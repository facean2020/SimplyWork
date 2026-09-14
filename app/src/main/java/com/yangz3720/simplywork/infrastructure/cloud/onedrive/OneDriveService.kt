package com.yangz3720.simplywork.infrastructure.cloud.onedrive

import android.app.Activity
import android.content.Context
import com.yangz3720.simplywork.core.cloud.CloudFolder
import com.yangz3720.simplywork.core.cloud.OneDriveOwner
import com.yangz3720.simplywork.core.cloud.ProjectSyncEngine
import com.yangz3720.simplywork.core.cloud.RemoteDrive
import com.yangz3720.simplywork.core.cloud.SyncManifestStore
import com.yangz3720.simplywork.core.cloud.SyncReport
import com.yangz3720.simplywork.core.cloud.SyncState
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [OneDriveOwner] 的实现（v1.4）：认证（MSAL）+ Graph + 镜像引擎 + 惰性调度策略。
 *
 * **惰性策略**（用户定调"双向镜像但尽量懒惰、耗电低"）：
 * - 绑定/新建绑定时必然同步一次（首次校准）。
 * - 一轮 agent run 结束后 `pushAfterRun`：只推不拉；无本地改动时零网络请求。
 * - 进入/切换项目时 `sync(throttled = true)`：距上次拉取不足 [pullIntervalMs] 直接跳过。
 * - 没有轮询、没有后台任务、没有 Webhook 订阅（Webhook 要公网回调，端侧不成立）。
 *
 * 全部同步操作串行化在一个 [Mutex] 上：同一时刻只可能有一个项目在跑镜像，
 * 既避免并发写同一份 manifest，也避免并发把令牌刷爆。
 */
class OneDriveService(
    context: Context,
    private val projects: ProjectStore,
    workspace: AgentWorkspace,
    private val pullIntervalMs: Long = DEFAULT_PULL_INTERVAL_MS
) : OneDriveOwner {

    private val appContext: Context = context.applicationContext
    private val auth = OneDriveAuth(appContext)

    private val engine = ProjectSyncEngine(
        workspace = workspace,
        manifests = SyncManifestStore(File(appContext.filesDir, "agent_workspace/sync"))
    )

    /** OkHttp 引擎较重，按需建一次即常驻；应用生命周期内不再重建。 */
    @Volatile
    private var client: OneDriveGraphClient? = null

    private val lock = Mutex()
    private var lastPullAtMs = 0L

    override val account = auth.account

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    override val syncState = _syncState.asStateFlow()

    override val configured: Boolean get() = auth.isConfigured()

    // ----- 账号 -----

    override suspend fun restore() {
        auth.restore()
    }

    override suspend fun connect(activity: Activity): Result<Unit> =
        auth.signIn(activity).map { }

    override suspend fun disconnect(): Result<Unit> = auth.signOut()

    // ----- 文件夹选择 -----

    override suspend fun listFolders(parentId: String, parentPath: String): Result<List<CloudFolder>> =
        runCatching {
            require(auth.account.value != null) { "尚未连接 OneDrive 账号" }
            drive().children(parentId)
                .filter { it.isFolder }
                .sortedBy { it.name.lowercase() }
                .map { CloudFolder(id = it.id, name = it.name, path = childPath(parentPath, it.name)) }
        }

    // ----- 绑定 -----

    override suspend fun bindFolder(projectId: String, folder: CloudFolder): Result<SyncReport> =
        guarded("正在同步") {
            val report = engine.bind(drive(), projectId, folder.id, folder.path).getOrThrow()
            record(projectId, report)
        }

    override suspend fun createAndBindFolder(
        projectId: String,
        parentId: String,
        parentPath: String,
        name: String
    ): Result<SyncReport> = guarded("正在新建云端文件夹") {
        val report = engine
            .createFolderAndBind(drive(), projectId, parentId, name, childPath(parentPath, name))
            .getOrThrow()
        record(projectId, report)
    }

    override suspend fun unbind(projectId: String): Result<Unit> = runCatching {
        engine.unbind(projectId)
        check(projects.updateOneDrive(projectId) { null }) { "未知项目：$projectId" }
        _syncState.value = SyncState.Info("已取消关联")
    }

    // ----- 同步 -----

    override suspend fun sync(projectId: String, throttled: Boolean): Result<SyncReport?> = lock.withLock {
        val binding = projects.oneDriveBinding(projectId) ?: return@withLock Result.success(null)
        if (auth.account.value == null) return@withLock Result.success(null)
        val elapsed = System.currentTimeMillis() - lastPullAtMs
        if (throttled && elapsed < pullIntervalMs) return@withLock Result.success(null)
        guarded("正在同步") {
            val report = engine.sync(drive(), projectId, binding).getOrThrow()
            lastPullAtMs = System.currentTimeMillis()
            record(projectId, report)
        }
    }

    override suspend fun pushAfterRun(projectId: String): Result<SyncReport?> = lock.withLock {
        val binding = projects.oneDriveBinding(projectId) ?: return@withLock Result.success(null)
        if (auth.account.value == null) return@withLock Result.success(null)
        runCatching {
            val report = engine.pushOnly(drive(), projectId, binding).getOrThrow()
            if (report.uploaded == 0 && report.deletedRemote == 0) {
                // 本次 run 没改动工作区：不发状态、不动 lastSyncedAt（这就是"懒惰"的落点）
                null
            } else {
                _syncState.value = SyncState.Info(summarize(report))
                record(projectId, report)
            }
        }.onFailure {
            _syncState.value = SyncState.Error(it.message ?: it::class.simpleName ?: "OneDrive 同步失败")
        }
    }

    // ----- 内部 -----

    /** 绑定信息落库：只有成功写回才继续，否则显式失败（不留下"以为绑上了"的假象）。 */
    private fun record(projectId: String, report: SyncReport): SyncReport {
        check(projects.updateOneDrive(projectId) { report.binding }) { "未知项目：$projectId" }
        _syncState.value = SyncState.Info(summarize(report))
        return report
    }

    private suspend fun <T> guarded(label: String, block: suspend () -> T): Result<T> {
        _syncState.value = SyncState.Working(label)
        return runCatching { block() }.onFailure {
            _syncState.value = SyncState.Error(it.message ?: it::class.simpleName ?: "OneDrive 操作失败")
        }
    }

    private fun drive(): RemoteDrive = client ?: synchronized(this) {
        client ?: OneDriveGraphClient(auth).also { client = it }
    }

    private fun childPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    private fun summarize(report: SyncReport): String {
        val parts = buildList {
            if (report.uploaded > 0) add("上传 ${report.uploaded}")
            if (report.downloaded > 0) add("下载 ${report.downloaded}")
            if (report.deletedRemote > 0) add("云端删除 ${report.deletedRemote}")
            if (report.deletedLocal > 0) add("本地删除 ${report.deletedLocal}")
        }
        val head = if (parts.isEmpty()) "已是最新" else parts.joinToString(" · ")
        return when {
            report.warnings.isNotEmpty() -> "$head（${report.warnings.first()}）"
            report.localWins.isNotEmpty() -> "$head（${report.localWins.size} 处双向冲突，以本地为准）"
            else -> head
        }
    }

    private companion object {
        /**
         * 进入项目触发拉取的最小间隔。10 分钟是"够新"与"够省电"之间的取舍：拉取走
         * delta 游标只回变化量，但每次仍要唤醒网络栈，对端侧电量不友好。
         */
        const val DEFAULT_PULL_INTERVAL_MS = 10 * 60 * 1000L
    }
}
