package com.yangz3720.simplywork.infrastructure.model.client

import com.yangz3720.simplywork.core.attachment.ImageBlobStore
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.model.ModelInfo
import com.yangz3720.simplywork.infrastructure.model.ModelPricing
import com.yangz3720.simplywork.infrastructure.model.ModelVisionTable
import com.yangz3720.simplywork.infrastructure.model.ModelProvider
import com.yangz3720.simplywork.infrastructure.model.config.ModelCapability
import com.yangz3720.simplywork.infrastructure.model.config.ModelConfig
import com.yangz3720.simplywork.infrastructure.model.model.*
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/**
 * Universal OpenAI-compatible Model Provider client.
 * Supports OpenAI, DeepSeek, Ollama, vLLM, and any OpenAI Chat Completions compatible endpoint.
 *
 * [imageBlobs] resolves an [Attachment.Image] reference to raw bytes at request-format time:
 * the session JSONL never holds base64, so the bytes are loaded (and base64-encoded for the
 * `data:` URL) only for the request that actually needs them.
 */
class OpenAiCompatibleClient(
    val config: ModelConfig,
    httpClient: HttpClient? = null,
    private val imageBlobs: ImageBlobStore? = null
) : ModelProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        prettyPrint = false
    }

    private val client: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = config.timeoutMs
        }
    }

    override val providerId: String = "openai-compatible"

    override val supportedCapabilities: Set<ModelCapability> = setOf(
        ModelCapability.STREAMING,
        ModelCapability.TOOL_CALLING,
        ModelCapability.REASONING_CONTENT,
        ModelCapability.SYSTEM_PROMPT
    )

    private val tag = "OpenAiClient"

    private companion object {
        /** Vendors quote USD per single token; the UI speaks per million. */
        const val TOKENS_PER_MILLION = 1_000_000.0
    }

    /** Possible field names for the context length, see [contextTokensOf]. */
    private val CONTEXT_KEYS = listOf(
        "context_length", "context_window", "max_context_length", "max_input_tokens", "max_model_len"
    )

    /**
     * Possible field names for the generation limit, see [maxOutputTokensOf].
     *
     * `max_completion_tokens` is the newer OpenAI spelling and is what DeepSeek and OpenRouter report;
     * `max_output_tokens` is DeepSeek's own top-level field.
     */
    private val MAX_OUTPUT_KEYS = listOf("max_output_tokens", "max_completion_tokens")

    /** Throwaway client (temporary instances such as model-list fetching): close the underlying engine when done to avoid leaking threads. */
    fun close() {
        client.close()
    }

    override suspend fun generate(request: ModelRequest): Result<ModelResponse> = runCatching {
        val endpoint = resolveEndpoint()
        val payload = buildRequestBody(request, stream = false)
        val payloadString = payload.toString()

        AgentLogger.d(tag) { "==> Single generate to $endpoint:\n$payloadString" }
        val startTime = System.currentTimeMillis()

        val response = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            if (config.apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
            config.customHeaders.forEach { (k, v) -> header(k, v) }
            setBody(payload)
        }

        val latency = System.currentTimeMillis() - startTime
        val responseBody = response.bodyAsText()

        AgentLogger.d(tag) { "<== Response [${response.status.value}] in ${latency}ms:\n$responseBody" }

        if (!response.status.isSuccess()) {
            handleHttpError(response.status.value, responseBody)
        }

        val responseJson = json.parseToJsonElement(responseBody).jsonObject
        parseModelResponse(responseJson, latency)
    }

    /**
     * OpenAI-compatible `GET {base}/models` -> sorted model ids (the settings page's "fetch model list").
     * Same headers as generate (Bearer + custom headers).
     */
    override suspend fun listModelCatalog(): Result<List<ModelInfo>> = runCatching {
        val endpoint = "${config.baseUrl.trimEnd('/')}/models"
        AgentLogger.d(tag) { "==> GET $endpoint" }

        val response = client.get(endpoint) {
            if (config.apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
            config.customHeaders.forEach { (k, v) -> header(k, v) }
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            handleHttpError(response.status.value, responseBody)
        }

        val data = json.parseToJsonElement(responseBody).jsonObject["data"]?.jsonArray
        data.orEmpty()
            .mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return@mapNotNull null
                ModelInfo(
                    id = id,
                    contextTokens = contextTokensOf(obj),
                    maxOutputTokens = maxOutputTokensOf(obj),
                    supportsVision = supportsVisionOf(obj, id),
                    supportsTools = supportsToolsOf(obj),
                    pricing = pricingOf(obj)
                )
            }
            .sortedBy { it.id }
    }

    /** Legacy id-only interface: derived from [listModelCatalog], so both paths hit the same endpoint. */
    override suspend fun listModels(): Result<List<String>> =
        listModelCatalog().map { catalog -> catalog.map { it.id } }

    /**
     * Read the context length out of one /models entry.
     *
     * OpenAI's spec only guarantees `id`, and every vendor names the context-length field as it pleases:
     * OpenRouter uses `context_length`, some use `context_window` / `max_context_length` /
     * `max_input_tokens`, llama.cpp stuffs it into `meta.n_ctx_train`, and OpenRouter also puts it in the
     * nested `top_provider`. So try them one by one and return null when nothing is recognised -- **never invent
     * a guessed value here**: overestimating a model's context gets the request rejected outright by the server, far more dangerous than "not knowing".
     */
    private fun contextTokensOf(entry: JsonObject): Int? {
        CONTEXT_KEYS.firstNotNullOfOrNull { key -> entry.positiveInt(key) }?.let { return it }
        (entry["top_provider"] as? JsonObject)?.let { nested ->
            CONTEXT_KEYS.firstNotNullOfOrNull { key -> nested.positiveInt(key) }?.let { return it }
        }
        (entry["meta"] as? JsonObject)?.positiveInt("n_ctx_train")?.let { return it }
        return null
    }

    private fun JsonObject.positiveInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }

    private fun JsonObject.decimal(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it >= 0 }

    /** A JSON array of strings; `null` when the key is absent or isn't an array at all. */
    private fun stringList(element: JsonElement?): List<String>? =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    /**
     * Read the generation limit out of one /models entry.
     *
     * Same discipline as [contextTokensOf]: several spellings, nested or not, `null` when nothing is recognised.
     * This is **not** the context window -- confusing the two would let a caller ask for more output tokens than
     * the model can produce.
     */
    private fun maxOutputTokensOf(entry: JsonObject): Int? {
        MAX_OUTPUT_KEYS.firstNotNullOfOrNull { key -> entry.positiveInt(key) }?.let { return it }
        (entry["top_provider"] as? JsonObject)?.let { nested ->
            MAX_OUTPUT_KEYS.firstNotNullOfOrNull { key -> nested.positiveInt(key) }?.let { return it }
        }
        return null
    }

    /**
     * Whether this model accepts image input. Order: what the endpoint reported > [ModelVisionTable] > `false`.
     *
     * The vendors that report it spell it differently -- OpenRouter nests `input_modalities` under `architecture`
     * and also offers a summary `modality` string (`"text+image+file->text"`), DeepSeek puts `input_modalities` at
     * the top level, Qwen reports neither (which is exactly why the built-in table exists).
     */
    private fun supportsVisionOf(entry: JsonObject, modelId: String): Boolean {
        val architecture = entry["architecture"] as? JsonObject
        val modalities = stringList(architecture?.get("input_modalities"))
            ?: stringList(entry["input_modalities"])
        if (modalities != null) {
            return modalities.any { it.equals("image", ignoreCase = true) }
        }
        // OpenRouter's summary form, e.g. "text+image+file->text": only the part before "->" describes the input.
        architecture?.get("modality")?.jsonPrimitive?.contentOrNull?.let { summary ->
            return summary.substringBefore("->").split("+").any { it.trim().equals("image", ignoreCase = true) }
        }
        return ModelVisionTable.supports(modelId)
    }

    /**
     * Whether this model accepts tool definitions.
     *
     * **Defaults to `true`** when the endpoint says nothing: OpenRouter publishes `supported_parameters`, but
     * DeepSeek and Qwen do not, and defaulting to `false` would declare every model of theirs incapable of the
     * agent loop's core operation. A tool schema a model ignores is a far cheaper failure than a capability the
     * app refuses to use.
     */
    private fun supportsToolsOf(entry: JsonObject): Boolean {
        val params = stringList(entry["supported_parameters"]) ?: return true
        return params.any { it.equals("tools", ignoreCase = true) }
    }

    /**
     * Read per-token pricing out of one /models entry, normalised to USD per 1M tokens.
     *
     * In practice only OpenRouter publishes this, and it quotes **strings of USD per single token**
     * (`"0.00000015"` = $0.15/M). Returns null when either the input or output price is missing rather than
     * presenting a half-filled price as a complete one. `pricing.overrides` (OpenRouter's time-of-day pricing) is
     * deliberately ignored -- the top-level figure is the one users recognise.
     */
    private fun pricingOf(entry: JsonObject): ModelPricing? {
        val pricing = entry["pricing"] as? JsonObject ?: return null
        val input = pricing.decimal("prompt") ?: return null
        val output = pricing.decimal("completion") ?: return null
        val scale = TOKENS_PER_MILLION
        return ModelPricing(
            inputPerMillion = input * scale,
            outputPerMillion = output * scale,
            cacheReadPerMillion = pricing.decimal("input_cache_read")?.times(scale)
        )
    }

    /**
     * Parse a `usage` object into [TokenUsage], picking up the vendor extras that all three built-in providers
     * agree on plus the ones only OpenRouter has.
     *
     * `cached_tokens` is read from `prompt_tokens_details` first because OpenAI/OpenRouter/Qwen all put it there;
     * DeepSeek reports the same number a second time as `prompt_cache_hit_tokens`, which is the fallback rather
     * than the primary. `cost` exists only on OpenRouter and stays null elsewhere -- it is never estimated.
     */
    private fun parseUsage(usageObj: JsonObject?): TokenUsage {
        if (usageObj == null) return TokenUsage()
        val prompt = usageObj.int("prompt_tokens") ?: 0
        val completion = usageObj.int("completion_tokens") ?: 0
        val promptDetails = usageObj["prompt_tokens_details"] as? JsonObject
        val completionDetails = usageObj["completion_tokens_details"] as? JsonObject
        return TokenUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = usageObj.int("total_tokens") ?: (prompt + completion),
            cachedTokens = promptDetails?.int("cached_tokens") ?: usageObj.int("prompt_cache_hit_tokens") ?: 0,
            reasoningTokens = completionDetails?.int("reasoning_tokens") ?: 0,
            costUsd = usageObj.decimal("cost")
        )
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    override fun generateStream(request: ModelRequest): Flow<ModelStreamChunk> = flow {
        val endpoint = resolveEndpoint()
        val payload = buildRequestBody(request, stream = true)
        val payloadString = payload.toString()

        AgentLogger.d(tag) { "==> Stream generate to $endpoint:\n$payloadString" }
        val startTime = System.currentTimeMillis()

        try {
            client.preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                if (config.apiKey.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                }
                config.customHeaders.forEach { (k, v) -> header(k, v) }
                setBody(payload)
            }.execute { httpResponse ->
                if (!httpResponse.status.isSuccess()) {
                    val errorBody = httpResponse.bodyAsText()
                    AgentLogger.e(tag) { "<== Stream HTTP Error [${httpResponse.status.value}]: $errorBody" }
                    handleHttpError(httpResponse.status.value, errorBody)
                }

                val channel: ByteReadChannel = httpResponse.bodyAsChannel()
                val accumulatedText = StringBuilder()
                val accumulatedReasoning = StringBuilder()
                val accumulatedToolCalls = mutableMapOf<Int, MutableToolCallBuilder>()
                var responseId = "chatcmpl-${System.currentTimeMillis()}"
                var responseModel = request.model ?: config.defaultModel
                var finishReason = FinishReason.STOP
                var tokenUsage: TokenUsage? = null

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue // Ping / comment line

                    if (trimmed.startsWith("data:")) {
                        val dataContent = trimmed.removePrefix("data:").trim()
                        if (dataContent == "[DONE]") {
                            AgentLogger.d(tag) { "<== Stream received [DONE]" }
                            break
                        }

                        val chunkJson = runCatching { json.parseToJsonElement(dataContent).jsonObject }.getOrNull()
                            ?: continue

                        chunkJson["id"]?.jsonPrimitive?.contentOrNull?.let { responseId = it }
                        chunkJson["model"]?.jsonPrimitive?.contentOrNull?.let { responseModel = it }

                        // Parse usage if present in stream
                        chunkJson["usage"]?.jsonObject?.let { usageObj ->
                            val usage = parseUsage(usageObj)
                            tokenUsage = usage
                            emit(ModelStreamChunk.UsageUpdate(usage))
                        }

                        val choices = chunkJson["choices"]?.jsonArray ?: continue
                        for (choice in choices) {
                            val choiceObj = choice.jsonObject
                            choiceObj["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
                                finishReason = parseFinishReason(it)
                            }

                            val delta = choiceObj["delta"]?.jsonObject ?: continue

                            // 1. Text Delta
                            delta["content"]?.jsonPrimitive?.contentOrNull?.let { textDelta ->
                                if (textDelta.isNotEmpty()) {
                                    accumulatedText.append(textDelta)
                                    AgentLogger.v(tag) { "TextDelta: $textDelta" }
                                    emit(ModelStreamChunk.TextDelta(textDelta))
                                }
                            }

                            // 2. Reasoning Delta (DeepSeek-R1 / Qwen reasoning format)
                            val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                                ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull
                            if (!reasoning.isNullOrEmpty()) {
                                accumulatedReasoning.append(reasoning)
                                AgentLogger.v(tag) { "ReasoningDelta: $reasoning" }
                                emit(ModelStreamChunk.ReasoningDelta(reasoning))
                            }

                            // 3. Tool Calls Delta
                            val toolCallsArray = delta["tool_calls"]?.jsonArray
                            if (toolCallsArray != null) {
                                for (tc in toolCallsArray) {
                                    val tcObj = tc.jsonObject
                                    val index = tcObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    val id = tcObj["id"]?.jsonPrimitive?.contentOrNull
                                    val fnObj = tcObj["function"]?.jsonObject
                                    val name = fnObj?.get("name")?.jsonPrimitive?.contentOrNull
                                    val argsDelta = fnObj?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()

                                    val builder = accumulatedToolCalls.getOrPut(index) { MutableToolCallBuilder(index) }
                                    if (id != null) builder.id = id
                                    if (name != null) builder.name = name
                                    builder.argumentsBuilder.append(argsDelta)

                                    emit(
                                        ModelStreamChunk.ToolCallChunk(
                                            index = index,
                                            id = id,
                                            name = name,
                                            argumentsDelta = argsDelta
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                val latency = System.currentTimeMillis() - startTime
                val assembledToolCalls = accumulatedToolCalls.values.map { it.build() }
                if (assembledToolCalls.isNotEmpty() && finishReason == FinishReason.STOP) {
                    finishReason = FinishReason.TOOL_CALLS
                }

                val finalResponse = ModelResponse(
                    id = responseId,
                    model = responseModel,
                    message = Message.Assistant(
                        content = accumulatedText.toString().ifEmpty { null },
                        toolCalls = assembledToolCalls,
                        reasoningContent = accumulatedReasoning.toString().ifEmpty { null }
                    ),
                    finishReason = finishReason,
                    usage = tokenUsage ?: TokenUsage(),
                    latencyMs = latency
                )

                AgentLogger.d(tag) { "<== Stream Completed in ${latency}ms (finishReason=$finishReason)" }
                emit(ModelStreamChunk.Completed(finishReason, finalResponse))
            }
        } catch (e: Throwable) {
            AgentLogger.e(tag, e) { "Streaming failure: ${e.message}" }
            emit(ModelStreamChunk.Error(e))
        }
    }

    private fun resolveEndpoint(): String {
        val base = config.baseUrl.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun buildRequestBody(request: ModelRequest, stream: Boolean): JsonObject {
        return buildJsonObject {
            put("model", request.model ?: config.defaultModel)
            put("stream", stream)

            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            request.topP?.let { put("top_p", it) }
            if (request.stopSequences.isNotEmpty()) {
                put("stop", buildJsonArray { request.stopSequences.forEach { add(it) } })
            }

            // Build Messages
            put("messages", buildJsonArray {
                request.systemPromptOverride?.let { sysPrompt ->
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", sysPrompt)
                    })
                }

                request.messages.forEach { msg ->
                    add(formatMessageToJson(msg))
                }
            })

            // Build Tools
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parametersSchema)
                                if (tool.isStrict) {
                                    put("strict", true)
                                }
                            })
                        })
                    }
                })

                when (val choice = request.toolChoice) {
                    ToolChoice.Auto -> put("tool_choice", "auto")
                    ToolChoice.None -> put("tool_choice", "none")
                    ToolChoice.Required -> put("tool_choice", "required")
                    is ToolChoice.Specific -> put("tool_choice", buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", choice.toolName)
                        })
                    })
                }
            }
        }
    }

    private fun formatMessageToJson(message: Message): JsonObject = buildJsonObject {
        when (message) {
            is Message.System -> {
                put("role", "system")
                put("content", message.content)
                message.name?.let { put("name", it) }
            }
            is Message.User -> {
                put("role", "user")
                if (message.attachments.isEmpty()) {
                    put("content", message.content)
                } else {
                    put("content", buildJsonArray {
                        // An upload-only turn has no text of its own; an empty text part is rejected by strict
                        // endpoints, and the attachments below already say everything there is to say.
                        if (message.content.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            })
                        }
                        message.attachments.forEach { att ->
                            when (att) {
                                is Attachment.Image -> {
                                    // Bytes are loaded here, not from the payload: the JSONL holds only the
                                    // blob id. A missing blob is reported as text so the turn stays coherent
                                    // instead of failing the whole request.
                                    val bytes = imageBlobs?.read(att.id)
                                    if (bytes != null) {
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject {
                                                put("url", "data:${att.mimeType};base64,${Base64.getEncoder().encodeToString(bytes)}")
                                            })
                                        })
                                    } else {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", "[图片已丢失：${att.name ?: "未命名"}]")
                                        })
                                    }
                                }
                                // Documents are already text: one labelled part. The label is what lets the model
                                // tell two uploaded files apart -- the text itself carries no file boundary.
                                is Attachment.Document -> {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", buildString {
                                            append("[附件：").append(att.name)
                                            if (att.truncated) append("（内容过长，仅保留开头部分）")
                                            append("]\n").append(att.text)
                                        })
                                    })
                                }
                            }
                        }
                    })
                }
            }
            is Message.Assistant -> {
                put("role", "assistant")
                put("content", message.content ?: "")
                if (message.toolCalls.isNotEmpty()) {
                    put("tool_calls", buildJsonArray {
                        message.toolCalls.forEach { tc ->
                            add(buildJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", tc.argumentsJson)
                                })
                            })
                        }
                    })
                }
            }
            is Message.ToolResult -> {
                put("role", "tool")
                put("tool_call_id", message.toolCallId)
                put("content", message.content)
            }
        }
    }

    private fun parseModelResponse(json: JsonObject, latencyMs: Long): ModelResponse {
        val id = json["id"]?.jsonPrimitive?.contentOrNull ?: "chatcmpl-${System.currentTimeMillis()}"
        val model = json["model"]?.jsonPrimitive?.contentOrNull ?: config.defaultModel

        val choices = json["choices"]?.jsonArray ?: emptyList()
        val firstChoice = choices.firstOrNull()?.jsonObject
            ?: throw InfrastructureException.Model.InvalidResponse("Empty choices array in model response")

        val rawFinishReason = firstChoice["finish_reason"]?.jsonPrimitive?.contentOrNull
        var finishReason = parseFinishReason(rawFinishReason)

        val messageObj = firstChoice["message"]?.jsonObject
            ?: throw InfrastructureException.Model.InvalidResponse("Missing message in model response choice")

        val content = messageObj["content"]?.jsonPrimitive?.contentOrNull
        val reasoning = messageObj["reasoning_content"]?.jsonPrimitive?.contentOrNull
            ?: messageObj["reasoning"]?.jsonPrimitive?.contentOrNull

        val toolCalls = mutableListOf<ToolCall>()
        messageObj["tool_calls"]?.jsonArray?.forEach { tcElement ->
            val tcObj = tcElement.jsonObject
            val tcId = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: "call_${System.currentTimeMillis()}"
            val fnObj = tcObj["function"]?.jsonObject
            val fnName = fnObj?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val fnArgs = fnObj?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
            toolCalls.add(ToolCall(id = tcId, name = fnName, argumentsJson = fnArgs))
        }

        if (toolCalls.isNotEmpty() && finishReason == FinishReason.STOP) {
            finishReason = FinishReason.TOOL_CALLS
        }

        val tokenUsage = parseUsage(json["usage"]?.jsonObject)

        return ModelResponse(
            id = id,
            model = model,
            message = Message.Assistant(
                content = content,
                toolCalls = toolCalls,
                reasoningContent = reasoning
            ),
            finishReason = finishReason,
            usage = tokenUsage,
            latencyMs = latencyMs
        )
    }

    private fun parseFinishReason(value: String?): FinishReason = when (value?.lowercase()) {
        "stop" -> FinishReason.STOP
        "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
        "length" -> FinishReason.LENGTH
        "content_filter" -> FinishReason.CONTENT_FILTER
        null -> FinishReason.STOP
        else -> FinishReason.UNKNOWN
    }

    private fun handleHttpError(statusCode: Int, body: String): Nothing {
        when (statusCode) {
            401, 403 -> throw InfrastructureException.Model.AuthenticationFailed(body)
            429 -> throw InfrastructureException.Model.RateLimitExceeded(null, body)
            400 -> {
                if (body.contains("context_length_exceeded", ignoreCase = true) ||
                    body.contains("max_tokens", ignoreCase = true)
                ) {
                    throw InfrastructureException.Model.ContextWindowExceeded(null, body)
                }
                throw InfrastructureException.Model.InvalidResponse("Bad request (400): $body")
            }
            in 500..599 -> throw InfrastructureException.Model.ServerError(statusCode, body)
            else -> throw InfrastructureException.Model.InvalidResponse("HTTP $statusCode: $body")
        }
    }

    private class MutableToolCallBuilder(val index: Int) {
        var id: String = "call_${System.currentTimeMillis()}_$index"
        var name: String = ""
        val argumentsBuilder = StringBuilder()

        fun build(): ToolCall = ToolCall(
            id = id,
            name = name,
            argumentsJson = argumentsBuilder.toString().ifEmpty { "{}" }
        )
    }
}
