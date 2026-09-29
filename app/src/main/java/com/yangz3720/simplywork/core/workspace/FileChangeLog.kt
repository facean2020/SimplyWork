package com.yangz3720.simplywork.core.workspace

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Read/write seam of the change log (v1.5). The write side is the fs_* tools (they record an entry when the agent changes a file), the read side is the session
 * projection (which sticks the change back into the conversation flow) -- both sides share one source, so "what you see" necessarily equals "what was changed".
 */
interface FileChangeLog {

    suspend fun record(change: FileChange)

    /** Changes of one session, ascending by time (old → new). Returns an empty list when there is no record. */
    suspend fun changes(sessionId: String): List<FileChange>

    /** Deletes all change records of a session (when the session is deleted). No record is a no-op. */
    suspend fun delete(sessionId: String)
}

/** JVM-test implementation (no filesystem dependency). */
class InMemoryFileChangeLog : FileChangeLog {

    private val bySession = LinkedHashMap<String, MutableList<FileChange>>()

    override suspend fun record(change: FileChange) {
        bySession.getOrPut(change.sessionId) { mutableListOf() } += change
    }

    override suspend fun changes(sessionId: String): List<FileChange> =
        bySession[sessionId].orEmpty().toList()

    override suspend fun delete(sessionId: String) {
        bySession.remove(sessionId)
    }
}

/**
 * One JSON file per session: `<baseDir>/<sessionId>.json`, whose content is a [FileChange] array.
 *
 * Separate from the session DAG store: the "objective content" of a change (the diff lines) does not enter the model context, so it should not be stuffed into the payload
 * store (that is the raw record fed to the LLM). The [maxPerSession] cap stops unbounded growth per session -- all that is dropped is
 * the oldest card, and no real file is affected.
 */
class JsonFileChangeLog(
    private val baseDir: File,
    private val maxPerSession: Int = 200
) : FileChangeLog {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FileChange.serializer())

    /** Session files are read-modify-write and must be serialized (several tool calls in one run persist concurrently). */
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

    override suspend fun delete(sessionId: String) {
        mutex.withLock { fileFor(sessionId).delete() }
    }

    private fun read(sessionId: String): List<FileChange> {
        val file = fileFor(sessionId)
        if (!file.isFile) return emptyList()
        // A corrupt record counts as none: change cards are a display artifact, not worth breaking the session projection over (the reverse of P2).
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull().orEmpty()
    }

    private fun write(sessionId: String, changes: List<FileChange>) {
        val file = fileFor(sessionId)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, changes))
    }

    /** The session id goes into the file name → `../`-style constructions must be blocked (this path is produced by the App itself, still validated explicitly). */
    private fun fileFor(sessionId: String): File {
        require(SESSION_ID_REGEX.matches(sessionId)) { "invalid session id for change log: '$sessionId'" }
        return File(baseDir, "$sessionId.json")
    }

    private companion object {
        val SESSION_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
