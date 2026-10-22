package com.yangz3720.simplywork.ui.agent

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
import com.yangz3720.simplywork.core.AgentRunSupervisor
import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.attachment.PendingUpload
import com.yangz3720.simplywork.core.attachment.UploadException
import com.yangz3720.simplywork.core.attachment.UploadKind
import com.yangz3720.simplywork.core.attachment.UploadOwner
import com.yangz3720.simplywork.core.agent.tool.AgentSelfConfig
import com.yangz3720.simplywork.core.agent.tool.SelfOpsTools
import com.yangz3720.simplywork.core.agent.ContextBudget
import com.yangz3720.simplywork.core.agent.ContextBudgetPlan
import com.yangz3720.simplywork.core.agent.AgentSoul
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
import com.yangz3720.simplywork.core.workspace.ExternalPreview
import com.yangz3720.simplywork.core.workspace.ExternalPreviewException
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.core.workspace.FileChangeKind
import com.yangz3720.simplywork.core.workspace.FileChangeLog
import com.yangz3720.simplywork.core.workspace.InMemoryFileChangeLog
import com.yangz3720.simplywork.core.workspace.LocalProjectFiles
import com.yangz3720.simplywork.core.workspace.ProjectFilesOwner
import com.yangz3720.simplywork.infrastructure.model.ModelInfo
import com.yangz3720.simplywork.infrastructure.model.ModelProvider
import com.yangz3720.simplywork.infrastructure.model.config.ModelCapability
import com.yangz3720.simplywork.infrastructure.model.config.ModelProviderPresets
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import com.yangz3720.simplywork.infrastructure.model.model.Attachment
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * AgentViewModel (MVI) behavior tests: run driving / mid-run steering / failure retry (Q2) / stop / multiple sessions.
 * Everything goes through a real AgentRuntime wiring (in-memory stores + scripted provider).
 *
 * Note: the ViewModel defaults to ioDispatcher = Dispatchers.IO (real threads), and run completion is
 * awaited by polling state (no virtual time, so that withTimeout is not fired early by advanceUntilIdle).
 */
class AgentViewModelTest {

    /** Main dispatcher -> a no-op handler (viewModelScope only needs Main to exist). */
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
        var visionSupported: Boolean = false
        var rebuilds: Int = 0
        val souls = mutableMapOf<String, String>()
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
        override fun contextBudget(): ContextBudgetPlan? =
            ContextBudget.resolve(contextOverride, providerContextTokens, "test-model")
        override fun setContextBudgetOverride(tokens: Int?) { contextOverride = tokens }
        override fun loadProjectSoul(projectId: String): String = souls[projectId].orEmpty()
        override fun saveProjectSoul(projectId: String, content: String) { souls[projectId] = content }
        override fun deleteProjectSoul(projectId: String) { souls.remove(projectId) }
        override fun applySelection(providerId: String, model: String) {
            appliedSelection = ModelSelection(providerId, model)
            selection = appliedSelection
        }
        override suspend fun fetchModels(account: ProviderAccount): Result<List<ModelInfo>> {
            fetchedAccount = account
            return Result.success(listOf(ModelInfo(id = "m1"), ModelInfo(id = "m2")))
        }
        override fun activeModelSupportsVision(): Boolean = visionSupported
    }

    /**
     * Dummy implementation of the OneDrive seam: what the cases in this file cover is run driving / sessions / model selection, not cloud sync,
     * so here it only needs to keep the VM wiring from being interrupted, and to record whether a push was ever triggered per project.
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

    /**
     * Dummy implementation of the external-preview seam (v1.7): launching an Android intent is not something a JVM test
     * can observe, so what is asserted here is the interesting half -- which project/path we hand over, and what the
     * preview page says when an outside app cannot take the file.
     */
    private class FakeExternalPreview(
        private val failure: ExternalPreviewException? = null
    ) : ExternalPreview {
        var opened: Pair<String, String>? = null

        override suspend fun open(projectId: String, path: String): Result<Unit> {
            opened = projectId to path
            return if (failure == null) Result.success(Unit) else Result.failure(failure)
        }
    }

    /**
     * Upload seam stand-in (v1.8): the interesting half is what the ViewModel does with a loaded upload
     * (staging, sending, clearing), so the fake answers with whatever the case pre-arranged -- success or a
     * rejection with a user-facing reason.
     */
    private class FakeUploads(
        var next: Result<PendingUpload> = Result.failure(UploadException("未安排上传结果"))
    ) : UploadOwner {
        var requested: String? = null

        override suspend fun load(uri: String, name: String?): Result<PendingUpload> {
            requested = uri
            return next
        }
    }

    /** Dummy implementation of the self_* config seam: this file only borrows it to register the fs_* tools, and does not cover self-editing config. */
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

    /** Returns each of [pre] once on the first round; afterwards it waits for [gate], returns [final] (only once) after release, then suspends. */
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
    }

    /** Fails on the first call, returns [after] from then on. */
    private class FailingOnceProvider(private val after: ModelResponse) : ModelProvider {
        private val first = java.util.concurrent.atomic.AtomicBoolean(true)
        override val providerId = "failing"
        override val supportedCapabilities = emptySet<ModelCapability>()
        override suspend fun generate(request: ModelRequest): Result<ModelResponse> =
            if (first.getAndSet(false)) Result.failure(RuntimeException("rate limited"))
            else Result.success(after)
    }

    /** Suspends forever (stop-semantics test). */
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
    }

    /** Fixed script: returns responses in order. */
    private class ScriptedProvider(vararg responses: ModelResponse) : ModelProvider {
        val queue = ArrayDeque(responses.toList())
        override val providerId = "scripted"
        override val supportedCapabilities = emptySet<ModelCapability>()
        override suspend fun generate(request: ModelRequest) = Result.success(queue.removeFirst())
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
        router = mockk<SystemOperationRouter>(relaxed = true),
        workspace = workspace(),
        soul = AgentSoul(File(tmp.root, "souls"))
    )

    /** Project-files seam: the real implementation + a temp directory (no mock -- directory traversal and path safety are exactly what should be verified together). */
    private fun workspace(): AgentWorkspace = AgentWorkspace(File(tmp.root, "ws"))

    private fun files(changes: FileChangeLog = InMemoryFileChangeLog()): ProjectFilesOwner =
        LocalProjectFiles(workspace(), changes)

    /**
     * Run driving (v1.6): a real supervisor + a real Dispatchers.IO -- what the case asserts is precisely that "driving still
     * runs outside the VM". The foreground service's start/stop callbacks are left empty (a Service is an Android component and does not go into unit tests).
     */
    private fun runSupervisor(owner: AgentRuntimeOwner): AgentRunSupervisor = AgentRunSupervisor(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        runtime = { owner.current() }
    )

    private fun viewModel(
        runtime: AgentRuntime,
        projectFiles: ProjectFilesOwner = files(),
        externalPreview: ExternalPreview = FakeExternalPreview(),
        uploads: UploadOwner = FakeUploads()
    ): AgentViewModel {
        val owner = FakeOwner(runtime)
        return AgentViewModel(
            owner,
            runSupervisor(owner),
            ApprovalCoordinator(),
            PermissionCoordinator(),
            fakeProjects(),
            FakeOneDrive(),
            projectFiles,
            externalPreview,
            uploads
        )
    }

    /** Real-time polling: awaits the predicate holding or the timeout expiring. */
    private fun waitFor(timeoutMs: Long = 8000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for state")
            Thread.sleep(25)
        }
    }

    // ----- cases -----

    @Test
    fun `send creates session, runs to completion and renders messages`() {
        val vm = viewModel(buildRuntime(ScriptedProvider(response("all done"))))

        Thread.sleep(100) // init: no sessions
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
        // Wait for the first tool round-trip to be persisted (after that the loop hangs on the gate of generate #2)
        waitFor { vm.uiState.value.messages.any { it.kind == UiMessageKind.ToolActivity } }

        // Input while running -> steering enqueued (the loop is hanging on the gate right now)
        vm.send("换个方向")
        vm.send("再优化一下")

        provider.gate.complete(Unit)
        waitFor { !vm.uiState.value.running }

        val kinds = vm.uiState.value.messages.map { it.kind }
        // A non-empty queue takes precedence over the completion guard (flowchart K->L): "adjusted final" lands one round first, and the steering
        // only really closes the loop after being drained by the next round.
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
        // The failed round persisted no assistant node at all
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
        // Wait until the user message is persisted before stopping (LoopStarted is emitted before the persist)
        waitFor { vm.uiState.value.messages.size == 1 }

        vm.stopRun()
        waitFor { !vm.uiState.value.running }
        // The user message is already persisted, so it stays in the projection
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
        assertEquals(1, vm.uiState.value.sessions.size) // history is still in the list

        vm.send("second")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }
        assertNotNull(vm.uiState.value.currentSessionId)
        assertTrue(vm.uiState.value.currentSessionId != firstSession)
    }

    @Test
    fun `selectModel applies selection through owner and updates state`() {
        val owner = FakeOwner(null)
        val vm = AgentViewModel(owner, runSupervisor(owner), ApprovalCoordinator(), PermissionCoordinator(), fakeProjects(), FakeOneDrive(), files(), FakeExternalPreview(), FakeUploads())
        Thread.sleep(100) // state is stable once init completes

        vm.selectModel("deepseek", "  deepseek-chat  ")
        assertEquals(ModelSelection("deepseek", "deepseek-chat"), owner.appliedSelection)
        assertEquals("deepseek", vm.uiState.value.activeProviderId)
        assertEquals("deepseek-chat", vm.uiState.value.activeModel)
        assertTrue(vm.uiState.value.modelConfigured)
    }

    @Test
    fun `selectModel is rejected while running`() {
        val owner = FakeOwner(buildRuntime(HangingProvider()))
        val vm = AgentViewModel(owner, runSupervisor(owner), ApprovalCoordinator(), PermissionCoordinator(), fakeProjects(), FakeOneDrive(), files(), FakeExternalPreview(), FakeUploads())

        vm.send("hi")
        waitFor { vm.uiState.value.running }
        vm.selectModel("deepseek", "deepseek-chat")

        assertNull(owner.appliedSelection)
        assertNotNull(vm.uiState.value.runError)
        vm.stopRun()
    }

    @Test
    fun `an attached document rides along with the next send and is cleared`() {
        val uploads = FakeUploads(
            Result.success(PendingUpload(id = "u1", name = "notes.md", kind = UploadKind.Document, text = "# 标题"))
        )
        val vm = viewModel(buildRuntime(ScriptedProvider(response("收到"))), uploads = uploads)

        vm.attachUpload("content://doc/1")
        waitFor { vm.uiState.value.pendingUploads.size == 1 }
        assertEquals("content://doc/1", uploads.requested)

        vm.send("帮我总结这篇笔记")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }

        val user = vm.uiState.value.messages.first()
        assertEquals("帮我总结这篇笔记", user.text)
        assertEquals(listOf(UiAttachment(name = "notes.md", image = false)), user.attachments)
        // Cleared at hand-off: a chip left behind would be attached again on the next message.
        assertTrue(vm.uiState.value.pendingUploads.isEmpty())
    }

    @Test
    fun `an upload-only turn is a valid message and names the session after the file`() {
        val image = Attachment.Image(id = "blob-1", mimeType = "image/webp", name = "cat.jpg")
        val uploads = FakeUploads(
            Result.success(PendingUpload(id = "u1", name = "cat.jpg", kind = UploadKind.Image, image = image))
        )
        val vm = viewModel(buildRuntime(ScriptedProvider(response("这是一只猫"))), uploads = uploads)

        vm.attachUpload("content://img/1")
        waitFor { vm.uiState.value.pendingUploads.size == 1 }
        vm.send("")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }

        val user = vm.uiState.value.messages.first()
        assertEquals("", user.text)
        // The transcript shows a thumbnail reference, not its bytes: the payload stays in the blob store.
        assertEquals(UiAttachment(name = "cat.jpg", image = true, imageId = "blob-1"), user.attachments.single())
        assertEquals("cat.jpg", vm.uiState.value.currentTitle)
    }

    @Test
    fun `a rejected pick surfaces the reason and stages nothing`() {
        val uploads = FakeUploads(
            Result.failure(UploadException("暂不支持「a.pdf」这种格式，目前支持图片和 txt / md 文档"))
        )
        val vm = viewModel(buildRuntime(ScriptedProvider(response("ok"))), uploads = uploads)

        vm.attachUpload("content://doc/pdf")
        waitFor { vm.uiState.value.uploadError != null }

        assertTrue(vm.uiState.value.uploadError!!.contains("暂不支持"))
        assertTrue(vm.uiState.value.pendingUploads.isEmpty())
        assertFalse(vm.uiState.value.uploadBusy)
    }

    @Test
    fun `removing a chip drops it before sending`() {
        val uploads = FakeUploads(
            Result.success(PendingUpload(id = "u1", name = "notes.md", kind = UploadKind.Document, text = "x"))
        )
        val vm = viewModel(buildRuntime(ScriptedProvider(response("ok"))), uploads = uploads)

        vm.attachUpload("content://doc/1")
        waitFor { vm.uiState.value.pendingUploads.size == 1 }
        vm.removeUpload("u1")

        assertTrue(vm.uiState.value.pendingUploads.isEmpty())
    }

    @Test
    fun `fetchModels delegates to owner with account`() = kotlinx.coroutines.runBlocking {
        val owner = FakeOwner(null)
        val vm = AgentViewModel(owner, runSupervisor(owner), ApprovalCoordinator(), PermissionCoordinator(), fakeProjects(), FakeOneDrive(), files(), FakeExternalPreview(), FakeUploads())
        val account = ProviderAccount(id = "openrouter", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1")

        val result = vm.fetchModels(account)

        assertEquals(account, owner.fetchedAccount)
        // The catalogue (not bare ids) crosses this boundary now: the selector shows context/price from it.
        assertEquals(listOf("m1", "m2"), result.getOrThrow().map { it.id })
    }

    @Test
    fun `the notification permission is asked once per process through the shared coordinator`() {
        val coordinator = PermissionCoordinator()
        val owner = FakeOwner(buildRuntime(ScriptedProvider(response("x"))))
        val vm = AgentViewModel(
            owner,
            runSupervisor(owner),
            ApprovalCoordinator(),
            coordinator,
            fakeProjects(),
            FakeOneDrive(),
            files(),
            FakeExternalPreview(),
            FakeUploads()
        )

        vm.ensureNotificationPermission()
        waitFor { coordinator.pending.value != null }
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            coordinator.pending.value?.permissions
        )

        // The dialog's answer lands back through the same coordinator; after that a second call must not
        // ask again (a foreground notification is worthless if the user is nagged for it every launch).
        vm.respondPermission(true)
        waitFor { coordinator.pending.value == null }

        vm.ensureNotificationPermission()
        Thread.sleep(100)
        assertNull(coordinator.pending.value)
    }

    // ----- edit and resend (branch semantics) -----

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
        // Active path = new question + new answer (the old branch is kept but not rendered)
        val user = state.messages.first { it.kind == UiMessageKind.User }
        assertEquals("编辑后的提问", user.text)
        assertEquals("second answer", state.messages.last().text)
        // Branch info: 2 branches at the same position, currently on the 2nd
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

        // Switch back from the new branch ‹ to the old branch: both the question and the answer revert to the old branch content
        val editedUser = vm.uiState.value.messages.first { it.kind == UiMessageKind.User }
        vm.switchBranch(editedUser.id, -1)
        waitFor { vm.uiState.value.messages.first { it.kind == UiMessageKind.User }.text == "原提问" }

        val state = vm.uiState.value
        assertEquals("first answer", state.messages.last().text)
        assertEquals(0, state.messages.first { it.kind == UiMessageKind.User }.branchIndex)
        assertTrue(!state.running)
    }

    // ----- project files and change cards (v1.5) -----

    /** Content goes into a JSON string: newlines must be escaped, otherwise argumentsJson simply cannot be parsed. */
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
            selfOps = { repo -> SelfOpsTools.registeredTools(repo, FakeSelfConfig(), ws, projects, log) },
            workspace = ws,
            soul = AgentSoul(File(tmp.root, "souls"))
        )
        val owner = FakeOwner(runtime)
        val vm = AgentViewModel(
            owner,
            runSupervisor(owner),
            ApprovalCoordinator(),
            PermissionCoordinator(),
            projects,
            FakeOneDrive(),
            LocalProjectFiles(ws, log),
            FakeExternalPreview(),
            FakeUploads()
        )

        vm.send("写一份笔记")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.any { it.fileChange != null } }

        // The tool really did write the file (the card is a projection of the fact, not a parallel second source of truth)
        assertEquals("# 标题\n正文", ws.read(ProjectLogic.DEFAULT_PROJECT_ID, "docs/notes.md").content)

        val card = vm.uiState.value.messages.first { it.fileChange != null }.fileChange!!
        assertEquals("docs/notes.md", card.path)
        assertEquals(FileChangeKind.Created, card.kind)
        assertEquals("c1", card.toolCallId)
        assertEquals(2, card.addedLines)
        assertEquals(0, card.removedLines)
        assertEquals(listOf('+', '+'), card.diff.map { it.prefix })

        // The small "new files" card at the bottom
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

        // Run one more round: the session projection incidentally refreshes the change cards
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
        // Directories first, the rest by name
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

    @Test
    fun `external preview hands the page's file to the seam and leaves the page alone`() {
        val ws = workspace()
        ws.writeBytes(ProjectLogic.DEFAULT_PROJECT_ID, "voice.wav", byteArrayOf(0x52, 0x49, 0x00, 0x00))
        val external = FakeExternalPreview()
        val vm = viewModel(buildRuntime(ScriptedProvider(response("noop"))), externalPreview = external)

        vm.openFilePreview("voice.wav")
        waitFor { vm.uiState.value.filePreview != null }
        // This app cannot render it -- which is exactly why the outside-app route exists
        assertNull(vm.uiState.value.filePreview!!.text)

        vm.openFileExternally()
        waitFor { external.opened != null }
        assertEquals(ProjectLogic.DEFAULT_PROJECT_ID to "voice.wav", external.opened)
        // Handing it over changes nothing on this page: the "cannot preview" reason still stands, it is just no longer a dead end
        assertEquals("无法预览此类型", vm.uiState.value.filePreview!!.notice)
    }

    @Test
    fun `external preview failure is stated on the page`() {
        val ws = workspace()
        ws.writeBytes(ProjectLogic.DEFAULT_PROJECT_ID, "voice.wav", byteArrayOf(0x52, 0x00))
        val external = FakeExternalPreview(ExternalPreviewException("没有能打开该文件的应用，可以先分享或导出它"))
        val vm = viewModel(buildRuntime(ScriptedProvider(response("noop"))), externalPreview = external)

        vm.openFilePreview("voice.wav")
        waitFor { vm.uiState.value.filePreview != null }
        vm.openFileExternally()

        waitFor { vm.uiState.value.filePreview?.notice?.contains("没有能打开") == true }
        assertEquals("没有能打开该文件的应用，可以先分享或导出它", vm.uiState.value.filePreview!!.notice)
    }

    @Test
    fun `external preview without an open page is a no-op`() {
        val external = FakeExternalPreview()
        val vm = viewModel(buildRuntime(ScriptedProvider(response("noop"))), externalPreview = external)

        vm.openFileExternally()
        Thread.sleep(100)

        assertNull(external.opened)
        assertNull(vm.uiState.value.filePreview)
    }

    // ----- deleting sessions / deleting projects (irreversible, must delete cleanly) -----

    @Test
    fun `deleteSession clears the projection and the stored session`() {
        val runtime = buildRuntime(ScriptedProvider(response("all done")))
        val vm = viewModel(runtime)

        vm.send("你好")
        waitFor { !vm.uiState.value.running && vm.uiState.value.messages.size == 2 }
        val sessionId = vm.uiState.value.currentSessionId!!

        vm.deleteSession(sessionId)

        waitFor { vm.uiState.value.sessions.isEmpty() }
        // What was deleted is the current session -> return to the "new chat" empty state, rather than staying on a session that no longer exists
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
        // The current session is not the deleted one: neither the projection nor the pointer moves
        assertEquals(second, vm.uiState.value.currentSessionId)
        assertNotNull(runtime.repository.getSession(second))
    }

    @Test
    fun `deleteProject clears its sessions, sandbox files and returns to the default project`() {
        val runtime = buildRuntime(ScriptedProvider(response("done")))
        // The owner is kept in hand: the Soul lives outside the sandbox, so "the directory is gone" is not enough.
        val owner = FakeOwner(runtime)
        val vm = AgentViewModel(
            owner,
            runSupervisor(owner),
            ApprovalCoordinator(),
            PermissionCoordinator(),
            fakeProjects(),
            FakeOneDrive(),
            files(),
            FakeExternalPreview(),
            FakeUploads()
        )

        vm.createProject("临时项目")
        waitFor { vm.uiState.value.projects.any { it.name == "临时项目" } }
        val projectId = vm.uiState.value.projects.first { it.name == "临时项目" }.id

        vm.switchProject(projectId)
        waitFor { vm.uiState.value.currentProjectId == projectId }
        owner.souls[projectId] = "# 临时项目的 Soul"

        // Put a file in the sandbox and confirm it disappears along with the project (rather than being left behind as an orphan directory)
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
        assertFalse(owner.souls.containsKey(projectId))
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

        // The refusal must happen before anything is touched -- if it were only blocked at the registry step, sessions and the sandbox would be cleared first
        assertEquals("默认项目不可删除", vm.uiState.value.runError)
        assertTrue(vm.uiState.value.projects.any { it.id == ProjectLogic.DEFAULT_PROJECT_ID })
        assertNotNull(runtime.repository.getSession(sessionId))
    }

    @Test
    fun `switching projects re-filters the session list for the project that is now current`() = runBlocking {
        val projects = fakeProjects()
        val a = projects.create("项目A").getOrThrow()
        val b = projects.create("项目B").getOrThrow()
        val runtime = buildRuntime(ScriptedProvider(response("noop")))
        runtime.repository.createSession("A 的对话", a.id)
        runtime.repository.createSession("B 的对话", b.id)

        val owner = FakeOwner(runtime)
        val vm = AgentViewModel(owner, runSupervisor(owner), ApprovalCoordinator(), PermissionCoordinator(), projects, FakeOneDrive(), files(), FakeExternalPreview(), FakeUploads())
        Thread.sleep(100) // init: the default project is current and owns no session

        // Switching has to re-filter for the project we just entered. Filtering by the id the projection still
        // held (the previous project) yields "the previous project's conversations under the new project's name",
        // and the new project's own conversations are not reachable until something else refreshes the list.
        vm.switchProject(a.id)
        waitFor { vm.uiState.value.currentProjectId == a.id && vm.uiState.value.sessions.map { it.title } == listOf("A 的对话") }

        vm.switchProject(b.id)
        waitFor { vm.uiState.value.currentProjectId == b.id && vm.uiState.value.sessions.map { it.title } == listOf("B 的对话") }
    }
}
