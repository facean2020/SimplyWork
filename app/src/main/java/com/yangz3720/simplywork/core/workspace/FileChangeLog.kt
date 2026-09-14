package com.yangz3720.simplywork.core.workspace

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 改动记录的读写缝（v1.5）。写侧是 fs_* 工具（agent 改文件时落一笔），读侧是会话投影
 * （把改动贴回对话流）——两侧同源，所以"看到什么"必然等于"改了什么"。
 */
interface FileChangeLog {

    suspend fun record(change: FileChange)

    /** 某会话的改动，按时间升序（旧 → 新）。没有记录返回空表。 */
    suspend fun changes(sessionId: String): List<FileChange>
}

/** JVM 测试用实现（无文件系统依赖）。 */
class InMemoryFileChangeLog : FileChangeLog {

    private val bySession = LinkedHashMap<String, MutableList<FileChange>>()

    override suspend fun record(change: FileChange) {
        bySession.getOrPut(change.sessionId) { mutableListOf() } += change
    }

    override suspend fun changes(sessionId: String): List<FileChange> =
        bySession[sessionId].orEmpty().toList()
}

/**
 * 每会话一个 JSON 文件：`<baseDir>/<sessionId>.json`，内容是 [FileChange] 数组。
 *
 * 独立于会话 DAG 存储：改动的"客观内容"（差异行）不进模型上下文，也就不该塞进 payload
 * store（那是喂给 LLM 的原始记录）。上限 [maxPerSession] 防单会话无界增长——丢的只会是
 * 最旧的卡片，不影响任何真实文件。
 */
class JsonFileChangeLog(
    private val baseDir: File,
    private val maxPerSession: Int = 200
) : FileChangeLog {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FileChange.serializer())

    /** 会话文件是"读-改-写"，必须串行（同一 run 内多个工具调用会并发落盘）。 */
    private val mutex = Mutex()

    override suspend fun record(change: FileChange) {
        mutex.withLock {
            val existing = read(change.sessionId)
            write(change.sessionId, (existing + change).takeLast(maxPerSession))
        }
    }

    override suspend fun changes(sessionId: String): List<FileChange> = mutex.withLock {
        read(sessionId)
    }

    private fun read(sessionId: String): List<FileChange> {
        val file = fileFor(sessionId)
        if (!file.isFile) return emptyList()
        // 损坏的记录等同没有：改动卡片是展示物，不值得为它中断会话投影（P2 反面）。
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull().orEmpty()
    }

    private fun write(sessionId: String, changes: List<FileChange>) {
        val file = fileFor(sessionId)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, changes))
    }

    /** 会话 id 进文件名 → 必须挡住 `../` 之类的构造（这条路径由 App 自己产生，仍显式校验）。 */
    private fun fileFor(sessionId: String): File {
        require(SESSION_ID_REGEX.matches(sessionId)) { "invalid session id for change log: '$sessionId'" }
        return File(baseDir, "$sessionId.json")
    }

    private companion object {
        val SESSION_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
