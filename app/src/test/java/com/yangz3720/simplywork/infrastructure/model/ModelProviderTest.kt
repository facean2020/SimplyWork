package com.yangz3720.simplywork.infrastructure.model

import com.yangz3720.simplywork.infrastructure.model.client.OpenAiCompatibleClient
import com.yangz3720.simplywork.infrastructure.model.config.ModelConfig
import com.yangz3720.simplywork.infrastructure.model.model.*
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
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
}
