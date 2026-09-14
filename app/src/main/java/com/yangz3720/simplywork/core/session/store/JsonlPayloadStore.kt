package com.yangz3720.simplywork.core.session.store

import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap


import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSONL-backed [PayloadStore]: the payload half of the dual-medium persistence
 * (agent-core-design.md §2.1). One append-only file per session at
 * `<baseDir>/<sessionId>.jsonl`, one compact JSON line per record:
 *
 * ```
 * {"id":"<nodeId>","nodeType":"ToolCall","payload":{...}}
 * ```
 *
 * kotlinx.serialization escapes newlines inside strings, so a record never spans lines.
 *
 * Write path (crash-consistency, §2.2): the JSONL line is appended + fsynced BEFORE the
 * node row is committed in SQLite. A crash in between leaves an orphan line, which is
 * harmless — reads are keyed by SQLite-referenced node ids, so orphans are unreachable.
 *
 * Pure java.io — JVM unit tests run this directly against a temp directory.
 */
class JsonlPayloadStore(private val baseDir: File) : PayloadStore {

    /** nodeId -> (offset, length) per session, built by scanning the file on first touch. */
    private val indexes = ConcurrentHashMap<String, ConcurrentHashMap<String, PayloadLocation>>()

    /** Serializes appends to the same session file (append is a blocking call). */
    private val appendLock = Any()

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    override fun append(
        sessionId: String,
        nodeId: String,
        nodeType: String,
        payloadJson: String
    ): PayloadLocation {
        val file = fileFor(sessionId)
        val line = buildJsonObject {
            put("id", nodeId)
            put("nodeType", nodeType)
            put("payload", Json.parseToJsonElement(payloadJson))
        }.toString() + "\n"
        return try {
            val location = synchronized(appendLock) {
                RandomAccessFile(file, "rw").use { raf ->
                    val offset = file.length()
                    raf.seek(offset)
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    raf.write(bytes)
                    raf.fd.sync()
                    PayloadLocation(offset = offset, length = bytes.size - 1) // exclude trailing newline
                }.also { location ->
                    // The in-memory index must track appends immediately — a read that
                    // happens before the next full scan would otherwise miss this node.
                    indexFor(sessionId)?.put(nodeId, location)
                }
            }
            location
        } catch (e: Exception) {
            throw InfrastructureException.Session.StoreIo("Failed appending payload for node $nodeId", e)
        }
    }

    override fun read(sessionId: String, nodeId: String): String? {
        val location = indexFor(sessionId)?.get(nodeId) ?: return null
        return try {
            val line = RandomAccessFile(fileFor(sessionId), "r").use { raf ->
                raf.seek(location.offset)
                val bytes = ByteArray(location.length)
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
            // Return the inner payload (not the id/nodeType envelope) — callers decode
            // the payload object directly (agent-core-design.md §2.1).
            parseLine(line.trim())?.second
        } catch (e: Exception) {
            AgentLogger.w(TAG, e) { "Failed reading payload for node $nodeId; treating as missing" }
            null
        }
    }

    /** Loads the whole file and returns all payloads keyed by node id (startup warm-up). */
    fun loadAll(sessionId: String): Map<String, String> {
        val file = fileFor(sessionId)
        if (!file.exists()) return emptyMap()
        val payloads = LinkedHashMap<String, String>()
        file.useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                parseLine(trimmed)?.let { (id, payload) -> payloads[id] = payload }
            }
        }
        return payloads
    }

    override fun delete(sessionId: String) {
        // 索引一起丢：留着旧索引会让"删完再建同名会话"读到已删文件的偏移量。
        indexes.remove(sessionId)
        fileFor(sessionId).delete()
    }

    // ----- internals -----

    private fun fileFor(sessionId: String): File = File(baseDir, "$sessionId.jsonl")

    private fun indexFor(sessionId: String): ConcurrentHashMap<String, PayloadLocation>? =
        indexes.computeIfAbsent(sessionId) { scanIndex(fileFor(sessionId)) }

    private fun scanIndex(file: File): ConcurrentHashMap<String, PayloadLocation> {
        val map = ConcurrentHashMap<String, PayloadLocation>()
        if (!file.exists()) return map
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            file.useLines { lines ->
                lines.forEach { line ->
                    val byteLength = line.toByteArray(Charsets.UTF_8).size
                    if (line.isNotBlank()) {
                        parseLine(line.trim())?.let { (id, _) ->
                            map[id] = PayloadLocation(offset = offset, length = byteLength)
                        }
                    }
                    offset += byteLength + 1 // +1 for the newline
                }
            }
        }
        return map
    }

    /** Parses one line into (id, payloadJson) or null when malformed. */
    private fun parseLine(line: String): Pair<String, String>? = runCatching {
        val element = Json.parseToJsonElement(line).jsonObject
        val id = element["id"]?.jsonPrimitive?.content ?: return null
        val payload = element["payload"] ?: return null
        id to payload.toString()
    }.getOrNull()

    private companion object {
        const val TAG = "JsonlPayloadStore"
    }
}
