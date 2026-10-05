package com.yangz3720.simplywork

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.yangz3720.simplywork.core.AgentRunSupervisor
import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.agent.AgentConfig
import com.yangz3720.simplywork.core.agent.AuditApprovalHandler
import com.yangz3720.simplywork.core.agent.ContextBudget
import com.yangz3720.simplywork.core.agent.ContextBudgetPlan
import com.yangz3720.simplywork.core.agent.IncomingMessage
import com.yangz3720.simplywork.core.agent.ScriptJobRegistry
import com.yangz3720.simplywork.core.agent.tool.AgentSelfConfig
import com.yangz3720.simplywork.core.agent.tool.ScriptTools
import com.yangz3720.simplywork.core.agent.tool.SelfOpsTools
import com.yangz3720.simplywork.core.agent.tool.TtsTool
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
import com.yangz3720.simplywork.infrastructure.sandbox.engine.QuickJsSandbox
import com.yangz3720.simplywork.infrastructure.systemops.SystemCapabilityProbeImpl
import com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouter
import com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouterImpl
import com.yangz3720.simplywork.infrastructure.systemops.intent.SystemIntentExecutorImpl
import com.yangz3720.simplywork.service.AgentRunService
import com.yangz3720.simplywork.ui.agent.AgentRuntimeOwner
import com.yangz3720.simplywork.ui.agent.ApprovalCoordinator
import com.yangz3720.simplywork.ui.agent.ModelSelection
import com.yangz3720.simplywork.ui.agent.PermissionCoordinator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Composition root for the app (UI stage): owns [ProviderKeyring] (multiple provider keys + current
 * selection) persistence, shared systemops wiring, [ApprovalCoordinator] (Q1 HITL seam) and the
 * production [AgentRuntime].
 *
 * Implements [AgentRuntimeOwner] for ViewModel consumption. Rebuild semantics: rebuild the runtime
 * when a keyring change affects the currently selected provider/model/timeout; runs that are
 * cancelled/in-flight in the old runtime hold the reference they captured and terminate naturally
 * -- the caller guarantees that a rebuild is only triggered while idle.
 */
class AppContainer(context: Context) : AgentRuntimeOwner {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyring = ProviderKeyring(prefs)

    /** HITL approval coordinator (Q1): UI dialog decisions are fed back through here to the audit gate. */
    val approvalCoordinator = ApprovalCoordinator()

    /** Runtime permission coordinator (v1.2 / P4): system grant results are fed back through here to the systemops executor. */
    val permissionCoordinator = PermissionCoordinator()

    /** Project registry (v1.3): a project = session ownership + sandbox workspace directory. */
    val projectStore = ProjectStore(prefs)

    /** Agent sandbox root: one subdirectory per project (`agent_workspace/projects/<projectId>/`). */
    private val workspace = AgentWorkspace(
        File(appContext.filesDir, "agent_workspace/projects")
    )

    /**
     * Change log (v1.5): each fs_* change persists one JSON file (`agent_workspace/changes/<sessionId>.json`).
     * Deliberately does **not** go into the session payload store -- that is the raw record fed to the model,
     * whereas the diff lines only serve the UI, so they should not consume context budget and the model
     * should not see its own diff (it already has the tool results).
     */
    private val changeLog: FileChangeLog =
        JsonFileChangeLog(File(appContext.filesDir, "agent_workspace/changes"))

    /**
     * Project file seam (v1.5): read-only browsing / preview + reading session changes. Same source as
     * [workspace], so the directory the UI sees is the very directory the agent works in.
     */
    val projectFiles: ProjectFilesOwner by lazy { LocalProjectFiles(workspace, changeLog) }

    /**
     * OneDrive integration (v1.4): lazy wiring -- no network or MSAL initialization, only object
     * creation at app startup. Real sign-in/sync is always triggered explicitly by the UI (the entry
     * control point of "lazy sync").
     */
    val oneDrive: OneDriveOwner by lazy { OneDriveService(appContext, projectStore, workspace) }

    /**
     * Global access point of Context.
     */
    fun getContext(): Context { return appContext }

    /** Config self-operation seam (v1.3): the agent's self_config_read/update goes through here, same source as the UI. */
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
                        // The API key itself never crosses the seam (the user's call): the model only sees whether one exists.
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
            // An agent change made during a run cannot rebuild the runtime immediately (it would swap out
            // the running coordinator): the keyring is already persisted, so the rebuild is deferred until
            // the VM observes configChanges while idle.
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Run driver (v1.6): ownership of runs moved here from `AgentViewModel.viewModelScope`.
     * Previously a run was cut off as soon as the Activity was destroyed, leaving the foreground service
     * unable to keep anything alive (the service lived while the run was already dead); now the driver
     * belongs to the process and [AgentRunService] only pins the process in the foreground.
     *
     * [runtime] is passed as a lambda rather than a value: a config change swaps the runtime instance
     * wholesale ([rebuildRuntime]), so every start must get the one that is current at that moment.
     */
    val runSupervisor = AgentRunSupervisor(
        scope = appScope,
        runtime = { runtime },
        onRunStarted = { appContext.startForegroundService(Intent(appContext, AgentRunService::class.java)) },
        onRunStopped = {
            appContext.stopService(Intent(appContext, AgentRunService::class.java))
            // Orphan scripts keep occupying the sandbox's serial execution lock (QuickJsSandbox.execMutex),
            // blocking every script of the **next** run -- so cancel them while stopping the service.
            appScope.launch { scriptJobs.cancelAll() }
        }
    )

    /**
     * Sandbox + async script jobs (v1.6). The engine is lazily bound: this only creates objects, and only
     * the first `js_run` connects to the AndroidX JS service process, so startup costs nothing and cold
     * start is unaffected.
     */
    private val scriptJobs: ScriptJobRegistry by lazy {
        ScriptJobRegistry(
            scope = appScope,
            sandbox = QuickJsSandbox(appContext),
            onFinished = { jobId, sessionId, result ->
                // Only wake the loop when "the same session is still running". Do not enqueue this notice
                // once the run has ended -- the queue belongs to the runtime and does not distinguish
                // sessions, so it would be drained by the next run (which may already be another session).
                val run = runSupervisor.state.value
                if (run.running && run.sessionId == sessionId) {
                    runtime?.queue?.enqueue(
                        IncomingMessage.FollowUp(
                            ScriptTools.finishNotice(jobId, result),
                            REASON_JS_JOB_FINISHED
                        )
                    )
                }
            }
        )
    }

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

    override fun applySelection(providerId: String, model: String) {
        mutateKeyring { KeyringLogic.selectModel(it, providerId, model) }
        rebuildRuntime()
    }

    override fun autoApproveNonDestructive(): Boolean = keyring.state.autoApproveNonDestructive

    override fun setAutoApproveNonDestructive(enabled: Boolean) {
        mutateKeyring { KeyringLogic.withAutoApprove(it, enabled) }
        // The gate holds the switch in its constructor argument, so a change only takes effect after a rebuild (same path as timeout/selection).
        rebuildRuntime()
    }

    /**
     * Returns null when no model is selected, rather than a "default 24k" budget: there is no request to
     * send at that point, and reporting a number would make people believe that budget is actually in
     * effect (the settings page's "no model selected" branch exists precisely for this).
     */
    override fun contextBudget(): ContextBudgetPlan? =
        if (keyring.state.activeModel.isBlank()) null else contextBudgetPlan()

    override fun setContextBudgetOverride(tokens: Int?) {
        mutateKeyring { KeyringLogic.withContextBudgetOverride(it, tokens) }
        // The budget goes into AgentConfig (compaction trigger + working window), so it likewise only takes effect after a rebuild.
        rebuildRuntime()
    }

    /**
     * Fetches the model list (GET /models) for a provider account. A one-off throwaway client + IO thread +
     * Throwable fallback: for non-IOException OkHttp rethrows as-is to the thread pool after the callback
     * (a process-level FATAL), so everything here is folded into Result (except cancellation).
     *
     * v1.1: incidentally records the **context length** reported by the provider. The model list is fetched
     * for the selector anyway, so learning it costs no extra request -- and the context budget needs it
     * (see [contextBudgetPlan]).
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
                client.listModelCatalog()
                    .onSuccess { catalog ->
                        mutateKeyring { state ->
                            KeyringLogic.withModelContexts(
                                state,
                                catalog.mapNotNull { info ->
                                    info.contextTokens?.let {
                                        KeyringLogic.contextKey(account.id, info.id) to it
                                    }
                                }.toMap()
                            )
                        }
                    }
                    .map { catalog -> catalog.map { it.id } }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                client.close()
            }
        }

    // ----- internal wiring -----

    /** Config version: incremented on any keyring change; the agent's own config edits use it to tell the VM to rebuild while idle. */
    private val configVersionFlow = MutableStateFlow(0)
    private var builtVersion = 0

    /** Config version for the VM to observe (AgentRuntimeOwner seam, v1.3). */
    override val configVersion: StateFlow<Int> = configVersionFlow.asStateFlow()

    private fun mutateKeyring(transform: (KeyringState) -> KeyringState) {
        keyring.update(transform)
        configVersionFlow.value += 1
    }

    /**
     * Rebuilds when the keyring has changed but the runtime has not been rebuilt yet (the landing step
     * after the agent edits its own config). The VM guarantees it is only called while idle; repeated
     * calls are a no-op.
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
            config = agentConfig(),
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
                ) + ScriptTools.registeredTools(scriptJobs) +
                    TtsTool.registeredTool(appContext, workspace, projectStore)
            }
        )

    /**
     * User settings -> loop/gate parameters (v1.1). The translation is centralized here; everywhere else
     * only understands the semantics of [AgentConfig].
     */
    private fun agentConfig(): AgentConfig {
        val plan = contextBudgetPlan()
        return AgentConfig(
            autoApproveNonDestructive = keyring.state.autoApproveNonDestructive,
            // Compact when it does not fit; the working window scales proportionally with the budget (see ContextBudget's derivation table).
            compactionTriggerTokens = plan.compactionTriggerTokens,
            workingTokenBudget = plan.workingTokenBudget
        )
    }

    /**
     * Context budget for the current model: manual override > provider-reported > spec in the model name
     * > historical default. The arbitration itself is a pure function ([ContextBudget.resolve]); here we
     * only pull out the three candidate values.
     */
    private fun contextBudgetPlan(): ContextBudgetPlan {
        val state = keyring.state
        val reported = state.activeId?.let { accountId ->
            state.modelContextTokens[KeyringLogic.contextKey(accountId, state.activeModel)]
        }
        return ContextBudget.resolve(
            overrideTokens = state.contextBudgetOverride,
            providerTokens = reported,
            modelId = state.activeModel
        )
    }

    private fun createProvider(config: ModelConfig): ModelProvider = OpenAiCompatibleClient(config)

    private companion object {
        const val PREFS_NAME = "simplywork"

        /** Reason for the async script job completion notice (lands in a follow-up node, for events and troubleshooting to identify). */
        const val REASON_JS_JOB_FINISHED = "js_job_finished"
    }
}
