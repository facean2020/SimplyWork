package com.yangz3720.simplywork.core.cloud

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** 已连接的 OneDrive 账号（展示用；access token 永不出认证层）。 */
data class OneDriveAccount(val id: String, val username: String)

/** 云端文件夹选择器的一项。[path] 是给用户看的层级路径（`工作/项目A`）。 */
data class CloudFolder(val id: String, val name: String, val path: String)

/**
 * 同步的瞬时状态，UI 只用来显示一行文字。刻意不做进度百分比：镜像同步是
 * "要么完成要么报错"的短事务，假进度只会骗人。
 */
sealed interface SyncState {
    data object Idle : SyncState

    data class Working(val message: String) : SyncState

    /** 已完成，[message] 是"上传 2 · 下载 1"这类摘要。 */
    data class Info(val message: String) : SyncState

    data class Error(val message: String) : SyncState
}

/**
 * UI 面向的 OneDrive 接缝（v1.4，与 `AgentRuntimeOwner` 同构：VM 只依赖这个接口，
 * JVM 测试用 fake，实现见 `infrastructure/cloud/onedrive/OneDriveService`）。
 *
 * 同步语义（用户定调）：**双向镜像 + 懒惰**。绑定即首轮对齐，方向由本地项目有没有
 * 文件决定：项目是空的 → 拉取云端；项目里有文件 → 上传（两边都有内容则拒绝绑定）。
 * 此后只在两个时机动作：一轮 agent run 结束后推送、进入项目时拉取（带节流）。
 * 没有轮询、没有后台任务、没有 Webhook。
 */
interface OneDriveOwner {

    val account: StateFlow<OneDriveAccount?>

    val syncState: StateFlow<SyncState>

    /** gradle.properties 是否已填 msalClientId。未配置时 UI 给提示而不是空白失败。 */
    val configured: Boolean

    /** 冷启动恢复已登录账号（幂等）。 */
    suspend fun restore()

    /** 交互式登录（需要 Activity 拉起授权页）。 */
    suspend fun connect(activity: Activity): Result<Unit>

    /** 登出。只清账号，**不动**任何项目绑定与本地文件。 */
    suspend fun disconnect(): Result<Unit>

    /** 列出 [parentId] 下的子文件夹；`parentId = "root"` 表示网盘根。 */
    suspend fun listFolders(parentId: String, parentPath: String): Result<List<CloudFolder>>

    /** 绑定到云端文件夹并完成首轮对齐；方向由本地项目有没有文件决定。 */
    suspend fun bindFolder(projectId: String, folder: CloudFolder): Result<SyncReport>

    /** 在 [parentId] 下新建文件夹并绑定；新建的目录必然是空的，方向同样由本地决定。 */
    suspend fun createAndBindFolder(
        projectId: String,
        parentId: String,
        parentPath: String,
        name: String
    ): Result<SyncReport>

    /** 解除绑定：丢掉同步基线，本地与云端各自保留。 */
    suspend fun unbind(projectId: String): Result<Unit>

    /**
     * 拉取 + 推送。[throttled] = true 时距上次拉取不足间隔直接跳过并返回 null
     * （进入项目/切项目的自动同步走这条），用户显式点按则传 false 强制跑。
     */
    suspend fun sync(projectId: String, throttled: Boolean): Result<SyncReport?>

    /** 一轮 agent run 结束后的懒惰推送（只推不拉）。未绑定或未登录时返回 null。 */
    suspend fun pushAfterRun(projectId: String): Result<SyncReport?>
}
