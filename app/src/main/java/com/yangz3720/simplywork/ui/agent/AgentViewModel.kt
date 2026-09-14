package com.yangz3720.simplywork.ui.agent

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.agent.AgentStepEvent
import com.yangz3720.simplywork.core.agent.IncomingMessage
import com.yangz3720.simplywork.core.cloud.CloudFolder
import com.yangz3720.simplywork.core.cloud.OneDriveOwner
import com.yangz3720.simplywork.core.session.ProjectLogic
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.core.session.SessionRecord
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.core.workspace.FileChangeKind
import com.yangz3720.simplywork.core.workspace.FileKinds
import com.yangz3720.simplywork.core.workspace.ProjectFilesOwner
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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

    /**
     * 配置版本（v1.3）：agent 经 self_config_update 自改配置时自增。VM 据此在空闲时
     * 调 [rebuildRuntimeIfNeeded] 让变更落地（run 内重建会换掉运行中的 coordinator）。
     */
    val configVersion: StateFlow<Int>

    /** 落地待重建的配置变更（no-op 当无变更）；调用方保证当前无运行中的 run。 */
    fun rebuildRuntimeIfNeeded()

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
    /** Must be the same instance the AppContainer wired into the executor — a private copy
     * would leave every permission request hanging until the router timeout. */
    val permissionCoordinator: PermissionCoordinator,
    /** 项目注册表（v1.3）：会话归属 + 沙箱目录；UI 与 agent 共用同一实例。 */
    private val projectStore: ProjectStore,
    /** OneDrive 接缝（v1.4）：账号、云端文件夹浏览、镜像同步。 */
    private val oneDrive: OneDriveOwner,
    /** 项目文件接缝（v1.5）：只读浏览/预览 + 会话改动记录。 */
    private val projectFiles: ProjectFilesOwner,
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
            modelConfigured = selection != null && selection.model.isNotBlank(),
            projects = projectStore.list(),
            currentProjectId = projectStore.currentId()
        )
    }

    /** HITL 挂起请求（Q1）；非空时 UI 显示审批对话框。 */
    val approval = approvalCoordinator.pending

    /** 挂起的运行时权限请求（v1.2）；非空时 UI 触发系统授权弹窗。 */
    val permission = permissionCoordinator.pending

    private var runJob: Job? = null

    init {
        viewModelScope.launch(ioDispatcher) {
            val sessions = sessionsForCurrentProject(repository()?.listSessions().orEmpty())
            _uiState.update { it.copy(sessions = sessions) }
            // 打开最近一个会话；没有会话则停留在"新对话"空态。
            sessions.firstOrNull()?.id?.let { openSession(it) }
        }
        // v1.3：agent 经 self_config_update 自改配置 → 空闲时落地重建（run 内不重建，
        // 否则会换掉运行中的 coordinator；变更对下一轮 run 生效，工具结果里已声明）。
        viewModelScope.launch(ioDispatcher) {
            owner.configVersion.drop(1).collect {
                if (!_uiState.value.running) {
                    owner.rebuildRuntimeIfNeeded()
                    refreshSelection()
                }
            }
        }
        // 项目/当前项目变更（UI 或 agent）→ 会话过滤条件变了，刷新列表。
        viewModelScope.launch(ioDispatcher) {
            projectStore.changes.drop(1).collect { refreshProjectsAndSessions() }
        }
        // OneDrive（v1.4）：启动时恢复已登录账号，并把账号/同步状态透出给 UI。
        viewModelScope.launch(ioDispatcher) {
            oneDrive.restore()
            _uiState.update {
                it.copy(oneDriveAccount = oneDrive.account.value, oneDriveConfigured = oneDrive.configured)
            }
            // 已绑定的当前项目：进入即懒惰拉取一次（带节流，冷启动必然超过间隔）。
            oneDrive.sync(_uiState.value.currentProjectId, throttled = true)
        }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.account.collect { acc ->
                _uiState.update { it.copy(oneDriveAccount = acc, oneDriveConfigured = oneDrive.configured) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.syncState.collect { state -> _uiState.update { it.copy(syncState = state) } }
        }
    }

    // ----- 会话管理 -----

    /** ChatGPT 式新对话：清空当前投影；会话在首条消息发送时才落库（归属当前项目）。 */
    fun startNewChat() {
        runJob?.cancel()
        // 用 copy 而不是重建：只清会话投影，服务商/项目/OneDrive 等横向状态全部保留
        // （重建式的写法会在新增状态字段时静默丢字段）。
        _uiState.update { current ->
            current.copy(
                currentSessionId = null,
                currentTitle = null,
                messages = emptyList(),
                running = false,
                iteration = 0,
                runError = null,
                todos = emptyList(),
                // 新增文件卡片属于"这个会话产出了什么"，换会话就没有了。
                newFiles = emptyList()
            )
        }
    }

    // ----- 项目（v1.3）-----

    /** 切换当前项目：会话列表过滤 + 新会话归属 + fs_* 工具默认目录随之改变。运行中拒绝。 */
    fun switchProject(projectId: String) {
        if (_uiState.value.running) {
            _uiState.update { it.copy(runError = "正在运行中，请先停止当前任务再切换项目") }
            return
        }
        if (!projectStore.switchTo(projectId)) {
            _uiState.update { it.copy(runError = "未知项目：$projectId") }
            return
        }
        // switchTo 触发 projectStore.changes → refreshProjectsAndSessions；
        // 这里把对话投影切回"新对话"空态（旧项目的会话不再属于当前视图）。
        startNewChat()
        // 文件浏览器指向上一个项目的目录了，回落根目录（下次打开重新列）。
        _uiState.update { it.copy(filePath = "", fileEntries = emptyList(), filesError = null, filePreview = null) }
        // 进入项目即懒惰拉取一次（节流在 owner 侧；10 分钟内不重复打网络）。
        viewModelScope.launch(ioDispatcher) { oneDrive.sync(projectId, throttled = true) }
    }

    /** 新建项目（沙箱目录与项目同生）；不切换当前项目。 */
    fun createProject(name: String) {
        if (_uiState.value.running) return
        viewModelScope.launch(ioDispatcher) {
            projectStore.create(name).fold(
                onSuccess = { refreshProjectsAndSessions() },
                onFailure = { e -> _uiState.update { it.copy(runError = e.message ?: "新建项目失败") } }
            )
        }
    }

    // ----- OneDrive（v1.4）：账号 / 文件夹浏览 / 绑定 / 同步 -----

    /**
     * 交互式登录（[activity] 由 UI 从 LocalActivity 传入，MSAL 用它拉起授权页）。
     * 允许 null 并显式报错：静默不响应点击比报错更难排查（P2）。
     */
    fun connectOneDrive(activity: Activity?) {
        if (activity == null) {
            _uiState.update { it.copy(cloudError = "无法获取当前 Activity，请重新打开页面后再试") }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.connect(activity).onFailure { e ->
                _uiState.update { it.copy(cloudError = e.message ?: "OneDrive 登录失败") }
            }
        }
    }

    /** 登出；只清账号，不动任何绑定与本地文件。 */
    fun disconnectOneDrive() {
        viewModelScope.launch(ioDispatcher) { oneDrive.disconnect() }
    }

    /** 打开/下钻云端文件夹选择器。[parentId] = "root" 表示网盘根。 */
    fun browseCloudFolders(parentId: String, parentPath: String) {
        _uiState.update { it.copy(cloudBusy = true, cloudError = null) }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.listFolders(parentId, parentPath).fold(
                onSuccess = { folders -> _uiState.update { it.copy(cloudFolders = folders, cloudBusy = false) } },
                onFailure = { e ->
                    _uiState.update { it.copy(cloudBusy = false, cloudError = e.message ?: "无法读取云端文件夹") }
                }
            )
        }
    }

    /** 绑定到云端文件夹：owner 侧先做一次首轮对齐（空项目拉取 / 有内容则上传）。 */
    fun bindCloudFolder(projectId: String, folder: CloudFolder) {
        // 重试时先清掉上一条错误，否则旧提示会和新结果并排显示。
        _uiState.update { it.copy(cloudError = null) }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.bindFolder(projectId, folder).onFailure { e ->
                _uiState.update { it.copy(cloudError = e.message ?: "同步失败") }
            }
        }
    }

    /** 在云端新建文件夹并绑定。 */
    fun createCloudFolderAndBind(projectId: String, parentId: String, parentPath: String, name: String) {
        _uiState.update { it.copy(cloudError = null) }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.createAndBindFolder(projectId, parentId, parentPath, name).onFailure { e ->
                _uiState.update { it.copy(cloudError = e.message ?: "新建文件夹失败") }
            }
        }
    }

    fun unbindCloud(projectId: String) {
        viewModelScope.launch(ioDispatcher) {
            oneDrive.unbind(projectId).onFailure { e ->
                _uiState.update { it.copy(cloudError = e.message ?: "取消关联失败") }
            }
        }
    }

    /** 用户显式"立即同步"：跳过节流。运行中拒绝（同步会与 run 争抢同一份工作区）。 */
    fun syncCloudNow(projectId: String) {
        if (_uiState.value.running) {
            _uiState.update { it.copy(cloudError = "正在运行中，请先停止当前任务再同步") }
            return
        }
        viewModelScope.launch(ioDispatcher) { oneDrive.sync(projectId, throttled = false) }
    }

    fun dismissCloudError() {
        _uiState.update { it.copy(cloudError = null) }
    }

    // ----- 项目文件（v1.5）：只读浏览 / 预览 -----

    /** 打开文件管理器：回到项目根目录。 */
    fun openProjectFiles() {
        loadFileDir("")
    }

    /** 进入子目录（[path] 相对项目根）。 */
    fun openFileDir(path: String) {
        loadFileDir(path)
    }

    /** 返回上一层（根目录上是 no-op；UI 在根目录不渲染返回键）。 */
    fun fileDirUp() {
        val current = _uiState.value.filePath
        if (current.isEmpty()) return
        loadFileDir(current.substringBeforeLast('/', ""))
    }

    /**
     * 预览文件。会话底部的「新增文件」卡片与文件管理器共用这一个入口 —— 从哪儿进来
     * 都是同一个页面、同一份内容，不存在两套预览逻辑。
     *
     * 二进制靠内容里的 NUL 字节判定（比扩展名白名单可靠）：不渲染乱码，直说类型不支持。
     */
    fun openFilePreview(path: String) {
        val projectId = _uiState.value.currentProjectId
        viewModelScope.launch(ioDispatcher) {
            val preview = projectFiles.read(projectId, path).fold(
                onSuccess = { content ->
                    val binary = content.content.contains('\u0000')
                    FilePreview(
                        path = path,
                        markdown = !binary && FileKinds.isMarkdown(path),
                        text = content.content.takeIf { !binary },
                        notice = when {
                            binary -> "无法预览此类型"
                            content.truncated -> "文件较大，仅显示开头部分"
                            else -> null
                        },
                        truncated = content.truncated,
                        sizeBytes = content.sizeBytes
                    )
                },
                onFailure = { FilePreview(path = path, notice = "无法读取该文件") }
            )
            _uiState.update { it.copy(filePreview = preview) }
        }
    }

    fun closeFilePreview() {
        _uiState.update { it.copy(filePreview = null) }
    }

    fun dismissFilesError() {
        _uiState.update { it.copy(filesError = null) }
    }

    private fun loadFileDir(path: String) {
        val projectId = _uiState.value.currentProjectId
        _uiState.update { it.copy(filePath = path, filesBusy = true, filesError = null) }
        viewModelScope.launch(ioDispatcher) {
            projectFiles.list(projectId, path).fold(
                onSuccess = { listing ->
                    _uiState.update { it.copy(fileEntries = listing.entries, filesBusy = false) }
                },
                onFailure = {
                    // 失败原因（路径逃逸 / 非目录）对用户没有操作价值，只报"读不到"，
                    // 目录树留空 —— 比留一屏过期条目诚实。
                    _uiState.update {
                        it.copy(fileEntries = emptyList(), filesBusy = false, filesError = "无法读取该目录")
                    }
                }
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

    /** 抽屉打开时的会话列表刷新入口（只列当前项目的会话）。 */
    fun refreshSessions() {
        viewModelScope.launch(ioDispatcher) {
            refreshProjectsAndSessions()
        }
    }

    private suspend fun refreshProjectsAndSessions() {
        val all = repository()?.listSessions().orEmpty()
        _uiState.update {
            val filtered = sessionsFor(all, it.currentProjectId)
            it.copy(
                projects = projectStore.list(),
                currentProjectId = projectStore.currentId(),
                sessions = filtered
            )
        }
    }

    private fun sessionsForCurrentProject(all: List<SessionRecord>): List<SessionRecord> =
        sessionsFor(all, _uiState.value.currentProjectId)

    /** pre-v1.3 行（projectId=null）按默认项目归属渲染，绝不悬空。 */
    private fun sessionsFor(all: List<SessionRecord>, projectId: String): List<SessionRecord> =
        all.filter { (it.projectId ?: ProjectLogic.DEFAULT_PROJECT_ID) == projectId }

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

    /** 系统授权弹窗结果回灌（v1.2）。执行器仍会复查权限，这里只报告"用户做了什么选择"。 */
    fun respondPermission(granted: Boolean) {
        permissionCoordinator.respond(granted)
    }

    // ----- 服务商 / 模型选择 -----

    /** 配置外部变更（agent 自改）后的选择态同步。 */
    private fun refreshSelection() {
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
        val record = repository()?.createSession(title, _uiState.value.currentProjectId)
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
                // agent 可能在 run 内改过配置：落地待重建的变更（下一轮 run 生效）。
                owner.rebuildRuntimeIfNeeded()
                refreshSelection()
                _uiState.update { it.copy(running = false) }
                refreshConversation()
                refreshTodos()
                refreshSessions()
                // 懒惰推送：本轮可能写过工作区，把产出送上云端。只推不拉；无改动时零网络。
                oneDrive.pushAfterRun(_uiState.value.currentProjectId)
            }

            is AgentStepEvent.Failed -> {
                owner.rebuildRuntimeIfNeeded()
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
        // 改动记录与节点同源：投影会话时一并把 diff 贴回对应工具条目（v1.5）。
        val changes = withContext(ioDispatcher) { projectFiles.changes(sessionId) }
        val messages = ConversationRenderer.render(path, allNodes, changes) { node -> repository.payload(node) }
        val title = withContext(ioDispatcher) { repository.getSession(sessionId)?.title }
        _uiState.update {
            it.copy(messages = messages, currentTitle = title ?: it.currentTitle, newFiles = newFilesOf(changes))
        }
    }

    /**
     * 底部「新增文件」卡片的数据：本会话新建、且**此刻仍然存在**的文件，同一路径重复创建
     * 只留最新一笔。被删掉/移走的文件不留在卡片里 —— 点进去是 404 的入口不如没有。
     */
    private fun newFilesOf(changes: List<FileChange>): List<FileChange> {
        if (changes.isEmpty()) return emptyList()
        val gone = changes
            .filter { it.kind == FileChangeKind.Deleted || it.kind == FileChangeKind.Moved }
            .flatMap { listOfNotNull(it.path, it.toPath) }
            .toSet()
        return changes.asReversed()
            .filter { it.kind == FileChangeKind.Created && it.path !in gone }
            .distinctBy { it.path }
    }

    private suspend fun refreshTodos() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val state = withContext(ioDispatcher) { repository()?.loadState(sessionId) } ?: return
        _uiState.update { it.copy(todos = state.todos) }
    }
}