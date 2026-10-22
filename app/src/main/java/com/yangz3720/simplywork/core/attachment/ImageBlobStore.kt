package com.yangz3720.simplywork.core.attachment

import java.io.File

/**
 * Binary image blobs, the third persistence medium next to SQLite (DAG) and JSONL (payload text).
 *
 * Image bytes are raw WebP files keyed by id — never base64, never inside the JSONL. The session
 * payload only carries an [com.yangz3720.simplywork.infrastructure.model.model.Attachment.Image]
 * reference; bytes are read back and base64-encoded only when a model request is formatted.
 * That keeps JSONL small and parseable and stops every payload decode from pulling megabytes of
 * base64 into memory.
 *
 * Pure java.io — JVM unit tests run this against a temp directory.
 */
interface ImageBlobStore {

    /** Writes [bytes] under [id]. Overwrites silently (ids are caller-generated UUIDs). */
    fun save(id: String, bytes: ByteArray)

    /** Raw bytes for [id], or null when absent (P2: the request formatter reports the gap). */
    fun read(id: String): ByteArray?

    /** Removes one blob. Absent id is a no-op. */
    fun delete(id: String)
}

/**
 * [ImageBlobStore] as one file per blob at `<baseDir>/<id>.webp`.
 *
 * Write-then-reference: [save] happens at upload time, before the session payload that names the
 * id is appended. A crash in between orphans a file (harmless, GC-able); the reverse order would
 * leave a payload pointing at bytes that never landed.
 */
class FileImageBlobStore(private val baseDir: File) : ImageBlobStore {

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    override fun save(id: String, bytes: ByteArray) {
        fileFor(id).writeBytes(bytes)
    }

    override fun read(id: String): ByteArray? {
        val file = fileFor(id)
        return if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
    }

    override fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun fileFor(id: String): File = File(baseDir, "$id$EXTENSION")

    private companion object {
        const val EXTENSION = ".webp"
    }
}

/** In-memory [ImageBlobStore] for JVM tests that do not need real files. */
class InMemoryImageBlobStore : ImageBlobStore {

    private val blobs = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    override fun save(id: String, bytes: ByteArray) {
        blobs[id] = bytes
    }

    override fun read(id: String): ByteArray? = blobs[id]

    override fun delete(id: String) {
        blobs.remove(id)
    }
}
