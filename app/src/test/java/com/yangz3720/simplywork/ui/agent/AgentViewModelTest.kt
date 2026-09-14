package com.yangz3720.simplywork.ui.agent

import android.app.Activity
import android.content.SharedPreferences
import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.agent.tool.AgentSelfConfig
import com.yangz3720.simplywork.core.agent.tool.SelfOpsTools
import com.yangz3720.simplywork.core.agent.ContextBudget
import com.yangz3720.simplywork.core.agent.ContextBudgetPlan
import com.yangz3720.simplywork.core.cloud.CloudFolder
import com.yangz3720.simplywork.core.cloud.OneDriveAccount
import com.yangz3720.simplywork.core.cloud.OneDriveOwner
import com.yangz3720.simplywork.core.cloud.SyncReport
import com.yangz3720.simplywork.core.cloud.SyncState
import com.yangz3720.simplywork.core.session.ProjectLogic
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.testsupport.InMemoryPrefs
import com.yangz3720.simplywork.core.session.TodoStatus
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.core.workspace.FileChangeKind
import com.yangz3720.simplywork.core.workspace.FileChangeLog
import com.yangz3720.simplywork.core.workspace.InMemoryFileChangeLog
import com.yangz3720.simplywork.core.workspace.LocalProjectFiles
import com.yangz3720.simplywork.core.workspace.ProjectFilesOwner
import com.yangz3720.simplywork.infrastructure.model.ModelProvider
import com.yangz3720.simplywork.infrastructure.model.config.ModelCapability
import com.yangz3720.simplywork.infrastructure.model.config.ModelProviderPresets
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import com.yangz3720.simplywork.infrastructure.model.model.FinishReason
import com.yangz3720.simplywork.infrastructure.model.model.Message
import com.yangz3720.simplywork.infrastructure.model.model.ModelRequest
import com.yangz3720.simplywork.infrastructure.model.model.ModelResponse
import com.yangz3720.simplywork.infrastructure.model.model.TokenUsage
import com.yangz3720.simplywork.infrastructure.model.model.ToolCall
import com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouter
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * AgentViewModel（MVI）行为测试：运行驱动 / 运行中转向 / 失败重试（Q2）/ 停止 / 多会话。
 * 全部走真实 AgentRuntime 装配（in-memory stores + scripted provider）。
 *
 * 说明：ViewModel 默认 ioDispatcher = Dispatchers.IO（真实线程），运行完成以状态轮询
 * 等待（不进虚拟时间，避免 withTimeout 被 advanceUntilIdle 提前触发）。
 */
class AgentViewModelTest {

    /** Main dispatcher → 空转处理（viewModelScope 需要 Main 存在即可）。 */
    class MainTestRule : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(Dispatchers.Unconfined)
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    @get:org.junit.Rule
    val mainRule = MainTestRule()

    @get:Rule
    val tmp = TemporaryFolder()

    // ----- fakes -----

    private fun fakeProjects(): ProjectStore = ProjectStore(InMemoryPrefs())

    private class FakeOwner(var runtime: AgentRuntime?) : AgentRuntimeOwner {
        val accounts = ModelProviderPresets.ALL.toMutableList()
        var selection: ModelSelection? = null
        var autoApprove: Boolean = true
        var contextOverride: Int? = null
        var providerContextTokens: Int? = null
        var savedKey: Pair<String, String>? = null
        var appliedSelection: ModelSelection? = null
        var fetchedAccount: ProviderAccount? = null
        var rebuilds: Int = 0
        override val configVersion = MutableStateFlow(0)

        override fun current(): AgentRuntime? = runtime
        override fun accounts(): List<ProviderAccount> = accounts
        override fun selection(): ModelSelection? = selection
        override fun rebuildRuntimeIfNeeded() { rebuilds += 1 }
        override fun saveAccountKey(accountId: String, apiKey: String) { savedKey = accountId to apiKey }
        override fun upsertCustomAccount(name: String, baseUrl: String, apiKey: String) =
            ProviderAccount(id = "custom-x", name = name, baseUrl = baseUrl, apiKey = apiKey)
        override fun removeCustomAccount(accountId: String) { /* no-op */ }
        override fun autoApproveNonDestructive(): Boolean = autoApprove
        override fun setAutoApproveNonDestructive(enabled: Boolean) { autoApprove = enabled }
        override fun contextBudget(): ContextBudgetPlan =
            ContextBudget.resolve(contextOverride, providerContextTokens, "test-model")
        override fun setContextBudgetOverride(tokens: Int?) { contextOverride = tokens }
        override fun applySelection(providerId: String, model: String) {
            appliedSelection = ModelSelection(providerId, model)
            selection = appliedSelection
        }
        override suspend fun fetchModels(account: ProviderAccount): Result<List<String>> {
            fetchedAccount = account
            return Result.success(listOf("m1", "m2"))
        }
    }

    /**
     * OneDrive 接缝的哑实现：本文件的用例覆盖的是运行驱动/会话/模型选择，不覆盖云同步，
     * 所以这里只需保证 VM 装配不被打断，并记录 push 是否按项目触发过。
     */
    private class FakeOneDrive : OneDriveOwner {
        override val account = MutableStateFlow<OneDriveAccount?>(null)
        override val syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        override val configured = false
        var pushedFor: String? = null
        var syncedFor: String? = null

        override suspend fun restore() = Unit
        override suspend fun connect(activity: Activity): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect(): Result<Unit> = Result.success(Unit)
        override suspend fun listFolders(parentId: String, parentPath: String): Result<List<CloudFolder>> =
            Result.success(emptyList())

        override suspend fun bindFolder(projectId: String, folder: CloudFolder): Result<SyncReport> =
            Result.failure(UnsupportedOperationException("本用例不覆盖云端绑定"))

        override suspend fun createAndBindFolder(
            projectId: String,
            parentId: String,
            parentPath: String,
            name: String
        ): Result<SyncReport> = Result.failure(UnsupportedOperationException("本用例不覆盖云端绑定"))

        override suspend fun unbind(projectId: String): Result<Unit> = Result.success(Unit)

        override suspend fun sync(projectId: String, throttled: Boolean): Result<SyncReport?> {
            syncedFor = projectId
            return Result.success(null)
        }

        override suspend fun pushAfterRun(projectId: String): Result<SyncReport?> {
            pushedFor = projectId
            return Result.success(null)
        }
    }

    /** self_* 配置缝的哑实现：本文件只借它注册 fs_* 工具，不覆盖配置自改。 */
    private class FakeSelfConfig : AgentSelfConfig {
        override fun snapshot() = AgentSelfConfig.Snapshot(
            activeProviderId = null,
            activeModel = "",
            timeoutMs = 60_000L,
            accounts = emptyList(),
            projects = emptyList(),
            currentProjectId = ProjectLogic.DEFAULT_PROJECT_ID
        )

        override suspend fun apply(mutation: AgentSelfConfig.Mutation) =
            AgentSelfConfig.ApplyResult(applied = emptyList(), rebuildPending = false, errors = emptyList())
    }

    /** 首轮返回 [pre] 各一次；其后等待 [gate]，释放后返回 [final]（仅一次），再挂起。 */
    private class SteerableProvider(
        pre: List<ModelResponse>,
        private val final: ModelResponse
    ) : ModelProvider {
        private val queue = ArrayDeque(pre)
        val gate = CompletableDeferred<Unit>()
        override val providerId = "steerable"
        override val supportedCapabilities = emptySet<ModelCapability>()
        override suspend fun generate(request: ModelRequest): Result<ModelResponse> {
            queue.removeFirstOrNull()?.let { return Result.success(it) }
            gate.await()
            return Result.success(final)
        }
        override fun generateStream(request: ModelRequest) = throw UnsupportedOperationException()
    }

    /** 第一次返回失败，其后返回 [after]。 */
    private class FailingOnceProvider(private val after: ModelResponse) : ModelProvider {
        private val first = java.util.concurrent.atomic.AtomicBoolean(true)
        override val providerId = "failing"
        override val supportedCapabilities = emptySet<ModelCapability>()
        override suspend fun generate(request: ModelRequest): Result<ModelResponse> =
            if (first.getAndSet(false)) Result.failure(RuntimeException("rate limited"))
            else Result.success(after)
        override fun generateStream(request: ModelRequest) = throw UnsupportedOperationException()
    }

    /** 一直挂起（停止语义测试）。 */
    private class HangingProvider : ModelProvider {
        val blocker = CompletableDeferred<Unit>()
        override val providerId = "hanging"
        override val supportedCapabilities = emptySet<ModelCapability>()
        override suspend fun generate(request: ModelRequest): Result<ModelResponse> {
            blocker.await()
            return Result.success(
                ModelResponse(
                    id = "resp-never", model = "hanging",
                    message = Message.Assistant(content = "never"),
                    finishReason = FinishReason.STOP,
                    usage = TokenUsage(1, 1)
                )
            )
        }
        override fun generateStream(request: ModelRequest) = throw UnsupportedOperationException()
    }

    /** 固定脚本：按序返回。 */
    private class ScriptedProvider(vararg responses: ModelResponse) : ModelProvider {
        val queue = ArrayDeque(responses.toList())
        override val providerId = "scripted"
        override val supportedCapabilities = emptySet<ModelCapability>()
        override suspend fun generate(request: ModelRequest) = Result.success(queue.removeFirst())
        override fun generateStream(request: ModelRequest) = throw UnsupportedOperationException()
    }

    // ----- helpers -----

    private fun response(text: String, toolCalls: List<ToolCall> = emptyList()) = ModelResponse(
        id = "resp-${System.nanoTime()}",
        model = "scripted",
        message = Message.Assistant(content = text.ifBlank { null }, toolCalls = toolCalls),
        finishReason = if (toolCalls.isEmpty()) FinishReason.STOP else FinishReason.TOOL_CALLS,
        usage = TokenUsage(promptTokens = 10, completionTokens = 7)
    )

    private fun todoDoneCall(id: String) = ToolCall(
        id = id, name = "todo_write",
        argumentsJson = """{"items":[{"title":"check mailbox","status":"${TodoStatus.done.name}"}]}"""
    )

    private fun buildRuntime(provider: ModelProvider): AgentRuntime = AgentRuntime.createWithStores(
        structureStore = InMemorySessionStore(),
        payloadStore = InMemoryPayloadStore(),
        provider = provider,
        router = mockk<SystemOperationRouter>(relaxed = true)
    )

    /** 项目文件接缝：真实实现 + 临时目录（不 mock —— 目录遍历与路径安全本来就该一起验）。 */
    private fun workspace(): AgentWorkspace = AgentWorkspace(File(tmp.root, "ws"))

    private fun files(changes: FileChangeLog = InMemoryFileChangeLog()): ProjectFilesOwner =
        LocalProjectFiles(workspace(), changes)

    private fun viewModel(
        runtime: AgentRuntime,
        projectFiles: ProjectFilesOwner = files()
    ): AgentViewModel = AgentViewModel(
        FakeOwner(runtime),
        ApprovalCoordinator(),
        PermissionCoordinator(),
        fakeProjects(),
        FakeOneDrive(),
        projectFiles
    )

    /** 真实时间轮询：等待谓词成立或超时。 */
    private fun waitFor(timeoutMs: Long = 8000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for state")
            Thread.sleep(25)
        }
    }

    // ----- 用例 -----

    @Test
    fun `send creates session, runs to completion and renders messages`() {
        val vm = viewModel(buildRuntime(ScriptedProvider(response("all done"))))

        Thread.sleep(100) // init：无会话
        assertTrue(vm.uiState.value.sessions.isEmpty())

        vm.send("你好")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }

        val state = vm.uiState.value
        assertNotNull(state.currentSessionId)
        assertEquals(UiMessageKind.User, state.messages[0].kind)
        assertEquals("你好", state.messages[0].text)
        assertEquals(UiMessageKind.Assistant, state.messages[1].kind)
        assertEquals("all done", state.messages[1].text)
        assertNull(state.runError)
    }

    @Test
    fun `mid-run steering is drained as steer messages`() {
        val provider = SteerableProvider(
            pre = listOf(response("", toolCalls = listOf(todoDoneCall("c1")))),
            final = response("adjusted final")
        )
        val vm = viewModel(buildRuntime(provider))

        vm.send("start")
        waitFor { vm.uiState.value.running }
        // 等第一轮工具往返落库（此后 loop 挂在 generate #2 的 gate 上）
        waitFor { vm.uiState.value.messages.any { it.kind == UiMessageKind.ToolActivity } }

        // 运行中输入 → 转向入队（loop 正挂在 gate 上）
        vm.send("换个方向")
        vm.send("再优化一下")

        provider.gate.complete(Unit)
        waitFor { !vm.uiState.value.running }

        val kinds = vm.uiState.value.messages.map { it.kind }
        // 队列非空优先于完成守卫（流程图 K→L）："adjusted final" 先落一轮，转向被
        // 下一轮 drain 后才真正闭环。
        assertEquals(
            listOf(UiMessageKind.User, UiMessageKind.ToolActivity, UiMessageKind.Assistant, UiMessageKind.Steer, UiMessageKind.Steer, UiMessageKind.Assistant),
            kinds
        )
        val steerTexts = vm.uiState.value.messages.filter { it.kind == UiMessageKind.Steer }.map { it.text }
        assertEquals(listOf("换个方向", "再优化一下"), steerTexts)
    }

    @Test
    fun `failed run surfaces error and retry resumes loop-outer`() {
        val vm = viewModel(buildRuntime(FailingOnceProvider(response("recovered!"))))

        vm.send("hi")
        waitFor { vm.uiState.value.runError != null }
        assertEquals("rate limited", vm.uiState.value.runError)
        assertFalse(vm.uiState.value.running)
        // 失败轮没有任何 assistant 节点落库
        assertEquals(1, vm.uiState.value.messages.size)

        vm.retry()
        waitFor { !vm.uiState.value.running && vm.uiState.value.runError == null }
        val state = vm.uiState.value
        assertEquals(2, state.messages.size)
        assertEquals(UiMessageKind.Assistant, state.messages[1].kind)
        assertEquals("recovered!", state.messages[1].text)
    }

    @Test
    fun `stopRun cancels the running loop`() {
        val vm = viewModel(buildRuntime(HangingProvider()))

        vm.send("hi")
        waitFor { vm.uiState.value.running }
        // 等用户消息落库后再停（LoopStarted 早于落库发出）
        waitFor { vm.uiState.value.messages.size == 1 }

        vm.stopRun()
        waitFor { !vm.uiState.value.running }
        // 用户消息已落库，保留在投影里
        assertEquals(1, vm.uiState.value.messages.size)
    }

    @Test
    fun `startNewChat resets conversation and defers session creation`() {
        val vm = viewModel(buildRuntime(ScriptedProvider(response("first done"), response("second done"))))

        vm.send("first")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }
        val firstSession = vm.uiState.value.currentSessionId
        assertNotNull(firstSession)

        vm.startNewChat()
        assertNull(vm.uiState.value.currentSessionId)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertEquals(1, vm.uiState.value.sessions.size) // 历史仍在列表里

        vm.send("second")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }
        assertNotNull(vm.uiState.value.currentSessionId)
        assertTrue(vm.uiState.value.currentSessionId != firstSession)
    }

    @Test
    fun `selectModel applies selection through owner and updates state`() {
        val owner = FakeOwner(null)
        val vm = AgentViewModel(owner, ApprovalCoordinator(), PermissionCoordinator(), fakeProjects(), FakeOneDrive(), files())
        Thread.sleep(100) // init 完成后状态稳定

        vm.selectModel("deepseek", "  deepseek-chat  ")
        assertEquals(ModelSelection("deepseek", "deepseek-chat"), owner.appliedSelection)
        assertEquals("deepseek", vm.uiState.value.activeProviderId)
        assertEquals("deepseek-chat", vm.uiState.value.activeModel)
        assertTrue(vm.uiState.value.modelConfigured)
    }

    @Test
    fun `selectModel is rejected while running`() {
        val owner = FakeOwner(buildRuntime(HangingProvider()))
        val vm = AgentViewModel(owner, ApprovalCoordinator(), PermissionCoordinator(), fakeProjects(), FakeOneDrive(), files())

        vm.send("hi")
        waitFor { vm.uiState.value.running }
        vm.selectModel("deepseek", "deepseek-chat")

        assertNull(owner.appliedSelection)
        assertNotNull(vm.uiState.value.runError)
        vm.stopRun()
    }

    @Test
    fun `fetchModels delegates to owner with account`() = kotlinx.coroutines.runBlocking {
        val owner = FakeOwner(null)
        val vm = AgentViewModel(owner, ApprovalCoordinator(), PermissionCoordinator(), fakeProjects(), FakeOneDrive(), files())
        val account = ProviderAccount(id = "openrouter", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1")

        val result = vm.fetchModels(account)

        assertEquals(account, owner.fetchedAccount)
        assertEquals(listOf("m1", "m2"), result.getOrThrow())
    }

    // ----- 编辑重发（分支语义） -----

    @Test
    fun `editAndResend opens a new branch and reruns without enqueueing`() {
        val vm = viewModel(buildRuntime(ScriptedProvider(response("first answer"), response("second answer"))))

        vm.send("原提问")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }
        val originalUser = vm.uiState.value.messages.first { it.kind == UiMessageKind.User }

        vm.editAndResend(originalUser.id, "编辑后的提问")
        waitFor { !vm.uiState.value.running }
        waitFor { vm.uiState.value.messages.any { it.kind == UiMessageKind.User && it.text == "编辑后的提问" } }

        val state = vm.uiState.value
        // 活跃路径 = 新提问 + 新回答（旧分支保留但不渲染）
        val user = state.messages.first { it.kind == UiMessageKind.User }
        assertEquals("编辑后的提问", user.text)
        assertEquals("second answer", state.messages.last().text)
        // 分支信息：同位置 2 个分支，当前第 2 个
        assertEquals(2, user.branchCount)
        assertEquals(1, user.branchIndex)
    }

    @Test
    fun `switchBranch returns to the original branch`() {
        val vm = viewModel(buildRuntime(ScriptedProvider(response("first answer"), response("second answer"))))

        vm.send("原提问")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }

        vm.editAndResend(
            vm.uiState.value.messages.first { it.kind == UiMessageKind.User }.id,
            "编辑后的提问"
        )
        waitFor { !vm.uiState.value.running }
        waitFor { vm.uiState.value.messages.first { it.kind == UiMessageKind.User }.text == "编辑后的提问" }

        // 从新分支 ‹ 切回旧分支：提问与回答都恢复为旧分支内容
        val editedUser = vm.uiState.value.messages.first { it.kind == UiMessageKind.User }
        vm.switchBranch(editedUser.id, -1)
        waitFor { vm.uiState.value.messages.first { it.kind == UiMessageKind.User }.text == "原提问" }

        val state = vm.uiState.value
        assertEquals("first answer", state.messages.last().text)
        assertEquals(0, state.messages.first { it.kind == UiMessageKind.User }.branchIndex)
        assertTrue(!state.running)
    }

    // ----- 项目文件与改动卡片（v1.5） -----

    /** 内容进 JSON 字符串：换行必须转义，否则 argumentsJson 根本解析不了。 */
    private fun jsonString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun fsWriteCall(id: String, path: String, content: String) = ToolCall(
        id = id,
        name = "fs_write",
        argumentsJson = """{"path":"$path","content":${jsonString(content)}}"""
    )

    @Test
    fun `agent fs_write lands a diff card and the new files card`() {
        val ws = workspace()
        val log = InMemoryFileChangeLog()
        val projects = fakeProjects()
        val runtime = AgentRuntime.createWithStores(
            structureStore = InMemorySessionStore(),
            payloadStore = InMemoryPayloadStore(),
            provider = ScriptedProvider(
                response("", toolCalls = listOf(fsWriteCall("c1", "docs/notes.md", "# 标题\n正文"))),
                response("写好了")
            ),
            router = mockk<SystemOperationRouter>(relaxed = true),
            selfOps = { repo -> SelfOpsTools.registeredTools(repo, FakeSelfConfig(), ws, projects, log) }
        )
        val vm = AgentViewModel(
            FakeOwner(runtime),
            ApprovalCoordinator(),
            PermissionCoordinator(),
            projects,
            FakeOneDrive(),
            LocalProjectFiles(ws, log)
        )

        vm.send("写一份笔记")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.any { it.fileChange != null } }

        // 工具真的写了文件（卡片是事实的投影，不是并行的另一份真相）
        assertEquals("# 标题\n正文", ws.read(ProjectLogic.DEFAULT_PROJECT_ID, "docs/notes.md").content)

        val card = vm.uiState.value.messages.first { it.fileChange != null }.fileChange!!
        assertEquals("docs/notes.md", card.path)
        assertEquals(FileChangeKind.Created, card.kind)
        assertEquals("c1", card.toolCallId)
        assertEquals(2, card.addedLines)
        assertEquals(0, card.removedLines)
        assertEquals(listOf('+', '+'), card.diff.map { it.prefix })

        // 底部「新增文件」小卡片
        assertEquals(listOf("docs/notes.md"), vm.uiState.value.newFiles.map { it.path })
    }

    @Test
    fun `new files card drops a file that a later change removed`() {
        val log = InMemoryFileChangeLog()
        val vm = viewModel(buildRuntime(ScriptedProvider(response("first"), response("second"))), files(log))

        vm.send("hi")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }
        val sessionId = vm.uiState.value.currentSessionId!!

        runBlocking {
            log.record(change(sessionId, "docs/a.md", FileChangeKind.Created, 1))
            log.record(change(sessionId, "tmp.md", FileChangeKind.Created, 2))
            log.record(change(sessionId, "tmp.md", FileChangeKind.Deleted, 3))
        }

        // 再跑一轮：会话投影顺带刷新改动卡片
        vm.send("again")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 4 }

        assertEquals(listOf("docs/a.md"), vm.uiState.value.newFiles.map { it.path })
    }

    private fun change(sessionId: String, path: String, kind: FileChangeKind, atMs: Long) = FileChange(
        sessionId = sessionId,
        projectId = ProjectLogic.DEFAULT_PROJECT_ID,
        path = path,
        kind = kind,
        atMs = atMs
    )

    @Test
    fun `file browser enters directories and walks back up`() {
        val ws = workspace()
        ws.write(ProjectLogic.DEFAULT_PROJECT_ID, "docs/notes.md", "# hi")
        ws.write(ProjectLogic.DEFAULT_PROJECT_ID, "todo.txt", "x")
        val vm = viewModel(buildRuntime(ScriptedProvider(response("noop"))))

        vm.openProjectFiles()
        waitFor { !vm.uiState.value.filesBusy && vm.uiState.value.fileEntries.isNotEmpty() }
        // 目录在前，其余按名字
        assertEquals(listOf("docs", "todo.txt"), vm.uiState.value.fileEntries.map { it.name })
        assertTrue(vm.uiState.value.fileEntries.first().isDirectory)

        vm.openFileDir("docs")
        waitFor { vm.uiState.value.filePath == "docs" && !vm.uiState.value.filesBusy }
        assertEquals(listOf("notes.md"), vm.uiState.value.fileEntries.map { it.name })

        vm.fileDirUp()
        waitFor { vm.uiState.value.filePath.isEmpty() && !vm.uiState.value.filesBusy }
        assertEquals(2, vm.uiState.value.fileEntries.size)
    }

    @Test
    fun `preview renders markdown and refuses to render binary`() {
        val ws = workspace()
        ws.write(ProjectLogic.DEFAULT_PROJECT_ID, "notes.md", "# 标题")
        ws.writeBytes(ProjectLogic.DEFAULT_PROJECT_ID, "logo.png", byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x01))
        val vm = viewModel(buildRuntime(ScriptedProvider(response("noop"))))

        vm.openFilePreview("notes.md")
        waitFor { vm.uiState.value.filePreview != null }
        val markdown = vm.uiState.value.filePreview!!
        assertTrue(markdown.markdown)
        assertEquals("# 标题", markdown.text)
        assertNull(markdown.notice)

        vm.openFilePreview("logo.png")
        waitFor { vm.uiState.value.filePreview?.path == "logo.png" }
        val binary = vm.uiState.value.filePreview!!
        assertFalse(binary.markdown)
        assertNull(binary.text)
        assertEquals("无法预览此类型", binary.notice)

        vm.closeFilePreview()
        assertNull(vm.uiState.value.filePreview)
    }

    // ----- 删除会话 / 删除项目（不可撤销，必须删干净） -----

    @Test
    fun `deleteSession clears the projection and the stored session`() {
        val runtime = buildRuntime(ScriptedProvider(response("all done")))
        val vm = viewModel(runtime)

        vm.send("你好")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }
        val sessionId = vm.uiState.value.currentSessionId!!

        vm.deleteSession(sessionId)

        waitFor { vm.uiState.value.sessions.isEmpty() }
        // 删的是当前会话 → 回到"新对话"空态，而不是停在一个已经不存在的会话上
        assertNull(vm.uiState.value.currentSessionId)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertNull(runtime.repository.getSession(sessionId))
    }

    @Test
    fun `deleteSession leaves the other sessions alone`() {
        val runtime = buildRuntime(ScriptedProvider(response("ok")))
        val vm = viewModel(runtime)

        vm.send("第一条")
        waitFor { !vm.uiState.value.running && vm.uiState.value.currentSessionId != null }
        val first = vm.uiState.value.currentSessionId!!

        vm.startNewChat()
        vm.send("第二条")
        waitFor { !vm.uiState.value.running && vm.uiState.value.currentSessionId != null }
        val second = vm.uiState.value.currentSessionId!!
        assertTrue(first != second)

        vm.deleteSession(first)

        waitFor { vm.uiState.value.sessions.none { it.id == first } }
        assertNull(runtime.repository.getSession(first))
        // 当前会话不是被删的那个：投影与指针都不动
        assertEquals(second, vm.uiState.value.currentSessionId)
        assertNotNull(runtime.repository.getSession(second))
    }

    @Test
    fun `deleteProject clears its sessions, sandbox files and returns to the default project`() {
        val runtime = buildRuntime(ScriptedProvider(response("done")))
        val vm = viewModel(runtime)

        vm.createProject("临时项目")
        waitFor { vm.uiState.value.projects.any { it.name == "临时项目" } }
        val projectId = vm.uiState.value.projects.first { it.name == "临时项目" }.id

        vm.switchProject(projectId)
        waitFor { vm.uiState.value.currentProjectId == projectId }

        // 沙箱里放一个文件，确认它随项目一起消失（而不是留成孤儿目录）
        val note = File(tmp.root, "ws/$projectId/note.md")
        note.parentFile?.mkdirs()
        note.writeText("hi")

        vm.send("写点什么")
        waitFor { !vm.uiState.value.running && vm.uiState.value.currentSessionId != null }
        val sessionIds = runtime.repository.listSessions()
            .filter { it.projectId == projectId }
            .map { it.id }
        assertTrue(sessionIds.isNotEmpty())

        vm.deleteProject(projectId)

        waitFor { vm.uiState.value.projects.none { it.id == projectId } }
        assertTrue(runtime.repository.listSessions().none { it.id in sessionIds })
        assertFalse(note.exists())
        assertEquals(ProjectLogic.DEFAULT_PROJECT_ID, vm.uiState.value.currentProjectId)
        assertNull(vm.uiState.value.currentSessionId)
    }

    @Test
    fun `deleteProject refuses the default project before touching anything`() {
        val runtime = buildRuntime(ScriptedProvider(response("done")))
        val vm = viewModel(runtime)

        vm.send("留着")
        waitFor { !vm.uiState.value.running && vm.uiState.value.currentSessionId != null }
        val sessionId = vm.uiState.value.currentSessionId!!

        vm.deleteProject(ProjectLogic.DEFAULT_PROJECT_ID)

        // 拒绝必须发生在动手之前 —— 若只在注册表那一步拦，会话和沙箱会先被清空
        assertEquals("默认项目不可删除", vm.uiState.value.runError)
        assertTrue(vm.uiState.value.projects.any { it.id == ProjectLogic.DEFAULT_PROJECT_ID })
        assertNotNull(runtime.repository.getSession(sessionId))
    }
}