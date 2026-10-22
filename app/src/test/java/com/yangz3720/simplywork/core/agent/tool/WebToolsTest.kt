package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `web_search` / `web_fetch`: HTML parsers against fixtures, the Bing→DDG fallback chain
 * over a MockEngine, argument validation, and the loopback/private-network guard.
 *
 * Everything runs offline: the search "engines" are canned HTML pages, so a markup change
 * in production breaks these fixtures first (loudly) rather than silently returning nothing.
 */
class WebToolsTest {

    private fun context() = ToolExecutionContext(sessionId = "s1")

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler))

    private fun htmlClient(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient =
        client { respond(ByteReadChannel(body), status, headersOf("Content-Type", "text/html; charset=utf-8")) }

    private fun tools(client: HttpClient) = WebTools.registeredTools(client).associateBy { it.definition.name }

    private fun searchArgs(query: String, maxResults: Int? = null): JsonObject = buildJsonObject {
        put("query", query)
        maxResults?.let { put("maxResults", it) }
    }

    private fun fetchArgs(url: String, maxChars: Int? = null): JsonObject = buildJsonObject {
        put("url", url)
        maxChars?.let { put("maxChars", it) }
    }

    // ----- registration -----

    @Test
    fun `both tools are registered read-only and SAFE`() {
        val tools = tools(htmlClient("<html></html>")).keys
        assertEquals(setOf("web_search", "web_fetch"), tools)
    }

    // ----- search: Bing parsing + success -----

    @Test
    fun `web_search parses bing results`() = runTest {
        val bingHtml = """
            <html><body><ol id="b_results">
            <li class="b_algo">
              <h2><a href="https://example.com/kotlin">Kotlin Programming Language</a></h2>
              <div class="b_caption"><p>Kotlin is a modern <b>programming</b> language.</p></div>
            </li>
            <li class="b_algo">
              <h2><a href="https://example.org/docs">Official Docs &amp; Guides</a></h2>
              <div class="b_caption"><p>Read the &quot;guide&quot; first.</p></div>
            </li>
            </ol></body></html>
        """.trimIndent()
        var requested = ""
        val tool = tools(client { request ->
            requested = request.url.toString()
            respond(ByteReadChannel(bingHtml), HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
        }).getValue(WebTools.SEARCH_TOOL_NAME)

        val parsed = tool.parse(searchArgs("kotlin coroutines")).getOrThrow()
        val result = tool.execute(parsed, context())

        assertEquals("success", result["status"]?.jsonPrimitive?.content)
        assertEquals("bing", result["engine"]?.jsonPrimitive?.content)
        assertTrue(requested.contains("q=kotlin"))
        val results = result["results"]!!.jsonArray
        assertEquals(2, results.size)
        val first = results[0].jsonObject
        assertEquals("Kotlin Programming Language", first["title"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/kotlin", first["url"]?.jsonPrimitive?.content)
        assertTrue(first["snippet"]?.jsonPrimitive?.content!!.contains("Kotlin is a modern"))
        // entities decoded
        assertEquals("Official Docs & Guides", results[1].jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `web_search unwraps bing ck-a redirect urls`() = runTest {
        val real = "https://kotlinlang.org/docs/home.html"
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(real.toByteArray())
        val bingHtml = """
            <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&&p=abc&amp;u=a1$encoded">Kotlin docs</a></h2><p>snippet</p></li>
        """.trimIndent()
        val tool = tools(htmlClient(bingHtml)).getValue(WebTools.SEARCH_TOOL_NAME)

        val result = tool.execute(tool.parse(searchArgs("kotlin")).getOrThrow(), context())
        val url = result["results"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content
        assertEquals(real, url)
    }

    // ----- search: fallback chain -----

    @Test
    fun `web_search falls back to duckduckgo when bing fails`() = runTest {
        val ddgHtml = """
            <html><body>
            <a rel="nofollow" class="result__a" href="https://example.com/ddg-result">DDG Result Title</a>
            <a class="result__snippet" href="https://example.com/ddg-result">The snippet text.</a>
            </body></html>
        """.trimIndent()
        val engine = MockEngine { request ->
            if (request.url.host.contains("bing")) {
                respond(ByteReadChannel("server error"), HttpStatusCode.InternalServerError)
            } else {
                respond(ByteReadChannel(ddgHtml), HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        }
        val tool = tools(HttpClient(engine)).getValue(WebTools.SEARCH_TOOL_NAME)

        val result = tool.execute(tool.parse(searchArgs("anything")).getOrThrow(), context())
        assertEquals("success", result["status"]?.jsonPrimitive?.content)
        assertEquals("duckduckgo", result["engine"]?.jsonPrimitive?.content)
        val first = result["results"]!!.jsonArray[0].jsonObject
        assertEquals("DDG Result Title", first["title"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/ddg-result", first["url"]?.jsonPrimitive?.content)
        assertEquals("The snippet text.", first["snippet"]?.jsonPrimitive?.content)
    }

    @Test
    fun `web_search unwraps duckduckgo uddg redirect`() = runTest {
        val ddgHtml = """
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Ftarget&amp;rut=xyz">Title</a>
            <a class="result__snippet" href="x">snip</a>
        """.trimIndent()
        val tool = tools(htmlClient(ddgHtml)).getValue(WebTools.SEARCH_TOOL_NAME)

        val result = tool.execute(tool.parse(searchArgs("q")).getOrThrow(), context())
        // bing got the same html (no b_algo → 0 results) and fell through to ddg
        assertEquals("duckduckgo", result["engine"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/target", result["results"]!!.jsonArray[0].jsonObject["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `web_search reports an explicit error when every engine yields nothing`() = runTest {
        val tool = tools(htmlClient("<html><body>no results here</body></html>")).getValue(WebTools.SEARCH_TOOL_NAME)

        val result = tool.execute(tool.parse(searchArgs("obscure thing")).getOrThrow(), context())
        assertEquals("error", result["status"]?.jsonPrimitive?.content)
        assertTrue(result["reason"]!!.jsonPrimitive.content.contains("no search results"))
    }

    // ----- search: validation -----

    @Test
    fun `web_search rejects blank query and out-of-range maxResults`() {
        val tool = tools(htmlClient("")).getValue(WebTools.SEARCH_TOOL_NAME)
        assertTrue(tool.parse(searchArgs("   ")).isFailure)
        assertTrue(tool.parse(searchArgs("ok", maxResults = 0)).isFailure)
        assertTrue(tool.parse(searchArgs("ok", maxResults = 11)).isFailure)
        assertTrue(tool.parse(searchArgs("ok", maxResults = 5)).isSuccess)
    }

    // ----- fetch: happy path -----

    @Test
    fun `web_fetch strips html to readable text`() = runTest {
        val page = """
            <html><head><title>Example Page</title><style>body{color:red}</style>
            <script>alert('x')</script></head>
            <body><h1>Heading</h1><p>First paragraph.</p>
            <p>Second &amp; final <a href="/x">link</a>.</p></body></html>
        """.trimIndent()
        val tool = tools(htmlClient(page)).getValue(WebTools.FETCH_TOOL_NAME)

        val result = tool.execute(tool.parse(fetchArgs("https://example.com/page")).getOrThrow(), context())
        assertEquals("success", result["status"]?.jsonPrimitive?.content)
        assertEquals("Example Page", result["title"]?.jsonPrimitive?.content)
        val content = result["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("Heading"))
        assertTrue(content.contains("First paragraph."))
        assertTrue(content.contains("Second & final"))
        assertFalse("script must be stripped", content.contains("alert"))
        assertFalse("style must be stripped", content.contains("color:red"))
        assertEquals(false, result["truncated"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `web_fetch truncates long pages and flags it`() = runTest {
        val long = "<html><body><p>" + "word ".repeat(5000) + "</p></body></html>"
        val tool = tools(htmlClient(long)).getValue(WebTools.FETCH_TOOL_NAME)

        val result = tool.execute(tool.parse(fetchArgs("https://example.com/long", maxChars = 100)).getOrThrow(), context())
        val content = result["content"]!!.jsonPrimitive.content
        assertEquals(100, content.length)
        assertEquals(true, result["truncated"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `web_fetch surfaces http error statuses as readable errors`() = runTest {
        val tool = tools(htmlClient("gone", HttpStatusCode.NotFound)).getValue(WebTools.FETCH_TOOL_NAME)

        val result = tool.execute(tool.parse(fetchArgs("https://example.com/missing")).getOrThrow(), context())
        assertEquals("error", result["status"]?.jsonPrimitive?.content)
        assertTrue(result["reason"]!!.jsonPrimitive.content.contains("HTTP 404"))
    }

    @Test
    fun `web_fetch follows redirects and reports the final url`() = runTest {
        val tool = tools(client { request ->
            when (request.url.encodedPath) {
                "/old" -> respond(
                    ByteReadChannel("moved"),
                    HttpStatusCode.Found,
                    headersOf("Location", "https://example.com/new")
                )
                else -> respond(ByteReadChannel("<html><title>New</title><body>hi</body></html>"), HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        }).getValue(WebTools.FETCH_TOOL_NAME)

        val result = tool.execute(tool.parse(fetchArgs("https://example.com/old")).getOrThrow(), context())
        assertEquals("success", result["status"]?.jsonPrimitive?.content)
        assertTrue(result["url"]!!.jsonPrimitive.content.endsWith("/new"))
    }

    // ----- fetch: guard rails -----

    @Test
    fun `web_fetch refuses non-http and private-network targets`() = runTest {
        val tool = tools(htmlClient("")).getValue(WebTools.FETCH_TOOL_NAME)
        val refused = listOf(
            "file:///etc/passwd",
            "ftp://example.com/x",
            "http://localhost/admin",
            "http://127.0.0.1/",
            "http://192.168.1.1/",
            "http://10.0.0.5/",
            "http://172.16.0.1/",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/"
        )
        for (url in refused) {
            val result = tool.execute(tool.parse(fetchArgs(url)).getOrThrow(), context())
            assertEquals("refused: $url", "error", result["status"]?.jsonPrimitive?.content)
            assertNotNull(result["reason"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `web_fetch accepts public addresses and validates maxChars`() {
        val tool = tools(htmlClient("")).getValue(WebTools.FETCH_TOOL_NAME)
        assertNull(WebTools.refuseTarget(io.ktor.http.Url("https://example.com/a")))
        assertNull(WebTools.refuseTarget(io.ktor.http.Url("https://8.8.8.8/")))
        assertNotNull(WebTools.refuseTarget(io.ktor.http.Url("http://192.168.0.1/")))
        assertNotNull(WebTools.refuseTarget(io.ktor.http.Url("http://my.local/")))

        assertTrue(tool.parse(fetchArgs("https://example.com", maxChars = 0)).isFailure)
        assertTrue(tool.parse(fetchArgs("https://example.com", maxChars = 8001)).isFailure)
        assertTrue(tool.parse(fetchArgs("https://example.com", maxChars = 4000)).isSuccess)
        assertTrue(tool.parse(fetchArgs("  ")).isFailure)
    }

    // ----- parsers in isolation -----

    @Test
    fun `htmlToText collapses whitespace and drops head junk`() {
        val text = WebTools.htmlToText(
            "<html><head><title>T</title></head><body><p>a</p><div>b</div><br>c</p></body></html>"
        )
        // Tags become spaces; block ends become newlines; space runs collapse.
        assertEquals("T a\nb\n\nc", text)
    }

    @Test
    fun `decodeHtmlEntities handles named numeric and unknown entities`() {
        assertEquals("a & b < c > d \"e\" 'f' g", WebTools.decodeHtmlEntities("a &amp; b &lt; c &gt; d &quot;e&quot; &#39;f&#39; g"))
        assertEquals("Aé", WebTools.decodeHtmlEntities("A&#xE9;"))
        assertEquals("&nope;", WebTools.decodeHtmlEntities("&nope;"))
    }

    // danger level: both SAFE — a network read touches no user data
    @Test
    fun `tools expose SAFE danger level`() {
        // RegisteredTool.dangerLevel is what the audit gate reads; asserted via registration
        val client = htmlClient("")
        WebTools.registeredTools(client).forEach { tool ->
            assertEquals(DangerLevel.SAFE, tool.dangerLevel)
        }
    }
}
