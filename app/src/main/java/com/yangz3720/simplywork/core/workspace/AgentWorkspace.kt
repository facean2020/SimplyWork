package com.yangz3720.simplywork.core.workspace

import java.io.File

/**
 * Agent-internal sandbox (v1.3): models the PC's "operable directory". One separate directory per
 * project (`<root>/<projectId>/…`), and every `fs_*` tool of the agent lands here -- matching the
 * `appcontainer → \Project1 \Project2` semantics (user-mandated).
 *
 * Pure Kotlin + injected [root] → testable on the JVM. Path safety is a hard constraint:
 * 1. projectId must match [ProjectIdRules] -- `.` / `..` / path separators are never allowed;
 * 2. after canonicalization the relative path must still land inside the project directory -- `../` escapes, absolute paths and
 *    symlink hops are all explicitly refused (P2), never silently rewritten somewhere else.
 *
 * This is an **app-private directory** (under `context.filesDir`), so no Android permission is needed;
 * an app operating on itself is naturally allowed, and the only boundary is not overstepping its own sandbox (exactly what this class is for).
 */
class AgentWorkspace(private val root: File) {

    /** Per-file limit: the read side truncates, the write side refuses. The context budget is 8k chars, so 256KB is already far beyond what is needed. */
    var maxFileBytes: Int = 256 * 1024

    /** Per-listing entry cap (stops a huge directory being fed to the model as one full table). */
    var maxListEntries: Int = 500

    /** Root directory of each project (created automatically). */
    fun projectDir(projectId: String): File {
        require(ProjectIdRegex.matches(projectId)) { "invalid project id: '$projectId'" }
        return File(root, projectId).apply { mkdirs() }
    }

    /**
     * Deletes the whole project directory (the UI takes this path to clear the sandbox when a project is deleted).
     *
     * Deliberately does not go through [projectDir]: that method calls `mkdirs()` first, which amounts to creating a
     * nonexistent directory just so it can be deleted.
     *
     * @return whether the directory is really gone after the call -- also true when it never existed (the "it is deleted" semantics,
     * with no distinction of "was there anything to delete": callers only care that it is not there now).
     */
    fun deleteProject(projectId: String): Boolean {
        require(ProjectIdRegex.matches(projectId)) { "invalid project id: '$projectId'" }
        return File(root, projectId).deleteRecursively()
    }

    // ----- Queries -----

    data class Entry(val name: String, val isDirectory: Boolean, val sizeBytes: Long, val modifiedAtMs: Long)

    data class Listing(val path: String, val entries: List<Entry>, val truncated: Boolean)

    fun list(projectId: String, relativePath: String = ""): Listing {
        val dir = resolve(projectId, relativePath, what = "a directory") { it.isDirectory }
        val files = dir.listFiles()
            ?: throw WorkspaceException("cannot list '${relativePath.ifEmpty { "." }}': not a readable directory")
        val sorted = files.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        val entries = sorted.take(maxListEntries).map {
            Entry(name = it.name, isDirectory = it.isDirectory, sizeBytes = if (it.isDirectory) 0 else it.length(), modifiedAtMs = it.lastModified())
        }
        return Listing(path = relativePath.ifEmpty { "." }, entries = entries, truncated = files.size > maxListEntries)
    }

    data class FileContent(val path: String, val content: String, val sizeBytes: Long, val truncated: Boolean)

    fun read(projectId: String, relativePath: String): FileContent {
        val file = resolve(projectId, relativePath, what = "a regular file") { it.isFile }
        val raw = file.readBytes()
        // Extra bytes are dropped but must be declared: silent truncation would make the model believe it read everything (P2).
        val bytes = if (raw.size > maxFileBytes) raw.copyOf(maxFileBytes) else raw
        return FileContent(
            path = relativePath,
            content = bytes.decodeToString(),
            sizeBytes = raw.size.toLong(),
            truncated = raw.size > maxFileBytes
        )
    }

    /**
     * Reads text, returns `null` on failure (used for the **baseline** read of the v1.5 change log): "what the file was" before a
     * write, and a missing file is a perfectly normal case that must not throw. Truncation beyond [maxFileBytes] is reported
     * faithfully by [FileContent.truncated], and the caller decides for itself whether that baseline can be trusted.
     */
    fun readOrNull(projectId: String, relativePath: String): FileContent? =
        runCatching { read(projectId, relativePath) }.getOrNull()

    // ----- Mutations (SAFE level: writes/mkdir are free; move/delete are escalated to USER_VISIBLE/CONFIRMING by the tool layer) -----

    data class WriteResult(val path: String, val bytesWritten: Long, val totalBytes: Long, val created: Boolean)

    /**
     * Binary read (used by v1.4 cloud mirror sync). Shares the same path-safety checks as [read] but does **not** impose
     * the [maxFileBytes] limit -- that limit protects the model's context budget, not the disk. A mirror must be byte-faithful;
     * truncating or decoding it as text would silently corrupt files.
     */
    fun readBytes(projectId: String, relativePath: String): ByteArray =
        resolve(projectId, relativePath, what = "a regular file") { it.isFile }.readBytes()

    /** Binary write; same semantics as [write] but with no size cap (same reasoning as [readBytes]). */
    fun writeBytes(projectId: String, relativePath: String, bytes: ByteArray): WriteResult {
        val file = resolveForWrite(projectId, relativePath)
        if (file.isDirectory) {
            throw WorkspaceException("'$relativePath' is a directory, not a file")
        }
        val existed = file.exists()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return WriteResult(path = relativePath, bytesWritten = bytes.size.toLong(), totalBytes = file.length(), created = !existed)
    }

    /**
     * Resolves a **write target** to a real [File], for callers that cannot hand their bytes to
     * [write]/[writeBytes] -- the TTS engine wants a file descriptor to write into, not an array.
     *
     * Same path safety as the internal writers (relative path, must stay inside the project; absolute
     * paths and `../` escapes are refused, [resolveForWrite]); parent directories are created
     * (mkdir -p). The caller is the one that fills the file, so it also owns the size cap that [write]
     * would otherwise have applied.
     */
    fun fileForWrite(projectId: String, relativePath: String): File {
        val file = resolveForWrite(projectId, relativePath)
        if (file.isDirectory) {
            throw WorkspaceException("'$relativePath' is a directory, not a file")
        }
        file.parentFile?.mkdirs()
        return file
    }

    /**
     * Resolves an **existing** file to a real [File], for callers that cannot work off the bytes alone --
     * the v1.7 external preview hands a file to `FileProvider`, which insists on a File.
     *
     * Same path safety as [read] (`resolve`): relative path, still inside the project after canonicalization,
     * and it must actually be a regular file. This is a **leak of the sandbox's absolute location to our own
     * process only** -- it never reaches the model, and every tool still refuses absolute paths.
     */
    fun fileForRead(projectId: String, relativePath: String): File =
        resolve(projectId, relativePath, what = "a regular file") { it.isFile }

    /** Writes a file; parent directories are created automatically (mkdir -p semantics); [append] defaults to overwrite. */
    fun write(projectId: String, relativePath: String, content: String, append: Boolean = false): WriteResult {
        require(content.encodeToByteArray().size <= maxFileBytes) {
            "content exceeds the ${maxFileBytes}-byte per-file limit (${content.encodeToByteArray().size} bytes); split it into multiple writes"
        }
        val file = resolveForWrite(projectId, relativePath)
        if (file.isDirectory) {
            throw WorkspaceException("'${relativePath}' is a directory, not a file")
        }
        val existed = file.exists()
        file.parentFile?.mkdirs()
        if (append) {
            file.appendText(content)
        } else {
            file.writeText(content)
        }
        return WriteResult(
            path = relativePath,
            bytesWritten = content.encodeToByteArray().size.toLong(),
            totalBytes = file.length(),
            created = !existed
        )
    }

    data class MkdirResult(val path: String, val created: Boolean)

    fun mkdir(projectId: String, relativePath: String): MkdirResult {
        if (relativePath.isBlank()) {
            throw WorkspaceException("directory path must not be empty (the project root always exists)")
        }
        val dir = resolve(projectId, relativePath, what = "present") { true }
        if (dir.isFile) {
            throw WorkspaceException("'${relativePath}' exists and is a file, not a directory")
        }
        val created = !dir.exists()
        dir.mkdirs()
        return MkdirResult(path = relativePath, created = created)
    }

    data class MoveResult(val from: String, val to: String)

    /** Refuses explicitly when the target already exists (no silent overwrite -- PC `mv -n` semantics, P2). */
    fun move(projectId: String, fromPath: String, toPath: String): MoveResult {
        val from = resolve(projectId, fromPath, what = "present") { it.exists() }
        val to = resolveForWrite(projectId, toPath)
        if (to.exists()) {
            throw WorkspaceException("'$toPath' already exists; move refused (no silent overwrite)")
        }
        if (!from.renameTo(to)) {
            throw WorkspaceException("rename failed for '$fromPath' → '$toPath' (target may be open or cross-device)")
        }
        return MoveResult(from = fromPath, to = toPath)
    }

    data class DeleteResult(val path: String, val deletedEntries: Int)

    /**
     * Deletes a file or directory. The project root itself cannot be deleted (deleting it would be deregistering the project's sandbox,
     * which simply does not exist in the tool semantics); a directory requires an explicit `recursive=true`, otherwise only an empty one is allowed.
     */
    fun delete(projectId: String, relativePath: String, recursive: Boolean = false): DeleteResult {
        if (relativePath.isBlank()) {
            throw WorkspaceException("refusing to delete the project root: files inside it only")
        }
        val target = resolve(projectId, relativePath, what = "present") { it.exists() }
        if (target == projectDir(projectId).canonicalFile) {
            throw WorkspaceException("the project root cannot be deleted")
        }
        var count = 0
        fun deleteRecursively(file: File): Boolean {
            if (file.isDirectory) {
                file.listFiles()?.forEach { if (!deleteRecursively(it)) return false }
            }
            val removed = file.delete()
            if (removed) count++
            return removed
        }
        if (target.isDirectory) {
            val childCount = target.listFiles()?.size ?: 0
            if (childCount > 0 && !recursive) {
                throw WorkspaceException(
                    "'$relativePath' is a non-empty directory; pass recursive=true to delete it (this is irreversible)"
                )
            }
            if (!deleteRecursively(target)) {
                throw WorkspaceException("deletion failed partway through '$relativePath'")
            }
        } else {
            if (!target.delete()) {
                throw WorkspaceException("failed to delete '$relativePath'")
            }
            count = 1
        }
        return DeleteResult(path = relativePath, deletedEntries = count)
    }

    // ----- Path safety -----

    /**
     * Resolves [relativePath] and checks that it still lands inside the project directory. Canonicalization dissolves both `..` and symbolic
     * links; an escape → [WorkspaceException] (explicit refusal, never rewritten outside the sandbox).
     * [what] is the readable semantics used when [predicate] fails ("a directory" / "a regular file" / "present").
     */
    private fun resolve(
        projectId: String,
        relativePath: String,
        what: String,
        predicate: (File) -> Boolean
    ): File {
        val base = projectDir(projectId).canonicalFile
        require(!relativePath.startsWith("/")) { "paths inside the workspace are relative; got an absolute path: '$relativePath'" }
        val target = if (relativePath.isBlank()) base else File(base, relativePath).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw WorkspaceException("path escapes the project workspace: '$relativePath'")
        }
        check(predicate(target)) { "'$relativePath' is not $what" }
        return target
    }

    /** Write/move target: may not exist, but after resolution it must still be inside the sandbox (verified before mkdirs). */
    private fun resolveForWrite(projectId: String, relativePath: String): File {
        require(relativePath.isNotBlank()) { "path must not be empty" }
        require(!relativePath.startsWith("/")) { "paths inside the workspace are relative; got an absolute path: '$relativePath'" }
        // base must be canonical: otherwise macOS's /var → /private/var symlink makes every legitimate
        // relative path look like it "escapes" (target is canonical, base is not).
        val base = projectDir(projectId).canonicalFile
        val target = File(base, relativePath).canonicalFile
        if (!target.path.startsWith(base.path + File.separator)) {
            throw WorkspaceException("path escapes the project workspace: '$relativePath'")
        }
        return target
    }

    companion object {
        val ProjectIdRegex = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

/** Normalized sandbox operation failure: the tool layer turns it into a JSON error object so the model can self-correct (P2). */
class WorkspaceException(message: String) : Exception(message)