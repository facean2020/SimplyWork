package com.yangz3720.simplywork.infrastructure.model

import com.yangz3720.simplywork.core.attachment.InMemoryImageBlobStore
import com.yangz3720.simplywork.infrastructure.model.client.OpenAiCompatibleClient
import com.yangz3720.simplywork.infrastructure.model.config.ModelConfig
import com.yangz3720.simplywork.infrastructure.model.model.*
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModelProviderTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setup() {
        AgentLogger.isEnabled = true
    }

    @Test
    fun testMessageSerializationPolymorphism() {
        val messages: List<Message> = listOf(
            Message.System(content = "You are a helpful assistant."),
            Message.User(content = "Hello, what is 2+2?"),
            Message.Assistant(
                content = "Let me calculate.",
                toolCalls = listOf(
                    ToolCall(
                        id = "call_123",
                        name = "calculator",
                        argumentsJson = """{"expression":"2+2"}"""
                    )
                ),
                reasoningContent = "The user asked for math."
            ),
            Message.ToolResult(
                toolCallId = "call_123",
                toolName = "calculator",
                content = "4"
            )
        )

        val serialized = json.encodeToString(messages)
        assertTrue(serialized.contains(""""type":"system"""") || serialized.contains(""""system""""))
        assertTrue(serialized.contains(""""calculator""""))
        assertTrue(serialized.contains(""""call_123""""))

        val deserialized = json.decodeFromString<List<Message>>(serialized)
        assertEquals(4, deserialized.size)
        assertTrue(deserialized[0] is Message.System)
        assertTrue(deserialized[1] is Message.User)
        assertTrue(deserialized[2] is Message.Assistant)
        assertTrue(deserialized[3] is Message.ToolResult)

        val assistantMsg = deserialized[2] as Message.Assistant
        assertEquals("The user asked for math.", assistantMsg.reasoningContent)
        assertEquals(1, assistantMsg.toolCalls.size)
        assertEquals("calculator", assistantMsg.toolCalls[0].name)
    }

    @Serializable
    data class CalcArgs(val expression: String)

    @Test
    fun testToolCallArgumentDecoding() {
        val toolCall = ToolCall(
            id = "call_999",
            name = "calc",
            argumentsJson = """{"expression":"5*5"}"""
        )

        val decodedResult = toolCall.decodeArguments(json, CalcArgs.serializer())
        assertTrue(decodedResult.isSuccess)
        assertEquals("5*5", decodedResult.getOrThrow().expression)
    }

    @Test
    fun testOpenAiCompatibleClientSingleGenerate() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("/chat/completions", request.url.encodedPath)
            respond(
                content = ByteReadChannel(
                    """
                    {
                      "id": "chatcmpl-test-01",
                      "model": "deepseek-v3",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "42 is the answer."
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 15,
                        "completion_tokens": 6,
                        "total_tokens": 21
                      }
                    }
                    """.trimIndent()
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val testHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val config = ModelConfig(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            defaultModel = "deepseek-v3"
        )
        val client = OpenAiCompatibleClient(config, testHttpClient)

        val request = ModelRequest(
            messages = listOf(Message.User("What is the meaning of life?")),
            temperature = 0.7f
        )

        val result = client.generate(request)
        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals("chatcmpl-test-01", response.id)
        assertEquals("deepseek-v3", response.model)
        assertEquals("42 is the answer.", response.text)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(15, response.usage.promptTokens)
        assertEquals(6, response.usage.completionTokens)
        assertEquals(21, response.usage.totalTokens)
        assertTrue(response.isFinalAnswer)
    }

    @Test
    fun testOpenAiCompatibleClientStreaming() = runTest {
        val ssePayload = """
            data: {"id":"chatcmpl-stream","model":"deepseek-r1","choices":[{"index":0,"delta":{"reasoning_content":"Thinking step 1"},"finish_reason":null}]}
            
            data: {"id":"chatcmpl-stream","model":"deepseek-r1","choices":[{"index":0,"delta":{"content":"Result is "},"finish_reason":null}]}
            
            data: {"id":"chatcmpl-stream","model":"deepseek-r1","choices":[{"index":0,"delta":{"content":"100."},"finish_reason":null}]}
            
            data: {"id":"chatcmpl-stream","model":"deepseek-r1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":8}}
            
            data: [DONE]
            
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(ssePayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val testHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val config = ModelConfig(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            defaultModel = "deepseek-r1"
        )
        val client = OpenAiCompatibleClient(config, testHttpClient)

        val request = ModelRequest(messages = listOf(Message.User("Compute 10*10")))
        val chunks = client.generateStream(request).toList()

        assertTrue(chunks.any { it is ModelStreamChunk.ReasoningDelta && it.delta == "Thinking step 1" })
        assertTrue(chunks.any { it is ModelStreamChunk.TextDelta && it.delta == "Result is " })
        assertTrue(chunks.any { it is ModelStreamChunk.TextDelta && it.delta == "100." })
        assertTrue(chunks.any { it is ModelStreamChunk.UsageUpdate && it.usage.totalTokens == 18 })

        val completed = chunks.filterIsInstance<ModelStreamChunk.Completed>().firstOrNull()
        assertNotNull(completed)
        assertEquals("Result is 100.", completed?.response?.text)
        assertEquals("Thinking step 1", completed?.response?.message?.reasoningContent)
        assertEquals(FinishReason.STOP, completed?.finishReason)
    }

    /**
     * Regression: SSE chunks often carry explicit JSON nulls (`"usage":null`, `"delta":null`).
     * `JsonNull` is not Kotlin `null` -- `?.jsonObject` does NOT skip it and used to throw
     * "Element class JsonNull is not a JsonObject", killing the whole chat turn.
     */
    @Test
    fun testStreamingToleratesJsonNullFields() = runTest {
        val ssePayload = """
            data: {"id":"chatcmpl-nulls","model":"gpt-4o","choices":[{"index":0,"delta":null,"finish_reason":null}],"usage":null}

            data: {"id":"chatcmpl-nulls","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":null}],"usage":null}

            data: {"id":"chatcmpl-nulls","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":null}

            data: [DONE]

        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(ssePayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val testHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val config = ModelConfig(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            defaultModel = "gpt-4o"
        )
        val client = OpenAiCompatibleClient(config, testHttpClient)

        val chunks = client.generateStream(ModelRequest(messages = listOf(Message.User("hi")))).toList()

        assertTrue(chunks.any { it is ModelStreamChunk.TextDelta && it.delta == "ok" })
        val completed = chunks.filterIsInstance<ModelStreamChunk.Completed>().single()
        assertEquals("ok", completed.response.text)
    }

    @Test
    fun testOpenAiCompatibleClientStreamingWithToolCalls() = runTest {
        val ssePayload = """
            data: {"id":"chatcmpl-tool","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"run_js","arguments":"{\"code\":"}}]},"finish_reason":null}]}
            
            data: {"id":"chatcmpl-tool","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"1+1\"}"}}]},"finish_reason":null}]}
            
            data: {"id":"chatcmpl-tool","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
            
            data: [DONE]
            
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(ssePayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val testHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val config = ModelConfig(
            baseUrl = "https://api.example.com",
            apiKey = "sk-test",
            defaultModel = "gpt-4o"
        )
        val client = OpenAiCompatibleClient(config, testHttpClient)

        val toolDef = ToolDefinition(
            name = "run_js",
            description = "Run JS code in sandbox",
            parametersSchema = buildJsonObject {
                put("type", "object")
            }
        )

        val request = ModelRequest(
            messages = listOf(Message.User("Run 1+1 in sandbox")),
            tools = listOf(toolDef)
        )

        val chunks = client.generateStream(request).toList()
        val toolCallChunks = chunks.filterIsInstance<ModelStreamChunk.ToolCallChunk>()
        assertEquals(2, toolCallChunks.size)

        val completed = chunks.filterIsInstance<ModelStreamChunk.Completed>().firstOrNull()
        assertNotNull(completed)
        assertEquals(FinishReason.TOOL_CALLS, completed?.finishReason)
        assertTrue(completed?.response?.hasToolCalls == true)
        val toolCall = completed?.response?.toolCalls?.firstOrNull()
        assertNotNull(toolCall)
        assertEquals("call_abc", toolCall?.id)
        assertEquals("run_js", toolCall?.name)
        assertEquals("""{"code":"1+1"}""", toolCall?.argumentsJson)
    }

    @Test
    fun testListModelsParsesIdsSortsAndSkipsInvalidEntries() = runTest {
        val body = """
            {"data":[
                {"id":"deepseek-chat","object":"model"},
                {"id":"deepseek-reasoner","object":"model"},
                {"object":"model"},
                {"id":123}
            ]}
        """.trimIndent()

        var capturedUrl: String? = null
        var capturedAuth: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val testHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val config = ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk-test", defaultModel = "m")
        val client = OpenAiCompatibleClient(config, testHttpClient)

        val result = client.listModels()

        assertTrue(result.isSuccess)
        // entries with a non-string id or a missing id are skipped; the result is sorted lexicographically.
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), result.getOrThrow())
        assertEquals("https://api.example.com/v1/models", capturedUrl)
        assertEquals("Bearer sk-test", capturedAuth)
    }

    @Test
    fun testListModelCatalogReadsContextLengthFromTheShapesProvidersActuallyUse() = runTest {
        val body = """
            {"data":[
                {"id":"openrouter-ish","context_length":128000},
                {"id":"vllm-ish","max_input_tokens":"32000"},
                {"id":"openrouter-nested","top_provider":{"context_length":200000}},
                {"id":"llamacpp-ish","meta":{"n_ctx_train":8192}},
                {"id":"openai-ish"},
                {"id":"bad-value","context_length":"not-a-number"},
                {"id":"zero-value","context_length":0}
            ]}
        """.trimIndent()
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = OpenAiCompatibleClient(
            ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk", defaultModel = "m"),
            HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } }
        )

        val catalog = client.listModelCatalog().getOrThrow().associate { it.id to it.contextTokens }

        assertEquals(128_000, catalog["openrouter-ish"])
        assertEquals(32_000, catalog["vllm-ish"])            // a number in string form is accepted too
        assertEquals(200_000, catalog["openrouter-nested"])  // nested inside top_provider
        assertEquals(8_192, catalog["llamacpp-ish"])         // llama.cpp's meta.n_ctx_train
        // the endpoint says nothing / the value is not a proper positive number -> null (= unknown), never guess a number into existence
        assertNull(catalog["openai-ish"])
        assertNull(catalog["bad-value"])
        assertNull(catalog["zero-value"])
    }

    @Test
    fun testListModelsStillReturnsSortedIdsFromTheCatalogPath() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel("""{"data":[{"id":"b-model","context_length":1000},{"id":"a-model"}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = OpenAiCompatibleClient(
            ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk", defaultModel = "m"),
            HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } }
        )

        // the id-only legacy endpoint must yield the same set as the catalog path, still in lexicographic order
        assertEquals(listOf("a-model", "b-model"), client.listModels().getOrThrow())
    }

    @Test
    fun testListModelsSurfacesHttpError() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{"error":{"message":"invalid key"}}"""),
                status = HttpStatusCode.Unauthorized
            )
        }
        val testHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val client = OpenAiCompatibleClient(
            ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "bad", defaultModel = "m"),
            testHttpClient
        )

        val result = client.listModels()

        assertTrue(result.isFailure)
    }

    // ----- v1.9: whatever the vendor disclosed beyond `id` -----

    /**
     * The four built-in vendors report wildly different subsets, so one body per vendor shape: OpenRouter (nested
     * under `architecture`/`top_provider`/`pricing`), DeepSeek (all top level), Qwen (nothing at all), and an
     * explicit text-only entry.
     */
    @Test
    fun testListModelCatalogReadsOutputLimitVisionToolsAndPricing() = runTest {
        val body = """
            {"data":[
              {"id":"openrouter-vision","context_length":128000,
               "top_provider":{"max_completion_tokens":16384},
               "architecture":{"input_modalities":["text","image"]},
               "supported_parameters":["tools","temperature"],
               "pricing":{"prompt":"0.00000015","completion":"0.0000006","input_cache_read":"0.000000075"}},
              {"id":"deepseek-flash","context_window":1048576,"max_output_tokens":393216,
               "input_modalities":["text","image"]},
              {"id":"qwen3.8-flash"},
              {"id":"plain-text","architecture":{"input_modalities":["text"]},
               "supported_parameters":["temperature"]}
            ]}
        """.trimIndent()
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = OpenAiCompatibleClient(
            ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk", defaultModel = "m"),
            HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } }
        )

        val byId = client.listModelCatalog().getOrThrow().associateBy { it.id }

        // OpenRouter: output limit nested in top_provider, vision under architecture, tools from supported_parameters
        val openrouter = byId.getValue("openrouter-vision")
        assertEquals(128_000, openrouter.contextTokens)
        assertEquals(16_384, openrouter.maxOutputTokens)
        assertTrue(openrouter.supportsVision)
        assertTrue(openrouter.supportsTools)
        // pricing is normalised from USD-per-token strings to USD per 1M tokens
        assertEquals(0.15, openrouter.pricing!!.inputPerMillion, 1e-9)
        assertEquals(0.60, openrouter.pricing!!.outputPerMillion, 1e-9)
        assertEquals(0.075, openrouter.pricing!!.cacheReadPerMillion!!, 1e-9)

        // DeepSeek: same information, different spelling, and no supported_parameters
        val deepseek = byId.getValue("deepseek-flash")
        assertEquals(1_048_576, deepseek.contextTokens)
        assertEquals(393_216, deepseek.maxOutputTokens)
        assertTrue(deepseek.supportsVision)
        // no supported_parameters -> tools stays optimistically true; the agent loop must not be locked out
        assertTrue(deepseek.supportsTools)
        assertNull(deepseek.pricing)

        // Qwen: discloses nothing -> unknown context/price, capabilities come from the built-in table. Real
        // measurement (2026-10-21): qwen3.8-flash *is* multimodal -- the whole 3.5+ line is, in fact.
        val qwen = byId.getValue("qwen3.8-flash")
        assertNull(qwen.contextTokens)
        assertNull(qwen.pricing)
        assertTrue(qwen.supportsVision)
        assertTrue(qwen.supportsTools)

        // An explicit text-only modality and an explicit parameter list without "tools" both mean no
        assertFalse(byId.getValue("plain-text").supportsVision)
        assertFalse(byId.getValue("plain-text").supportsTools)
    }

    @Test
    fun testVisionFallsBackToTheBuiltInTableWhenTheEndpointSaysNothing() = runTest {
        // Qwen never reports modalities; the table is what keeps its vision models usable.
        val body = """{"data":[{"id":"qwen3-vl-plus"},{"id":"qwen3.7-max"},{"id":"totally-unknown-model"}]}"""
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = OpenAiCompatibleClient(
            ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk", defaultModel = "m"),
            HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } }
        )

        val byId = client.listModelCatalog().getOrThrow().associateBy { it.id }

        assertTrue(byId.getValue("qwen3-vl-plus").supportsVision)   // known vision family
        assertFalse(byId.getValue("qwen3.7-max").supportsVision)    // sibling in the same line, text-only
        assertFalse(byId.getValue("totally-unknown-model").supportsVision)  // no opinion -> text-only
    }

    @Test
    fun testUsagePicksUpVendorExtras() = runTest {
        val body = """
            {"id":"x","object":"chat.completion","model":"m","created":1,
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":11,"completion_tokens":8,"total_tokens":19,"cost":0.0000123,
                      "is_byok":false,
                      "prompt_tokens_details":{"cached_tokens":4,"cache_write_tokens":0},
                      "completion_tokens_details":{"reasoning_tokens":7}}}
        """.trimIndent()
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = OpenAiCompatibleClient(
            ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk", defaultModel = "m"),
            HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } }
        )

        val usage = client.generate(ModelRequest(messages = listOf(Message.User("hi")))).getOrThrow().usage

        assertEquals(11, usage.promptTokens)
        assertEquals(8, usage.completionTokens)
        assertEquals(4, usage.cachedTokens)
        assertEquals(7, usage.reasoningTokens)
        assertEquals(0.0000123, usage.costUsd!!, 1e-12)
    }

    @Test
    fun testUsageFallsBackToDeepSeekCacheCountersWhenDetailsAreAbsent() = runTest {
        val body = """
            {"id":"x","object":"chat.completion","model":"m","created":1,
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":31,"completion_tokens":8,"total_tokens":39,
                      "prompt_cache_hit_tokens":12,"prompt_cache_miss_tokens":19}}
        """.trimIndent()
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = OpenAiCompatibleClient(
            ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk", defaultModel = "m"),
            HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } }
        )

        val usage = client.generate(ModelRequest(messages = listOf(Message.User("hi")))).getOrThrow().usage

        // DeepSeek reports the same cached-token count a second time under its own name
        assertEquals(12, usage.cachedTokens)
        // and no cost field anywhere -> stays null rather than being estimated
        assertNull(usage.costUsd)
    }

    @Test
    fun testImageAttachmentsAreHydratedFromTheBlobStoreIntoDataUrls() = runTest {
        val raw = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
        val blobs = InMemoryImageBlobStore().apply { save("img-1", raw) }
        var sentBody = ""
        val mockEngine = MockEngine { request ->
            sentBody = (request.body as TextContent).text
            respond(
                content = ByteReadChannel(
                    """{"id":"x","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = OpenAiCompatibleClient(
            ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk", defaultModel = "m"),
            HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } },
            imageBlobs = blobs
        )

        client.generate(
            ModelRequest(
                messages = listOf(
                    Message.User(
                        content = "看看这张图",
                        attachments = listOf(Attachment.Image(id = "img-1", mimeType = "image/webp", name = "cat.webp"))
                    )
                )
            )
        )

        // The JSONL holds only the id; the bytes are base64-encoded here, at request-format time.
        val expected = java.util.Base64.getEncoder().encodeToString(raw)
        assertTrue(sentBody.contains("\"type\":\"image_url\""))
        assertTrue(sentBody.contains("data:image/webp;base64,$expected"))
    }

    @Test
    fun testMissingImageBlobBecomesAnExplicitTextNoteRatherThanAFailedRequest() = runTest {
        var sentBody = ""
        val mockEngine = MockEngine { request ->
            sentBody = (request.body as TextContent).text
            respond(
                content = ByteReadChannel(
                    """{"id":"x","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = OpenAiCompatibleClient(
            ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "sk", defaultModel = "m"),
            HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } },
            imageBlobs = InMemoryImageBlobStore()
        )

        val result = client.generate(
            ModelRequest(
                messages = listOf(
                    Message.User(
                        content = "看图",
                        attachments = listOf(Attachment.Image(id = "gone", mimeType = "image/webp", name = "cat.webp"))
                    )
                )
            )
        )

        assertTrue(result.isSuccess)
        assertFalse(sentBody.contains("image_url"))
        assertTrue(sentBody.contains("图片已丢失"))
    }
}
