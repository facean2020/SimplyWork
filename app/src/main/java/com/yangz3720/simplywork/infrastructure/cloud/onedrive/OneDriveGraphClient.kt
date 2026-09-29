package com.yangz3720.simplywork.infrastructure.cloud.onedrive

import com.yangz3720.simplywork.core.cloud.DeltaCursorExpired
import com.yangz3720.simplywork.core.cloud.RemoteDelta
import com.yangz3720.simplywork.core.cloud.RemoteDrive
import com.yangz3720.simplywork.core.cloud.RemoteItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The cloud returned a non-2xx status. [status] lets callers tell cases apart (410 goes to [DeltaCursorExpired], 401 triggers a renewal retry). */
class OneDriveHttpException(val status: Int, val detail: String) :
    RuntimeException("OneDrive 请求失败（HTTP $status）：${detail.take(300)}")

/** The response shape is not what we expect (missing deltaLink, a file treated as a folder, etc.). Fail explicitly, never guess silently. */
class OneDriveProtocolException(message: String) : RuntimeException(message)

/**
 * The OneDrive implementation on top of Microsoft Graph (v1.4).
 *
 * Talk REST directly with Ktor instead of pulling in `msgraph-sdk-java`: this app needs only 7 endpoints, and the
 * official Java SDK would drag in azure-identity plus a pile of reflection dependencies, utterly out of proportion for an on-device APK.
 * Parsing is hand-written with kotlinx.serialization (Graph's `folder`/`file`/`deleted` follow
 * a "the facet exists so it is true" semantics, which makes strongly typed deserialization awkward instead).
 *
 * Only `/me/drive` is supported (the personal account's own drive), no driveId — the product convention is "bind your own cloud drive".
 */
class OneDriveGraphClient(
    private val auth: OneDriveAuth,
    httpClient: HttpClient? = null
) : RemoteDrive {

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = httpClient ?: HttpClient(OkHttp) {
        // Defaults for metadata calls: these are all small JSON payloads, so 60s is more than enough. Bulk transfers (downloads) override
        // them on their own requests, see download().
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    fun close() = http.close()

    // ----- RemoteDrive -----

    override suspend fun folder(folderId: String): RemoteItem {
        val obj = json("/me/drive/items/${seg(folderId)}")
        val item = parseItem(obj) ?: throw OneDriveProtocolException("云端条目缺少 id：$folderId")
        if (!item.isFolder) throw OneDriveProtocolException("云端条目不是文件夹：${item.name}")
        return item
    }

    override suspend fun children(folderId: String): List<RemoteItem> {
        val out = ArrayList<RemoteItem>()
        var url: String? = "$BASE/me/drive/items/${seg(folderId)}/children?\$top=200"
        while (url != null) {
            val page = json(url)
            page["value"]?.jsonArray?.forEach { element ->
                parseItem(element.jsonObject)?.let(out::add)
            }
            url = page.string("@odata.nextLink")
        }
        return out
    }

    override suspend fun createFolder(parentFolderId: String, name: String): RemoteItem {
        val created = json(
            "/me/drive/items/${seg(parentFolderId)}/children",
            method = HttpMethod.Post,
            body = buildJsonObject {
                put("name", name)
                put("folder", buildJsonObject { })
                // fail: report an explicit error when the name already exists, rather than quietly packing two projects into the same cloud folder.
                put("@microsoft.graph.conflictBehavior", "fail")
            }
        )
        return parseItem(created) ?: throw OneDriveProtocolException("创建文件夹未返回条目：$name")
    }

    override suspend fun delta(folderId: String, deltaLink: String?): RemoteDelta {
        val items = ArrayList<RemoteItem>()
        var next: String? = deltaLink ?: "$BASE/me/drive/items/${seg(folderId)}/delta"
        var delta: String? = null
        while (next != null) {
            val page = try {
                json(next)
            } catch (e: OneDriveHttpException) {
                // Cursor expired: Graph requires a full rebuild from scratch following the new nextLink in Location.
                if (e.status == HttpStatusCode.Gone.value) {
                    throw DeltaCursorExpired("OneDrive delta 游标已过期，需要重建基线")
                }
                throw e
            }
            page["value"]?.jsonArray?.forEach { element ->
                parseItem(element.jsonObject)?.let(items::add)
            }
            delta = page.string("@odata.deltaLink")
            next = page.string("@odata.nextLink")
        }
        return RemoteDelta(
            items = items,
            deltaLink = delta ?: throw OneDriveProtocolException("delta 响应缺少 @odata.deltaLink"),
            reset = deltaLink == null
        )
    }

    override suspend fun download(itemId: String): ByteArray {
        val response = http.get("$BASE/me/drive/items/${seg(itemId)}/content") {
            header(HttpHeaders.Authorization, "Bearer ${auth.accessToken()}")
            // The metadata default of 60s is too tight for bulk transfer: a big file on a slow link taking longer than that is slow, not broken,
            // and failing on the 60s mark would make "pull" always fail on slow networks. The overall ceiling is relaxed here; a link that really does die
            // (no bytes received for a long time) is still caught by the 60s socket timeout.
            timeout { requestTimeoutMillis = BULK_REQUEST_TIMEOUT_MS }
        }
        if (!response.status.isSuccess()) {
            throw OneDriveHttpException(response.status.value, response.bodyAsText())
        }
        // /content is a 302 to a pre-authorised URL that OkHttp follows automatically; when the redirect goes to another host OkHttp
        // drops the Authorization header, which is exactly what we want (the pre-authorised URL neither accepts nor needs it).
        return response.readRawBytes()
    }

    override suspend fun upload(parentFolderId: String, fileName: String, bytes: ByteArray): RemoteItem {
        val path = "/me/drive/items/${seg(parentFolderId)}:/${seg(fileName)}:"
        if (bytes.size <= SIMPLE_UPLOAD_MAX_BYTES) {
            val response = raw("$BASE$path/content", method = HttpMethod.Put, bytes = bytes)
            return parseItem(json.parseToJsonElement(response.bodyAsText()).jsonObject)
                ?: throw OneDriveProtocolException("上传未返回条目：$fileName")
        }
        return uploadInSession(path, fileName, bytes)
    }

    override suspend fun deleteItem(itemId: String) {
        val response = send("/me/drive/items/${seg(itemId)}", HttpMethod.Delete, body = null, token = auth.accessToken())
        if (!response.status.isSuccess()) {
            throw OneDriveHttpException(response.status.value, response.bodyAsText())
        }
    }

    // ----- Large files: upload session (mandatory from 4MiB up; a simple upload would time out or be truncated)-----

    private suspend fun uploadInSession(itemPath: String, fileName: String, bytes: ByteArray): RemoteItem {
        val session = json(
            "$itemPath/createUploadSession",
            method = HttpMethod.Post,
            body = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("@microsoft.graph.conflictBehavior", "replace")
                        put("name", fileName)
                    }
                )
            }
        )
        val uploadUrl = session.string("uploadUrl")
            ?: throw OneDriveProtocolException("createUploadSession 未返回 uploadUrl")

        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + UPLOAD_CHUNK_BYTES, bytes.size)
            // uploadUrl is a pre-authorised URL: never send an Authorization header.
            val response = http.request(uploadUrl) {
                method = HttpMethod.Put
                contentType(ContentType.Application.OctetStream)
                header("Content-Range", "bytes $offset-${end - 1}/${bytes.size}")
                setBody(bytes.copyOfRange(offset, end))
            }
            if (!response.status.isSuccess()) {
                throw OneDriveHttpException(response.status.value, response.bodyAsText())
            }
            val text = response.bodyAsText()
            if (end == bytes.size) {
                return parseItem(json.parseToJsonElement(text).jsonObject)
                    ?: throw OneDriveProtocolException("上传会话结束但未返回条目：$fileName")
            }
            offset = end
        }
        throw OneDriveProtocolException("上传会话未产生结果：$fileName")
    }

    // ----- HTTP pipeline -----

    /**
     * Send a JSON request and parse it. A 401 (token just expired) forces a renewal and retries once;
     * a 429 reads `Retry-After` and retries with backoff (throttling on Graph is normal, not an error).
     */
    private suspend fun json(
        pathOrUrl: String,
        method: HttpMethod = HttpMethod.Get,
        body: JsonObject? = null
    ): JsonObject {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$BASE$pathOrUrl"
        val response = raw(url, method, body = body)
        val text = response.bodyAsText()
        if (text.isBlank()) return buildJsonObject { }
        return json.parseToJsonElement(text).jsonObject
    }

    private suspend fun raw(
        url: String,
        method: HttpMethod,
        body: JsonObject? = null,
        bytes: ByteArray? = null
    ): HttpResponse {
        var forceRefresh = false
        var attempt = 0
        while (true) {
            val response = send(url, method, body, auth.accessToken(forceRefresh), bytes)
            when {
                response.status.isSuccess() -> return response

                response.status == HttpStatusCode.Unauthorized && !forceRefresh -> {
                    forceRefresh = true
                }

                response.status == HttpStatusCode.TooManyRequests && attempt < MAX_THROTTLE_RETRIES -> {
                    attempt++
                    val waitSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 1L
                    delay(waitSeconds.coerceIn(1L, 30L) * 1_000L)
                }

                else -> throw OneDriveHttpException(response.status.value, response.bodyAsText())
            }
        }
    }

    private suspend fun send(
        url: String,
        method: HttpMethod,
        body: JsonObject?,
        token: String,
        bytes: ByteArray? = null
    ): HttpResponse = http.request(url) {
        this.method = method
        header(HttpHeaders.Authorization, "Bearer $token")
        when {
            bytes != null -> {
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }

            body != null -> {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
    }

    // ----- Parsing -----

    private fun parseItem(obj: JsonObject): RemoteItem? {
        val id = obj.string("id") ?: return null
        return RemoteItem(
            id = id,
            name = obj.string("name").orEmpty(),
            // Graph expresses the type through facet presence: having folder means a folder, having deleted means deleted.
            isFolder = obj["folder"] != null,
            eTag = obj.string("eTag"),
            modifiedAtMs = obj.string("lastModifiedDateTime")?.let(::parseIso).orZero(),
            sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            parentId = obj["parentReference"]?.jsonObject?.string("id"),
            deleted = obj["deleted"] != null
        )
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun parseIso(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private fun Long?.orZero(): Long = this ?: 0L

    private fun seg(value: String): String = value.encodeURLPathPart()

    private companion object {
        const val BASE = "https://graph.microsoft.com/v1.0"

        /** The safe ceiling for a Graph simple upload; beyond it an upload session is mandatory. */
        const val SIMPLE_UPLOAD_MAX_BYTES = 4 * 1024 * 1024

        /** Upload-session chunks must be a multiple of 320KiB (a hard Graph requirement). */
        const val UPLOAD_CHUNK_BYTES = 320 * 1024 * 10

        /**
         * Download ceiling for a single file. A generous finite value rather than "unlimited": normal downloads on a slow link
         * are no longer killed by the 60s limit, yet a stuck request cannot hold the sync lock indefinitely.
         */
        const val BULK_REQUEST_TIMEOUT_MS = 30 * 60 * 1000L

        const val MAX_THROTTLE_RETRIES = 3
    }
}
