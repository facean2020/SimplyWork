package com.yangz3720.simplywork

import android.content.Context
import android.content.SharedPreferences
import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.agent.AuditApprovalHandler
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

    /** Shared systemops wiring: router is context-bound, not config-bound. */
    private val router: SystemOperationRouter by lazy {
        val probe = SystemCapabilityProbeImpl.from(appContext)
        SystemOperationRouterImpl(SystemIntentExecutorImpl(appContext, probe), probe)
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
        keyring.update { KeyringLogic.withKey(it, accountId, apiKey) }
        rebuildIfActive(accountId)
    }

    override fun upsertCustomAccount(name: String, baseUrl: String, apiKey: String): ProviderAccount {
        val account = ProviderAccount(
            id = KeyringLogic.CUSTOM_ID_PREFIX + UUID.randomUUID(),
            name = name.trim().ifEmpty { "自定义" },
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim()
        )
        keyring.update { KeyringLogic.upsertCustom(it, account) }
        return account
    }

    override fun removeCustomAccount(accountId: String) {
        keyring.update { KeyringLogic.removeAccount(it, accountId) }
        rebuildRuntime()
    }

    override fun setTimeout(ms: Long) {
        keyring.update { KeyringLogic.withTimeout(it, ms) }
        rebuildRuntime()
    }

    override fun applySelection(providerId: String, model: String) {
        keyring.update { KeyringLogic.selectModel(it, providerId, model) }
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

    private fun rebuildIfActive(accountId: String) {
        if (keyring.state.activeId == accountId) rebuildRuntime()
    }

    private fun rebuildRuntime() {
        runtime = KeyringLogic.toModelConfig(keyring.state)?.let(::buildRuntime)
    }

    private fun buildRuntime(config: ModelConfig): AgentRuntime =
        AgentRuntime.create(
            appContext,
            createProvider(config),
            router,
            approvalHandler = AuditApprovalHandler { toolName, arguments, _ ->
                approvalCoordinator.request(toolName, arguments.toString())
            }
        )

    private fun createProvider(config: ModelConfig): ModelProvider = OpenAiCompatibleClient(config)

    private companion object {
        const val PREFS_NAME = "simplywork"
    }
}
