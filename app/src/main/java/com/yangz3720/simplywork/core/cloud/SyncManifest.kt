package com.yangz3720.simplywork.core.cloud

import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 上次同步时的单文件基线。判断"本地是否改过"不再依赖时间戳跨时钟比较，
 * 而是拿本地文件当前的 `size + mtime` 与这里记录的值比：
 * 不同 = 本地动过。这是 rsync 的轻量变体，避免引入全量哈希。
 */
@Serializable
data class SyncEntry(
    val itemId: String,
    val eTag: String,
    val localModifiedAtMs: Long,
    val localSizeBytes: Long
)

/**
 * 一个项目 ↔ 一个云端文件夹的同步清单。存在**沙箱之外**（`agent_workspace/sync/`），
 * 否则清单自己会被同步上去、并污染增量判断。
 *
 * [folders] 的 key 是相对路径（项目根 = 空串），value 是 driveItem id；
 * 它承担两件事：把云端条目解析成逻辑路径、以及记住新建目录的 id 以便上传子文件。
 */
@Serializable
data class SyncManifest(
    val version: Int = 1,
    /** 绑定的云端文件夹 id（= [folders] 的空串项，冗余存一份便于自校验）。 */
    val folderId: String,
    val deltaLink: String? = null,
    val files: Map<String, SyncEntry> = emptyMap(),
    val folders: Map<String, String> = emptyMap()
)

/**
 * 清单的 JSON 文件存储（每项目一个文件）。用 [AgentWorkspace.ProjectIdRegex] 校验
 * 项目 id 后才拼路径 —— 与沙箱同一套路径安全约束，绝不允许 `../` 逃逸。
 */
class SyncManifestStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(projectId: String): File {
        require(AgentWorkspace.ProjectIdRegex.matches(projectId)) { "invalid project id: '$projectId'" }
        return File(dir, "$projectId.json")
    }

    /** 读不到/损坏都返回 null：调用方按"尚未建立基线"处理并全量重建（P2 不静默）。 */
    fun load(projectId: String): SyncManifest? {
        val file = file(projectId)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(SyncManifest.serializer(), file.readText()) }.getOrNull()
    }

    fun save(projectId: String, manifest: SyncManifest) {
        dir.mkdirs()
        // 先写临时文件再原子重命名：同步中途被杀不会留下半截 JSON。
        val tmp = File(dir, "${projectId}.json.tmp")
        tmp.writeText(json.encodeToString(SyncManifest.serializer(), manifest))
        if (!tmp.renameTo(file(projectId))) {
            file(projectId).writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete(projectId: String) {
        file(projectId).delete()
    }
}
