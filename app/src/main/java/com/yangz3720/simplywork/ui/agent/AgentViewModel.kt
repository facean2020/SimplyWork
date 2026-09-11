package com.yangz3720.simplywork.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.agent.AgentStepEvent
import com.yangz3720.simplywork.core.agent.IncomingMessage
import com.yangz3720.simplywork.core.session.SessionRecord
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 当前模型选择：服务商账号 + 模型 id（对话界面顶栏动态切换）。 */
data class ModelSelection(val providerId: String, val model: String)

/**
 * Composition-root 缝（architecture.md `AgentViewModel` 的依赖注入面）：App 容器实现，
 * JVM 测试用 fake。多服务商语义：各服务商只保存密钥，provider+model 由对话屏动态选择。
 */
interface AgentRuntimeOwner {
    fun current(): AgentRuntime?
    fun accounts(): List<ProviderAccount>
    fun selection(): ModelSelection?
    fun timeoutMs(): Long

    /** 设置页：保存某服务商的 API Key（若为当前选中服务商则重建运行时）。 */
    fun saveAccountKey(accountId: String, apiKey: String)

    /** 设置页：新增自定义端点，返回落库后的账号。 */
    fun upsertCustomAccount(name: String, baseUrl: String, apiKey: String): ProviderAccount

    /** 设置页：删除自定义端点（预设不可删，调用方保证）。 */
    fun removeCustomAccount(accountId: String)

    /** 设置页：全局请求超时（毫秒）。 */
    fun setTimeout(ms: Long)

    /** 对话屏顶栏：切换当前服务商 + 模型并重建运行时（调用方保证当前无运行中的 run）。 */
    fun applySelection(providerId: String, model: String)

    /** 按服务商账号拉取端点可用模型列表（GET /models）。 */
    suspend fun fetchModels(account: ProviderAccount): Result<List<String>>
}

/**
 * MVI 状态管理器（architecture.md UI 层）：把 [AgentRuntimeOwner] 提供的循环/队列
 * 与 SessionRepository 投影成 [AgentUiState]。
 *
 * 职责：
 * - 运行驱动：collect `AgentLoopCoordinator.run` 的 cold Flow（Q2：循环外层重启，
 *   失败后由 [retry] 以 `initialPrompt=null` 续跑）；
 * - 多会话：ChatGPT 式列表（[startNewChat] 延迟建会话，首条消息以首行为标题）；
 * - HITL（Q1）：透出 [ApprovalCoordinator.pending] 供 UI 弹窗决策；
 * - 运行中输入自动降级为 Steer（design §6 节点 B）。
 */
class AgentViewModel(
    private val owner: AgentRuntimeOwner,
    val approvalCoordinator: ApprovalCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private fun initialState(): AgentUiState {
        val selection = owner.selection()
        return AgentUiState(
            providerAccounts = owner.accounts(),
            activeProviderId = selection?.providerId,
            activeModel = selection?.model.orEmpty(),
            modelConfigured = selection != null && selection.model.isNotBlank()
        )
    }

    /** HITL 挂起请求（Q1）；非空时 UI 显示审批对话框。 */
    val approval = approvalCoordinator.pending

    private var runJob: Job? = null

    init {
        viewModelScope.launch(ioDispatcher) {
            val sessions = repository()?.listSessions().orEmpty()
            _uiState.update { it.copy(sessions = sessions) }
            // 打开最近一个会话；没有会话则停留在"新对话"空态。
            sessions.firstOrNull()?.id?.let { openSession(it) }
        }
    }

    // ----- 会话管理 -----

    /** ChatGPT 式新对话：清空当前投影；会话在首条消息发送时才落库。 */
    fun startNewChat() {
        runJob?.cancel()
        _uiState.update { current ->
            AgentUiState(
                sessions = current.sessions,
                modelConfigured = current.modelConfigured
            )
        }
    }

    fun openSession(sessionId: String) {
        if (_uiState.value.currentSessionId == sessionId) return
        runJob?.cancel()
        _uiState.update { it.copy(currentSessionId = sessionId, running = false, runError = null) }
        viewModelScope.launch(ioDispatcher) {
            refreshConversation()
            refreshTodos()
        }
    }

    /** 抽屉打开时的会话列表刷新入口。 */
    fun refreshSessions() {
        viewModelScope.launch(ioDispatcher) {
            val sessions = repository()?.listSessions().orEmpty()
            _uiState.update { it.copy(sessions = sessions) }
        }
    }

    // ----- 输入与运行 -----

    /**
     * 发送输入：空闲 → 启动一轮 run；运行中 → 降级为 Steer（下一步循环生效）。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val state = _uiState.value
        if (state.running) {
            owner.current()?.queue?.enqueue(IncomingMessage.Steer(trimmed))
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val sessionId = state.currentSessionId ?: createSessionFor(trimmed)
            launchRun(sessionId, trimmed)
        }
    }

    /** Q2：失败后的循环外层重启 —— 同一会话续跑，不追加任何用户消息。 */
    fun retry() {
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        if (state.running) return
        viewModelScope.launch(ioDispatcher) { launchRun(sessionId, initialPrompt = null) }
    }

    /**
     * 编辑历史提问并重新发送（分支语义）：在原消息的父节点下追加一条新 UserPrompt
     * （新分支，旧分支保留），叶子推进到新分支后以 `initialPrompt=null` 重启循环
     * ——循环直接 assemble 现有路径，无需重复入队。
     */
    fun editAndResend(messageId: String, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return
        val state = _uiState.value
        if (state.running || state.currentSessionId == null) return
        val sessionId = state.currentSessionId
        viewModelScope.launch(ioDispatcher) {
            val repository = repository() ?: return@launch
            val node = withContext(ioDispatcher) {
                repository.listNodes(sessionId).firstOrNull { it.id == messageId }
            } ?: return@launch
            repository.appendUserPrompt(sessionId, node.parentId, trimmed)
            launchRun(sessionId, initialPrompt = null)
        }
    }

    /** 分支导航：‹ › 切换到相邻历史分支（叶子切到该分支的最新状态）。 */
    fun switchBranch(messageId: String, direction: Int) {
        val state = _uiState.value
        if (state.running || state.currentSessionId == null) return
        val sessionId = state.currentSessionId
        viewModelScope.launch(ioDispatcher) {
            val repository = repository() ?: return@launch
            val siblings = withContext(ioDispatcher) { repository.promptSiblings(sessionId, messageId) }
            if (siblings.size < 2) return@launch
            val index = siblings.indexOfFirst { it.id == messageId }
            val targetIndex = index + direction
            if (targetIndex !in siblings.indices) return@launch
            val leaf = withContext(ioDispatcher) { repository.branchLeaf(sessionId, siblings[targetIndex].id) }
            repository.updateActiveLeaf(sessionId, leaf)
            refreshConversation()
            refreshTodos()
        }
    }

    /** 运行中止（对齐 ChatGPT 的停止语义）：已落库节点保留，pending 节点由下次 run 清扫。 */
    fun stopRun() {
        runJob?.cancel()
    }

    fun respondApproval(allowed: Boolean) {
        approvalCoordinator.respond(allowed)
    }

    // ----- 服务商 / 模型选择 -----

    /** 顶栏动态切换模型：运行中拒绝（切换会重建运行时，打断当前 run）。 */
    fun selectModel(providerId: String, model: String) {
        if (_uiState.value.running) {
            _uiState.update { it.copy(runError = "正在运行中，请先停止当前任务再切换模型") }
            return
        }
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return
        owner.applySelection(providerId, trimmed)
        _uiState.update { it.copy(activeProviderId = providerId, activeModel = trimmed, modelConfigured = true) }
    }

    /** 设置页：保存服务商 API Key。 */
    fun saveAccountKey(accountId: String, apiKey: String) {
        owner.saveAccountKey(accountId, apiKey)
        _uiState.update { it.copy(providerAccounts = owner.accounts()) }
    }

    /** 设置页：新增自定义端点。 */
    fun addCustomAccount(name: String, baseUrl: String, apiKey: String) {
        owner.upsertCustomAccount(name, baseUrl, apiKey)
        _uiState.update { it.copy(providerAccounts = owner.accounts()) }
    }

    /** 设置页：删除自定义端点（若是当前选中，同步清空选择态）。 */
    fun removeCustomAccount(accountId: String) {
        owner.removeCustomAccount(accountId)
        val selection = owner.selection()
        _uiState.update {
            it.copy(
                providerAccounts = owner.accounts(),
                activeProviderId = selection?.providerId,
                activeModel = selection?.model.orEmpty(),
                modelConfigured = selection != null && selection.model.isNotBlank()
            )
        }
    }

    /** 设置页：全局请求超时。 */
    fun saveTimeoutMs(ms: Long) {
        owner.setTimeout(ms)
    }

    /** 设置页展示当前全局超时。 */
    fun timeoutMs(): Long = owner.timeoutMs()

    /** 对话屏/设置页：拉取某服务商的模型列表（GET /models）。 */
    suspend fun fetchModels(account: ProviderAccount): Result<List<String>> = owner.fetchModels(account)

    // ----- 内部实现 -----

    private fun repository() = owner.current()?.repository

    private suspend fun createSessionFor(prompt: String): String {
        val title = prompt.lineSequence().firstOrNull().orEmpty().take(24).ifBlank { "新对话" }
        val record = repository()?.createSession(title)
            ?: throw IllegalStateException("Runtime unavailable")
        _uiState.update {
            it.copy(
                currentSessionId = record.id,
                currentTitle = record.title,
                sessions = listOf(record) + it.sessions
            )
        }
        return record.id
    }

    private fun launchRun(sessionId: String, initialPrompt: String?) {
        val runtime = owner.current() ?: run {
            _uiState.update { it.copy(runError = "模型未配置，请先在设置中完成配置") }
            return
        }
        runJob?.cancel()
        _uiState.update { it.copy(running = true, runError = null, iteration = 0) }
        runJob = viewModelScope.launch(ioDispatcher) {
            try {
                runtime.coordinator.run(sessionId, initialPrompt).collect { event -> onEvent(event) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(running = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(running = false, runError = e.message ?: e::class.simpleName) }
            }
        }
    }

    private suspend fun onEvent(event: AgentStepEvent) {
        when (event) {
            is AgentStepEvent.LoopStarted ->
                _uiState.update { it.copy(running = true) }

            // 用户消息 / 转向落库即可见（不等下一轮 ModelTurn）。
            is AgentStepEvent.InboundPersisted -> refreshConversation()

            is AgentStepEvent.ModelTurn -> {
                _uiState.update { it.copy(iteration = event.iteration) }
                refreshConversation()
            }

            is AgentStepEvent.ToolStarted ->
                refreshConversation()

            is AgentStepEvent.ToolCompleted -> {
                refreshConversation()
                refreshTodos()
            }

            is AgentStepEvent.ToolRejected -> refreshConversation()

            is AgentStepEvent.ContextCompacted -> refreshConversation()

            is AgentStepEvent.CompactionSkipped -> Unit

            // Follow-up 在下一次 drain 时才落库，随后的 ModelTurn 会触发刷新。
            is AgentStepEvent.FollowUpInjected -> Unit

            is AgentStepEvent.Completed -> {
                _uiState.update { it.copy(running = false) }
                refreshConversation()
                refreshTodos()
                refreshSessions()
            }

            is AgentStepEvent.Failed -> {
                _uiState.update {
                    it.copy(running = false, runError = event.cause.message ?: event.cause::class.simpleName)
                }
                refreshConversation()
            }
        }
    }

    /** 以持久化事实为准重建对话投影（活跃路径 → UiMessage；全集节点提供分支信息）。 */
    private suspend fun refreshConversation() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val repository = repository() ?: return
        val path = withContext(ioDispatcher) { repository.activePath(sessionId) }
        val allNodes = withContext(ioDispatcher) { repository.listNodes(sessionId) }
        val messages = ConversationRenderer.render(path, allNodes) { node -> repository.payload(node) }
        val title = withContext(ioDispatcher) { repository.getSession(sessionId)?.title }
        _uiState.update { it.copy(messages = messages, currentTitle = title ?: it.currentTitle) }
    }

    private suspend fun refreshTodos() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val state = withContext(ioDispatcher) { repository()?.loadState(sessionId) } ?: return
        _uiState.update { it.copy(todos = state.todos) }
    }
}