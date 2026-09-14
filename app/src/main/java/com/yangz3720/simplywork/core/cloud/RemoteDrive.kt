package com.yangz3720.simplywork.core.cloud

/**
 * 云端条目 —— Graph `driveItem` 对本应用的最小投影。只保留镜像同步真正需要的东西：
 * `id`（主键）、`name`/`parentId`（推导逻辑路径）、`eTag`（变更判据）、
 * `modifiedAtMs`（展示与冲突提示）、`deleted`（增量删除标记）。
 *
 * 删除项只有 [id] 可靠：Graph 在 delta 里对已删除条目不保证返回 name/parent，
 * 所以删除必须靠本地清单把 `id` 反查回路径（见 [ProjectSyncEngine]）。
 */
data class RemoteItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val eTag: String?,
    val modifiedAtMs: Long,
    val sizeBytes: Long,
    val parentId: String? = null,
    val deleted: Boolean = false
)

/**
 * 一次 delta 查询的结果。
 *
 * [reset] = true 表示这是一次**全量枚举**（首次绑定、或游标过期 HTTP 410 后重建），
 * 此时 [items] 是云端该文件夹的完整现状，而不仅是变化量 —— 引擎据此把"清单里有、
 * 枚举里没有"的条目标记为云端已删除。增量为 false，未出现的条目不代表被删。
 */
data class RemoteDelta(
    val items: List<RemoteItem>,
    val deltaLink: String,
    val reset: Boolean
)

/** 云端 delta 游标失效（HTTP 410 Gone）时的显式信号，由引擎决定是否重建基线。 */
class DeltaCursorExpired(message: String) : Exception(message)

/**
 * 云端驱动器端口（OneDrive 实现见 `infrastructure/cloud/onedrive/OneDriveGraphClient`，
 * JVM 单测用内存 fake）。刻意保持窄：镜像同步只需要这几件事。
 *
 * 约定：所有方法在失败时抛异常（含 [DeltaCursorExpired]），由 [ProjectSyncEngine]
 * 决定重试或上报 —— 端口层不做静默降级（P2）。
 */
interface RemoteDrive {

    /** 取文件夹元数据；不是文件夹或不存在时抛异常。 */
    suspend fun folder(folderId: String): RemoteItem

    /** 在 [parentFolderId] 下列子项（文件夹选择器用，不参与增量同步）。 */
    suspend fun children(folderId: String): List<RemoteItem>

    /** 创建文件夹；同名已存在时由实现方决定返回既有项（Graph 用 fail 冲突策略）。 */
    suspend fun createFolder(parentFolderId: String, name: String): RemoteItem

    /**
     * 增量枚举 [folderId] 子树。[deltaLink] 为 null 走全量（[RemoteDelta.reset] = true）。
     * 游标过期时抛 [DeltaCursorExpired]，由调用方以 null 重试。
     */
    suspend fun delta(folderId: String, deltaLink: String?): RemoteDelta

    suspend fun download(itemId: String): ByteArray

    /** 新建或覆盖 [parentFolderId]/[fileName]（大小限制由实现方处理）。 */
    suspend fun upload(parentFolderId: String, fileName: String, bytes: ByteArray): RemoteItem

    suspend fun deleteItem(itemId: String)
}
