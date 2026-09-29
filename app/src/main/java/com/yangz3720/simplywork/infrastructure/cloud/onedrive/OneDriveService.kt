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
 * Implementation of [OneDriveOwner] (v1.4): authentication (MSAL) + Graph + mirror engine + lazy scheduling policy.
 *
 * **Lazy policy** (the user set the tone: "two-way mirror, but as lazy as possible, low power"):
 * - Binding/creating a binding necessarily syncs once (initial calibration).
 * - `pushAfterRun` once a round of agent run ends: push only, never pull; zero network requests when there are no local changes.
 * - Entering/switching projects calls `sync(throttled = true)`: skipped outright when less than [pullIntervalMs] has passed since the last pull.
 * - No polling, no background tasks, no Webhook subscription (a Webhook needs a public-facing callback, which does not hold on the device side).
 *
 * Every sync operation is serialized on a single [Mutex]: only one project can be mirroring at a time,
 * which avoids both concurrent writes to the same manifest and concurrently hammering the token.
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

    /** The OkHttp engine is heavy, so it is created once on demand and then stays resident; it is never rebuilt for the lifetime of the app. */
    @Volatile
    private var client: OneDriveGraphClient? = null

    private val lock = Mutex()
    private var lastPullAtMs = 0L

    override val account = auth.account

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    override val syncState = _syncState.asStateFlow()

    override val configured: Boolean get() = auth.isConfigured()

    // ----- Account -----

    override suspend fun restore() {
        auth.restore()
    }

    override suspend fun connect(activity: Activity): Result<Unit> =
        auth.signIn(activity).map { }

    override suspend fun disconnect(): Result<Unit> = auth.signOut()

    // ----- Folder selection -----

    override suspend fun listFolders(parentId: String, parentPath: String): Result<List<CloudFolder>> =
        runCatching {
            require(auth.account.value != null) { "尚未连接 OneDrive 账号" }
            drive().children(parentId)
                .filter { it.isFolder }
                .sortedBy { it.name.lowercase() }
                .map { CloudFolder(id = it.id, name = it.name, path = childPath(parentPath, it.name)) }
        }

    // ----- Binding -----

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

    // ----- Sync -----

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
                // This run changed nothing in the workspace: emit no state, leave lastSyncedAt alone (this is where "laziness" lands)
                null
            } else {
                _syncState.value = SyncState.Info(summarize(report))
                record(projectId, report)
            }
        }.onFailure {
            _syncState.value = SyncState.Error(it.message ?: it::class.simpleName ?: "OneDrive 同步失败")
        }
    }

    // ----- Internals -----

    /** Persist the binding info: continue only once the write-back succeeds, otherwise fail explicitly (never leave the illusion of "it looked bound"). */
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
         * Minimum interval for the pull triggered by entering a project. 10 minutes is the trade-off between "fresh enough" and "frugal enough": a pull goes
         * through the delta cursor and returns only the changes, but it still wakes the network stack every time, which is unkind to device battery.
         */
        const val DEFAULT_PULL_INTERVAL_MS = 10 * 60 * 1000L
    }
}
