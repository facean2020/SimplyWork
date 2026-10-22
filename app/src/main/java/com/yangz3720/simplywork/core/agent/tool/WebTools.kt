package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.net.URLDecoder
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Web tool family: `web_search` (search-engine query) and `web_fetch` (read one URL).
 *
 * Both are read-only HTTP GETs that touch no user data, so the danger level is SAFE --
 * the model authors the query/URL itself, so nothing private leaves the device unless
 * the model puts it there (same stance as the model-provider traffic in [OpenAiCompatibleClient]).
 *
 * Search has no API key by design: it scrapes Bing HTML first (reachable from CN networks)
 * and falls back to DuckDuckGo's HTML endpoint. Scraping is inherently more brittle than a
 * paid API -- when an engine's markup changes, [parseBingHtml]/[parseDdgHtml] stop yielding
 * results and the chain reports an explicit error rather than a silent empty list.
 *
 * The HTTP client is injected (MockEngine in unit tests); the production instance is built
 * once by the composition root and shared across runtime rebuilds so the OkHttp engine is
 * not rebuilt per config change.
 */
object WebTools {

    private const val TAG = "WebTools"

    const val SEARCH_TOOL_NAME = "web_search"
    const val FETCH_TOOL_NAME = "web_fetch"

    /** Search results per call. Small on purpose: each result burns context budget. */
    const val DEFAULT_MAX_RESULTS = 8
    const val MAX_RESULTS_CAP = 10

    /**
     * `web_fetch` text budget. Capped at 8000 so the default tool-result gate
     * (maxToolResultChars = 8000) does not structurally shrink the payload -- past the
     * budget the gate would cut the content string down to 512 chars, which is worse
     * than an explicit `truncated=true` here.
     */
    const val DEFAULT_FETCH_CHARS = 5_000
    const val MAX_FETCH_CHARS = 8_000

    /** Hard cap on bytes pulled off the wire, whatever the Content-Length claims. */
    const val MAX_RESPONSE_BYTES = 512 * 1024

    private const val REQUEST_TIMEOUT_MS = 20_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L

    /** Snippet/title caps: one runaway result must not eat the whole result list budget. */
    private const val MAX_TITLE_CHARS = 160
    private const val MAX_SNIPPET_CHARS = 240

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    /** Production client: bounded, redirect-following, no cookies jar (no login sessions). */
    fun defaultClient(): HttpClient = HttpClient(OkHttp) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    fun registeredTools(client: HttpClient): List<RegisteredTool> =
        listOf(searchTool(client), fetchTool(client))

    // ----- Input -----

    @Serializable
    data class SearchInput(
        val query: String,
        val maxResults: Int? = null
    )

    @Serializable
    data class FetchInput(
        val url: String,
        val maxChars: Int? = null
    )

    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val snippet: String
    )

    // ----- web_search -----

    private fun searchTool(client: HttpClient): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = SEARCH_TOOL_NAME,
            description = "Search the web with a search engine (Bing, with DuckDuckGo as " +
                "fallback) and get back a list of results with title, URL and a short " +
                "snippet. Use it when you need current information that is not in your " +
                "training data. To READ the content of a specific page, call " +
                "$FETCH_TOOL_NAME with one of the returned URLs.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "The search query. Be specific; combine keywords.")
                    })
                    put("maxResults", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "How many results to return (1..$MAX_RESULTS_CAP, default $DEFAULT_MAX_RESULTS)."
                        )
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
            }
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw ->
            runCatching { json.decodeFromJsonElement(SearchInput.serializer(), raw) }.mapCatching { input ->
                require(input.query.isNotBlank()) { "query must not be blank" }
                input.maxResults?.let {
                    require(it in 1..MAX_RESULTS_CAP) { "maxResults must be between 1 and $MAX_RESULTS_CAP (got $it)" }
                }
                input
            }
        },
        runner = { parsed, _ ->
            val input = parsed as? SearchInput
                ?: return@RegisteredTool errorObject("$SEARCH_TOOL_NAME arguments did not parse")
            val maxResults = (input.maxResults ?: DEFAULT_MAX_RESULTS).coerceIn(1, MAX_RESULTS_CAP)
            search(client, input.query.trim(), maxResults)
        }
    )

    private suspend fun search(client: HttpClient, query: String, maxResults: Int): JsonObject {
        val failures = mutableListOf<String>()

        bingSearch(client, query, maxResults).fold(
            onSuccess = { results ->
                if (results.isNotEmpty()) {
                    return searchSuccess(query, "bing", results, maxResults)
                }
                failures += "bing: page parsed but yielded no results"
            },
            onFailure = { e ->
                failures += "bing: ${e.message ?: e::class.simpleName}"
            }
        )

        ddgSearch(client, query, maxResults).fold(
            onSuccess = { results ->
                if (results.isNotEmpty()) {
                    return searchSuccess(query, "duckduckgo", results, maxResults)
                }
                failures += "duckduckgo: page parsed but yielded no results"
            },
            onFailure = { e ->
                failures += "duckduckgo: ${e.message ?: e::class.simpleName}"
            }
        )

        AgentLogger.w(TAG, Throwable("no search results")) {
            "all engines empty for '$query': ${failures.joinToString("; ")}"
        }
        return errorObject(
            "no search results from any engine (${failures.joinToString("; ")}). " +
                "The search engines may be unreachable, rate-limiting this client, or their " +
                "page layout may have changed. Retry once; if it persists, tell the user " +
                "web search is currently unavailable."
        )
    }

    private suspend fun bingSearch(client: HttpClient, query: String, maxResults: Int): Result<List<SearchResultItem>> =
        runCatching {
            val response = client.get("https://www.bing.com/search") {
                parameter("q", query)
                parameter("count", maxResults.toString())
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }
            if (response.status.value !in 200..299) {
                throw IllegalStateException("HTTP ${response.status.value}")
            }
            parseBingHtml(response.bodyAsText()).take(maxResults)
        }

    private suspend fun ddgSearch(client: HttpClient, query: String, maxResults: Int): Result<List<SearchResultItem>> =
        runCatching {
            val response = client.get("https://html.duckduckgo.com/html/") {
                parameter("q", query)
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }
            if (response.status.value !in 200..299) {
                throw IllegalStateException("HTTP ${response.status.value}")
            }
            parseDdgHtml(response.bodyAsText()).take(maxResults)
        }

    private fun searchSuccess(query: String, engine: String, results: List<SearchResultItem>, maxResults: Int): JsonObject =
        buildJsonObject {
            put("status", "success")
            put("query", query)
            put("engine", engine)
            put("count", results.size)
            putJsonArray("results") {
                results.forEach { item ->
                    add(buildJsonObject {
                        put("title", item.title)
                        put("url", item.url)
                        put("snippet", item.snippet)
                    })
                }
            }
            if (results.size >= maxResults) {
                put("note", "result list capped at $maxResults; refine the query for more")
            }
        }

    // ----- web_fetch -----

    private fun fetchTool(client: HttpClient): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = FETCH_TOOL_NAME,
            description = "Fetch a web page over HTTP and return its text content (HTML is " +
                "stripped down to readable text). Use it to READ a specific URL -- e.g. one " +
                "returned by $SEARCH_TOOL_NAME, or any link the user gives you. Only http/https " +
                "URLs are accepted; loopback and private-network addresses are refused. The " +
                "content is truncated at maxChars -- check 'truncated' before treating it as " +
                "the full page.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute http(s) URL of the page to read.")
                    })
                    put("maxChars", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Cap on returned text characters (1..$MAX_FETCH_CHARS, default $DEFAULT_FETCH_CHARS)."
                        )
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("url"))))
            }
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw ->
            runCatching { json.decodeFromJsonElement(FetchInput.serializer(), raw) }.mapCatching { input ->
                require(input.url.isNotBlank()) { "url must not be blank" }
                input.maxChars?.let {
                    require(it in 1..MAX_FETCH_CHARS) { "maxChars must be between 1 and $MAX_FETCH_CHARS (got $it)" }
                }
                input
            }
        },
        runner = { parsed, _ ->
            val input = parsed as? FetchInput
                ?: return@RegisteredTool errorObject("$FETCH_TOOL_NAME arguments did not parse")
            val maxChars = (input.maxChars ?: DEFAULT_FETCH_CHARS).coerceIn(1, MAX_FETCH_CHARS)
            fetch(client, input.url.trim(), maxChars)
        }
    )

    private suspend fun fetch(client: HttpClient, rawUrl: String, maxChars: Int): JsonObject {
        val target = runCatching { Url(rawUrl) }.getOrElse {
            return errorObject("invalid URL '$rawUrl': ${it.message}")
        }
        when (val refusal = refuseTarget(target)) {
            null -> Unit
            else -> return errorObject(refusal)
        }

        return runCatching {
            val response = client.get(rawUrl) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }
            val status = response.status.value
            if (status !in 200..299) {
                return errorObject("HTTP $status for $rawUrl -- the page may need auth, be gone, or refuse bots")
            }

            val contentType = response.contentType()
            val charset = contentType?.charset() ?: Charsets.UTF_8
            val (bytes, responseTruncated) = readBounded(response)
            val raw = String(bytes, charset)

            val isHtml = contentType?.match(ContentType.Text.Html) == true ||
                contentType?.contentSubtype?.endsWith("html") == true ||
                looksLikeHtml(raw)
            val text = if (isHtml) htmlToText(raw) else raw

            val truncated = responseTruncated || text.length > maxChars
            buildJsonObject {
                put("status", "success")
                put("url", response.request.url.toString())
                put("contentType", contentType?.toString() ?: "unknown")
                put("contentFetchedChars", text.length)
                put("content", text.take(maxChars))
                put("maxChars", maxChars)
                put("truncated", truncated)
                if (isHtml) {
                    extractHtmlTitle(raw)?.let { put("title", it) }
                }
                if (truncated) {
                    put("note", "content was cut off -- it is NOT the full page; say so if you quote it")
                }
            }
        }.getOrElse { e ->
            AgentLogger.w(TAG, e) { "fetch failed for $rawUrl" }
            errorObject("fetch failed for $rawUrl: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Best-effort guard against the model being steered at the device's own network. */
    internal fun refuseTarget(target: Url): String? {
        if (target.protocol.name !in setOf("http", "https")) {
            return "only http/https URLs are allowed (got '${target.protocol.name}')"
        }
        if (target.host.isBlank()) {
            return "URL has no host"
        }
        // ktor keeps IPv6 literals bracketed in `host` ("[::1]"); normalise before checks.
        val host = target.host.lowercase().removeSuffix(".").removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            return "refusing to fetch loopback/local host '$host'"
        }
        if (isPrivateIpv4(host) || isLoopbackIpv6(host)) {
            return "refusing to fetch private-network address '$host'"
        }
        return null
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 0 ||                       // 0.0.0.0/8
            octets[0] == 10 ||                          // 10/8
            octets[0] == 127 ||                         // loopback
            (octets[0] == 169 && octets[1] == 254) ||   // link-local
            (octets[0] == 172 && octets[1] in 16..31) ||// 172.16/12
            (octets[0] == 192 && octets[1] == 168)      // 192.168/16
    }

    private fun isLoopbackIpv6(host: String): Boolean =
        host == "::1" || host == "0:0:0:0:0:0:0:1" || host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd")

    /**
     * Reads at most [MAX_RESPONSE_BYTES] + 1 bytes and reports whether the wire held more.
     * The +1 probe is what makes `truncated` honest: without it a cut-off body looks identical
     * to a complete one.
     */
    private suspend fun readBounded(response: HttpResponse): Pair<ByteArray, Boolean> {
        val bytes = response.bodyAsChannel()
            .readRemaining(MAX_RESPONSE_BYTES + 1L)
            .readByteArray()
        return if (bytes.size > MAX_RESPONSE_BYTES) {
            bytes.copyOf(MAX_RESPONSE_BYTES) to true
        } else {
            bytes to false
        }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.take(512).lowercase()
        return head.contains("<html") || head.contains("<!doctype html")
    }

    // ----- HTML parsing (no jsoup: the markup we need is shallow, and the parsers are
    // pinned by fixtures in WebToolsTest so a layout change fails loudly there) -----

    internal fun parseBingHtml(html: String): List<SearchResultItem> {
        val results = mutableListOf<SearchResultItem>()
        // Each organic result lives in <li class="b_algo ...">...</li>; title/link in the first
        // <h2><a href=...>, snippet in the first <p> of the block (b_caption or alike).
        val blocks = Regex("""<li\s[^>]*class="[^"]*b_algo[^"]*"[^>]*>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .map { it.groupValues[1] }
        for (block in blocks) {
            if (results.size >= MAX_RESULTS_CAP) break
            val link = Regex("""<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(block) ?: continue
            val url = unwrapBingUrl(link.groupValues[1].trim())
            if (url.isBlank()) continue
            val title = cleanInline(link.groupValues[2])
            val snippet = Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(block)?.groupValues?.get(1)
                ?.let { cleanInline(it) }
                .orEmpty()
            results += SearchResultItem(
                title = title.take(MAX_TITLE_CHARS),
                url = url,
                snippet = snippet.take(MAX_SNIPPET_CHARS)
            )
        }
        return results
    }

    internal fun parseDdgHtml(html: String): List<SearchResultItem> {
        val titles = mutableListOf<Pair<String, String>>() // url to title
        val snippets = mutableListOf<String>()

        // Result links: <a rel="nofollow" class="result__a" href="...">Title</a> (attribute
        // order varies, so class/href are matched anywhere inside the open tag). Walking
        // whole anchors keeps href (usually BEFORE class) and title paired.
        val anchorPattern = Regex("""<a\s[^>]*class="[^"]*result__a[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val hrefPattern = Regex("""href="([^"]+)"""", RegexOption.IGNORE_CASE)
        for (match in Regex("""<a\s[^>]*>.*?</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html)) {
            val anchor = match.value
            val inner = anchorPattern.find(anchor)?.groupValues?.get(1) ?: continue
            val href = hrefPattern.find(anchor)?.groupValues?.get(1)?.trim() ?: continue
            val url = unwrapDdgUrl(href)
            if (url.isBlank()) continue
            val title = cleanInline(inner)
            if (title.isNotBlank()) {
                titles += url to title.take(MAX_TITLE_CHARS)
            }
        }

        val snippetPattern = Regex("""<a\s[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        snippetPattern.findAll(html).forEach {
            snippets += cleanInline(it.groupValues[1]).take(MAX_SNIPPET_CHARS)
        }

        return titles.take(MAX_RESULTS_CAP).mapIndexed { index, (url, title) ->
            SearchResultItem(title = title, url = url, snippet = snippets.getOrElse(index) { "" })
        }
    }

    /** Bing wraps targets in /ck/a?...&u=a1<url-safe-base64>; unwrap so results carry the real URL. */
    internal fun unwrapBingUrl(href: String): String {
        val decoded = decodeHtmlEntities(href)
        return runCatching {
            val url = Url(decoded)
            if (!url.host.endsWith("bing.com") || !url.encodedPath.startsWith("/ck/a")) return decoded
            val u = url.parameters["u"] ?: return decoded
            if (!u.startsWith("a1")) return decoded
            val body = u.removePrefix("a1")
            val padded = body + "=".repeat((4 - body.length % 4) % 4)
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrDefault(decoded)
    }

    /** DDG wraps targets in /l/?uddg=<percent-encoded real URL>; unwrap the same way. */
    internal fun unwrapDdgUrl(href: String): String {
        val withScheme = when {
            href.startsWith("//") -> "https:$href"
            else -> href
        }
        return runCatching {
            val url = Url(withScheme)
            if (!url.host.endsWith("duckduckgo.com")) return withScheme
            val uddg = url.parameters["uddg"] ?: return withScheme
            URLDecoder.decode(uddg, "UTF-8")
        }.getOrDefault(withScheme)
    }

    // ----- HTML → text (fetch path) -----

    internal fun extractHtmlTitle(html: String): String? =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues?.get(1)
            ?.let { cleanInline(it) }
            ?.take(MAX_TITLE_CHARS)
            ?.takeIf { it.isNotBlank() }

    /**
     * Strips a page down to readable text: script/style/noscript and comments are dropped
     * whole (their source would leak into the reading), block-level ends become newlines,
     * everything else is untagged and entity-decoded, blank runs collapse.
     */
    internal fun htmlToText(html: String): String {
        var s = html
        s = Regex("""<(script|style|noscript)[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, " ")
        s = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL).replace(s, " ")
        s = Regex("""(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article|/header|/footer)[^>]*>""").replace(s, "\n")
        s = Regex("""<[^>]+>""").replace(s, " ")
        s = decodeHtmlEntities(s)
        return s
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    internal fun cleanInline(htmlFragment: String): String =
        decodeHtmlEntities(Regex("""<[^>]+>""").replace(htmlFragment, " "))
            .replace(Regex("\\s+"), " ")
            .trim()

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“"
    )

    internal fun decodeHtmlEntities(text: String): String =
        Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""").replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { code -> String(Character.toChars(code)) } ?: match.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: match.value
                else -> ENTITIES[body.lowercase()] ?: match.value
            }
        }

    // ----- Helpers -----

    private fun errorObject(message: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("reason", message)
    }
}
