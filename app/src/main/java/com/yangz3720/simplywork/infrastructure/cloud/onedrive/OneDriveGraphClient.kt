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

/** 云端返回非 2xx。[status] 供调用方区分（410 走 [DeltaCursorExpired]，401 触发续期重试）。 */
class OneDriveHttpException(val status: Int, val detail: String) :
    RuntimeException("OneDrive 请求失败（HTTP $status）：${detail.take(300)}")

/** 响应结构不符合预期（缺 deltaLink、把文件当文件夹等）。显式失败，不静默猜。 */
class OneDriveProtocolException(message: String) : RuntimeException(message)

/**
 * Microsoft Graph 的 OneDrive 实现（v1.4）。
 *
 * 用 Ktor 直接打 REST，不引入 `msgraph-sdk-java`：本应用只需要 7 个端点，
 * 官方 Java SDK 会拖进 azure-identity + 一堆反射依赖，对端侧 APK 完全不成比例。
 * 解析用 kotlinx.serialization 手写（Graph 的 `folder`/`file`/`deleted` 是
 * "facate 存在即真"的语义，强类型反序列化反而别扭）。
 *
 * 只支持 `/me/drive`（个人账号自己的盘），不带 driveId —— 产品约定"绑自己的网盘"。
 */
class OneDriveGraphClient(
    private val auth: OneDriveAuth,
    httpClient: HttpClient? = null
) : RemoteDrive {

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = httpClient ?: HttpClient(OkHttp) {
        // 元数据调用的默认值：这些都是小 JSON，60s 绰绰有余。批量传输（下载）在各自的
        // 请求上覆盖，见 download()。
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
                // fail：同名已存在时显式报错，而不是把两个项目悄悄打进同一个云端目录。
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
                // 游标过期：Graph 要求按 Location 里的新 nextLink 从头全量重建。
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
            // 元数据的 60s 默认值对批量传输太紧：大文件在慢链路上超过它只是慢，不是故障，
            // 按 60s 判失败会让"拉取"在慢网络上必败。这里放宽整体上限，链路真的死掉
            // （长时间收不到任何字节）仍然由 60s 的 socket 超时兜住。
            timeout { requestTimeoutMillis = BULK_REQUEST_TIMEOUT_MS }
        }
        if (!response.status.isSuccess()) {
            throw OneDriveHttpException(response.status.value, response.bodyAsText())
        }
        // /content 是 302 到预授权地址，OkHttp 自动跟随；跳转到别的 host 时 OkHttp 会
        // 丢掉 Authorization 头，正好是我们要的（预授权 URL 不接受、也不需要它）。
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

    // ----- 大文件：上传会话（≥4MiB 必须走，简单上传会超时/被截断）-----

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
            // uploadUrl 是预授权地址：绝不带 Authorization 头。
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

    // ----- HTTP 管道 -----

    /**
     * 发一个 JSON 请求并解析。401（令牌刚过期）强制续期后重试一次；
     * 429 读 `Retry-After` 退避重试（Graph 的节流是常态，不是错误）。
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

    // ----- 解析 -----

    private fun parseItem(obj: JsonObject): RemoteItem? {
        val id = obj.string("id") ?: return null
        return RemoteItem(
            id = id,
            name = obj.string("name").orEmpty(),
            // Graph 用 facet 的存在性表达类型：有 folder 就是文件夹，有 deleted 就是已删除。
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

        /** Graph 简单上传的稳妥上限；超过就必须走上传会话。 */
        const val SIMPLE_UPLOAD_MAX_BYTES = 4 * 1024 * 1024

        /** 上传会话分片必须是 320KiB 的整数倍（Graph 硬要求）。 */
        const val UPLOAD_CHUNK_BYTES = 320 * 1024 * 10

        /**
         * 单个文件的下载上限。取一个宽松的有限值而不是"无限"：既让慢链路下的正常下载
         * 不再被 60s 误杀，又不会让一个卡住的请求把同步锁无限期占住。
         */
        const val BULK_REQUEST_TIMEOUT_MS = 30 * 60 * 1000L

        const val MAX_THROTTLE_RETRIES = 3
    }
}
