package com.yangz3720.simplywork

import android.content.Context
import android.content.SharedPreferences
import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.agent.AuditApprovalHandler
import com.yangz3720.simplywork.core.agent.tool.AgentSelfConfig
import com.yangz3720.simplywork.core.agent.tool.SelfOpsTools
import com.yangz3720.simplywork.core.cloud.OneDriveOwner
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.FileChangeLog
import com.yangz3720.simplywork.core.workspace.JsonFileChangeLog
import com.yangz3720.simplywork.core.workspace.LocalProjectFiles
import com.yangz3720.simplywork.core.workspace.ProjectFilesOwner
import com.yangz3720.simplywork.KeyringState
import com.yangz3720.simplywork.infrastructure.cloud.onedrive.OneDriveService
import com.yangz3720.simplywork.infrastructure.model.ModelProvider
import com.yangz3720.simplywork.infrastructure.model.client.OpenAiCompatibleClient
import com.yangz3720.simplywork.infrastructure.model.config.ModelConfig
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import com.yangz3720.simplywork.infrastructure.systemops.SystemCapabilityProbeImpl
import com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouter
import com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouterImpl
import com.yangz3720.simplywork.infrastructure.systemops.intent.SystemIntentExecutorImpl
import com.yangz3720.simplywork.ui.agent.AgentRuntimeOwner
import com.yangz3720.simplywork.ui.agent.ApprovalCoordinator
import com.yangz3720.simplywork.ui.agent.ModelSelection
import com.yangz3720.simplywork.ui.agent.PermissionCoordinator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Composition root for the app（UI 阶段）：owns [ProviderKeyring]（多服务商密钥 + 当前
 * 选择）持久化、共享 systemops 装配、[ApprovalCoordinator]（Q1 HITL 缝）与生产
 * [AgentRuntime]。
 *
 * 实现 [AgentRuntimeOwner] 供 ViewModel 消费。重建语义：keyring 变更影响当前选中的
 * provider/model/timeout 时重建运行时；旧运行时中被取消/进行中的 run 持有其捕获的引用
 * 自然终止——调用方保证仅在空闲时触发重建。
 */
class AppContainer(context: Context) : AgentRuntimeOwner {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyring = ProviderKeyring(prefs)

    /** HITL 审批协调器（Q1）：UI 弹窗决策经此回灌审计门禁。 */
    val approvalCoordinator = ApprovalCoordinator()

    /** 运行时权限协调器（v1.2 / P4）：系统授权结果经此回灌 systemops 执行器。 */
    val permissionCoordinator = PermissionCoordinator()

    /** 项目注册表（v1.3）：项目 = 会话归属 + 沙箱工作空间目录。 */
    val projectStore = ProjectStore(prefs)

    /** Agent 沙箱根：每个项目一个子目录（`agent_workspace/projects/<projectId>/`）。 */
    private val workspace = AgentWorkspace(
        File(appContext.filesDir, "agent_workspace/projects")
    )

    /**
     * 改动记录（v1.5）：fs_* 每笔改动落一个 JSON 文件（`agent_workspace/changes/<sessionId>.json`）。
     * 故意**不**进会话 payload store —— 那是喂给模型的原始记录，而差异行只服务 UI，
     * 不该占上下文预算，也不该让模型看见自己的 diff（它已经有工具结果了）。
     */
    private val changeLog: FileChangeLog =
        JsonFileChangeLog(File(appContext.filesDir, "agent_workspace/changes"))

    /**
     * 项目文件接缝（v1.5）：只读浏览 / 预览 + 会话改动读取。与 [workspace] 同源，
     * 所以 UI 看到的目录就是 agent 动手的那个目录。
     */
    val projectFiles: ProjectFilesOwner by lazy { LocalProjectFiles(workspace, changeLog) }

    /**
     * OneDrive 接入（v1.4）：惰性装配 —— 不做任何网络或 MSAL 初始化，只在 App 启动时
     * 建对象。真正的登录/同步都由 UI 显式触发（"懒惰同步"的入口控制点）。
     */
    val oneDrive: OneDriveOwner by lazy { OneDriveService(appContext, projectStore, workspace) }

    /** 配置自操作缝（v1.3）：agent 的 self_config_read/update 走这里，与 UI 同源。 */
    private val selfConfig = object : AgentSelfConfig {

        override fun snapshot(): AgentSelfConfig.Snapshot {
            val state = keyring.state
            return AgentSelfConfig.Snapshot(
                activeProviderId = state.activeId,
                activeModel = state.activeModel,
                timeoutMs = state.timeoutMs,
                accounts = state.accounts.map {
                    AgentSelfConfig.AccountView(
                        id = it.id,
                        name = it.name,
                        baseUrl = it.baseUrl,
                        // API Key 本体永不出缝（用户拍板）：模型只见"有没有"。
                        hasKey = it.apiKey.isNotBlank()
                    )
                },
                projects = projectStore.list(),
                currentProjectId = projectStore.currentId()
            )
        }

        override suspend fun apply(mutation: AgentSelfConfig.Mutation): AgentSelfConfig.ApplyResult {
            val applied = ArrayList<String>()
            val errors = ArrayList<String>()
            if (mutation.providerId != null || mutation.model != null) {
                val providerId = mutation.providerId
                val model = mutation.model
                if (providerId == null || model == null) {
                    errors += "providerId and model must be set together"
                } else if (keyring.state.accounts.none { it.id == providerId }) {
                    errors += "unknown providerId '${mutation.providerId}' (see self_config_read.accounts)"
                } else {
                    mutateKeyring { KeyringLogic.selectModel(it, providerId, model) }
                    applied += "active provider/model → ${mutation.providerId}/${model}"
                }
            }
            mutation.timeoutMs?.let { ms ->
                if (ms !in 1_000..600_000L) {
                    errors += "timeoutMs must be between 1000 and 600000 (got $ms)"
                } else {
                    mutateKeyring { KeyringLogic.withTimeout(it, ms) }
                    applied += "timeoutMs → $ms"
                }
            }
            mutation.projectId?.let { id ->
                if (projectStore.switchTo(id)) {
                    applied += "current project → $id (${projectStore.byId(id)?.name})"
                } else {
                    errors += "unknown projectId '$id' (see self_config_read.projects)"
                }
            }
            // Agent 在 run 内改动不能立刻重建 runtime（会换掉运行中的 coordinator）：
            // keyring 已落盘，重建推迟到 VM 观察到 configChanges 且空闲时。
            return AgentSelfConfig.ApplyResult(
                applied = applied,
                rebuildPending = applied.any { !it.startsWith("current project") },
                errors = errors
            )
        }
    }

    /** Shared systemops wiring: router is context-bound, not config-bound. */
    private val router: SystemOperationRouter by lazy {
        val probe = SystemCapabilityProbeImpl.from(appContext)
        SystemOperationRouterImpl(
            SystemIntentExecutorImpl(
                appContext,
                probe,
                permissionRequester = permissionCoordinator.asRequester()
            ),
            probe
        )
    }

    @Volatile private var runtime: AgentRuntime? = null

    init {
        runtime = KeyringLogic.toModelConfig(keyring.state)?.let(::buildRuntime)
    }

    // ----- AgentRuntimeOwner -----

    override fun current(): AgentRuntime? = runtime

    override fun accounts(): List<ProviderAccount> = keyring.state.accounts

    override fun selection(): ModelSelection? {
        val state = keyring.state
        val id = state.activeId ?: return null
        return ModelSelection(providerId = id, model = state.activeModel)
    }

    override fun timeoutMs(): Long = keyring.state.timeoutMs

    override fun saveAccountKey(accountId: String, apiKey: String) {
        mutateKeyring { KeyringLogic.withKey(it, accountId, apiKey) }
        rebuildIfActive(accountId)
    }

    override fun upsertCustomAccount(name: String, baseUrl: String, apiKey: String): ProviderAccount {
        val account = ProviderAccount(
            id = KeyringLogic.CUSTOM_ID_PREFIX + UUID.randomUUID(),
            name = name.trim().ifEmpty { "自定义" },
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim()
        )
        mutateKeyring { KeyringLogic.upsertCustom(it, account) }
        return account
    }

    override fun removeCustomAccount(accountId: String) {
        mutateKeyring { KeyringLogic.removeAccount(it, accountId) }
        rebuildRuntime()
    }

    override fun setTimeout(ms: Long) {
        mutateKeyring { KeyringLogic.withTimeout(it, ms) }
        rebuildRuntime()
    }

    override fun applySelection(providerId: String, model: String) {
        mutateKeyring { KeyringLogic.selectModel(it, providerId, model) }
        rebuildRuntime()
    }

    /**
     * 按服务商账号拉取模型列表（GET /models）。一次性临时客户端 + IO 线程 +
     * Throwable 兜底：OkHttp 对非 IOException 会在回调后原样 rethrow 到线程池
     * （进程级 FATAL），这里统一收编为 Result（取消除外）。
     */
    override suspend fun fetchModels(account: ProviderAccount): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val client = OpenAiCompatibleClient(
                ModelConfig(
                    baseUrl = account.baseUrl,
                    apiKey = account.apiKey,
                    defaultModel = "",
                    timeoutMs = keyring.state.timeoutMs
                )
            )
            try {
                client.listModels()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                client.close()
            }
        }

    // ----- 内部装配 -----

    /** 配置版本：任何 keyring 变更自增；agent 自改配置靠它通知 VM 在空闲时重建。 */
    private val configVersionFlow = MutableStateFlow(0)
    private var builtVersion = 0

    /** 供 VM 观察的配置版本（AgentRuntimeOwner 缝，v1.3）。 */
    override val configVersion: StateFlow<Int> = configVersionFlow.asStateFlow()

    private fun mutateKeyring(transform: (KeyringState) -> KeyringState) {
        keyring.update(transform)
        configVersionFlow.value += 1
    }

    /**
     * keyring 已改但 runtime 未重建时重建（agent 自改配置后的落地步）。
     * VM 保证仅在空闲时调用；重复调用是 no-op。
     */
    override fun rebuildRuntimeIfNeeded() {
        if (builtVersion != configVersionFlow.value) rebuildRuntime()
    }

    private fun rebuildIfActive(accountId: String) {
        if (keyring.state.activeId == accountId) rebuildRuntime()
    }

    private fun rebuildRuntime() {
        runtime = KeyringLogic.toModelConfig(keyring.state)?.let(::buildRuntime)
        builtVersion = configVersionFlow.value
    }

    private fun buildRuntime(config: ModelConfig): AgentRuntime =
        AgentRuntime.create(
            appContext,
            createProvider(config),
            router,
            approvalHandler = AuditApprovalHandler { toolName, arguments, _ ->
                approvalCoordinator.request(toolName, arguments.toString())
            },
            selfOps = { repository ->
                SelfOpsTools.registeredTools(
                    repository = repository,
                    config = selfConfig,
                    workspace = workspace,
                    projects = projectStore,
                    changes = changeLog
                )
            }
        )

    private fun createProvider(config: ModelConfig): ModelProvider = OpenAiCompatibleClient(config)

    private companion object {
        const val PREFS_NAME = "simplywork"
    }
}
