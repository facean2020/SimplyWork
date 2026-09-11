package com.yangz3720.simplywork.ui.agent

import android.content.SharedPreferences
import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.testsupport.InMemoryPrefs
import com.yangz3720.simplywork.core.session.TodoStatus
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

    // ----- fakes -----

    private fun fakeProjects(): ProjectStore = ProjectStore(InMemoryPrefs())

    private class FakeOwner(var runtime: AgentRuntime?) : AgentRuntimeOwner {
        val accounts = ModelProviderPresets.ALL.toMutableList()
        var selection: ModelSelection? = null
        var currentTimeout: Long = 60_000L
        var savedKey: Pair<String, String>? = null
        var appliedSelection: ModelSelection? = null
        var fetchedAccount: ProviderAccount? = null
        var rebuilds: Int = 0
        override val configVersion = MutableStateFlow(0)

        override fun current(): AgentRuntime? = runtime
        override fun accounts(): List<ProviderAccount> = accounts
        override fun selection(): ModelSelection? = selection
        override fun timeoutMs(): Long = currentTimeout
        override fun rebuildRuntimeIfNeeded() { rebuilds += 1 }
        override fun saveAccountKey(accountId: String, apiKey: String) { savedKey = accountId to apiKey }
        override fun upsertCustomAccount(name: String, baseUrl: String, apiKey: String) =
            ProviderAccount(id = "custom-x", name = name, baseUrl = baseUrl, apiKey = apiKey)
        override fun removeCustomAccount(accountId: String) { /* no-op */ }
        override fun setTimeout(ms: Long) { currentTimeout = ms }
        override fun applySelection(providerId: String, model: String) {
            appliedSelection = ModelSelection(providerId, model)
            selection = appliedSelection
        }
        override suspend fun fetchModels(account: ProviderAccount): Result<List<String>> {
            fetchedAccount = account
            return Result.success(listOf("m1", "m2"))
        }
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

    private fun viewModel(runtime: AgentRuntime): AgentViewModel =
        AgentViewModel(FakeOwner(runtime), ApprovalCoordinator(), PermissionCoordinator(), fakeProjects())

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
        val vm = AgentViewModel(owner, ApprovalCoordinator(), PermissionCoordinator(), fakeProjects())
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
        val vm = AgentViewModel(owner, ApprovalCoordinator(), PermissionCoordinator(), fakeProjects())

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
        val vm = AgentViewModel(owner, ApprovalCoordinator(), PermissionCoordinator(), fakeProjects())
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
}