package com.yangz3720.simplywork.core.workspace

import java.io.File

/**
 * Agent 内部沙箱（v1.3）：模拟 PC 的"可操作目录"。每个项目一个独立目录
 * （`<root>/<projectId>/…`），agent 的 `fs_*` 工具全部落在这里 —— 对应
 * `appcontainer → \项目1 \项目2` 的语义（用户定调）。
 *
 * 纯 Kotlin + [root] 注入 → JVM 可测。路径安全是硬约束：
 * 1. projectId 必须匹配 [ProjectIdRules]，绝不允许 `.` / `..` / 路径分隔符；
 * 2. 相对路径 canonical 化后必须仍落在项目目录内 —— `../` 逃逸、绝对路径、
 *    符号链接跳出一律显式拒绝（P2），绝不静默改写到别处。
 *
 * 这是**应用私有目录**（`context.filesDir` 下），不需要任何 Android 权限；
 * App 操作自己是天然允许的，边界只在不要越过自己的沙盒（这正是本类的职责）。
 */
class AgentWorkspace(private val root: File) {

    /** 单文件上限：读侧截断、写侧拒绝。上下文预算 8k 字符，256KB 已经远超所需。 */
    var maxFileBytes: Int = 256 * 1024

    /** 单次列目录上限（防把巨型目录整表喂给模型）。 */
    var maxListEntries: Int = 500

    /** 每项目的根目录（自动创建）。 */
    fun projectDir(projectId: String): File {
        require(ProjectIdRegex.matches(projectId)) { "invalid project id: '$projectId'" }
        return File(root, projectId).apply { mkdirs() }
    }

    // ----- 查询 -----

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
        // 多出来的字节丢弃但必须声明：静默截断会让模型以为读全了（P2）。
        val bytes = if (raw.size > maxFileBytes) raw.copyOf(maxFileBytes) else raw
        return FileContent(
            path = relativePath,
            content = bytes.decodeToString(),
            sizeBytes = raw.size.toLong(),
            truncated = raw.size > maxFileBytes
        )
    }

    // ----- 变更（SAFE 级：写/建目录自由；移动/删除由工具层升级为 USER_VISIBLE/CONFIRMING）-----

    data class WriteResult(val path: String, val bytesWritten: Long, val totalBytes: Long, val created: Boolean)

    /** 写文件；父目录自动创建（mkdir -p 语义），[append] 缺省为覆盖。 */
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

    /** 目标已存在时显式拒绝（不静默覆盖 —— PC mv 的 -n 语义，P2）。 */
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
     * 删除文件或目录。项目根目录本身不可删（删掉等于注销项目的沙箱，工具语义里
     * 项目删除根本不存在）；目录必须显式 `recursive=true`，否则只允许空目录。
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

    // ----- 路径安全 -----

    /**
     * 解析并检查 [relativePath] 仍落在项目目录内。canonical 化同时消解 `..` 与符号
     * 链接；逃逸 → [WorkspaceException]（显式拒绝，绝不改写到沙箱外）。
     * [what] 是 [predicate] 失败时的可读语义（"a directory" / "a regular file" / "present"）。
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

    /** 写/移动目标：允许不存在，但解析后必须仍在沙箱内（mkdirs 前先验）。 */
    private fun resolveForWrite(projectId: String, relativePath: String): File {
        require(relativePath.isNotBlank()) { "path must not be empty" }
        require(!relativePath.startsWith("/")) { "paths inside the workspace are relative; got an absolute path: '$relativePath'" }
        // base 必须 canonical：否则 macOS 的 /var → /private/var 符号链接会让每个合法
        // 相对路径都"逃逸"（target 是 canonical 的，base 不是）。
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

/** 归一化的沙箱操作失败：工具层转 JSON 错误对象，模型可自纠（P2）。 */
class WorkspaceException(message: String) : Exception(message)