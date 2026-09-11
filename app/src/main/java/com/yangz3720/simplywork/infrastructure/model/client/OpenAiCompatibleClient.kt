package com.yangz3720.simplywork.infrastructure.model.client

import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/**
 * Universal OpenAI-compatible Model Provider client.
 * Supports OpenAI, DeepSeek, Ollama, vLLM, and any OpenAI Chat Completions compatible endpoint.
 */
class OpenAiCompatibleClient(
    val config: ModelConfig,
    httpClient: HttpClient? = null
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

    /** 一次性客户端（模型列表拉取等临时实例）用完关闭底层引擎，避免线程泄漏。 */
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
     * OpenAI-compatible `GET {base}/models` → sorted model ids（设置页"获取模型列表"）。
     * 请求头与 generate 相同（Bearer + custom headers）。
     */
    override suspend fun listModels(): Result<List<String>> = runCatching {
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
                (entry as? JsonObject)?.get("id")
                    ?.let { id -> id as? kotlinx.serialization.json.JsonPrimitive }
                    ?.takeIf { it.isString }
                    ?.content
            }
            .sorted()
    }

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
                            val prompt = usageObj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                            val completion = usageObj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                            val usage = TokenUsage(promptTokens = prompt, completionTokens = completion)
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
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", message.content)
                        })
                        message.attachments.forEach { att ->
                            when (att) {
                                is Attachment.Image -> {
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", "data:${att.mimeType};base64,${att.base64Data}")
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

        val usageObj = json["usage"]?.jsonObject
        val promptTokens = usageObj?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = usageObj?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val tokenUsage = TokenUsage(promptTokens, completionTokens)

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
