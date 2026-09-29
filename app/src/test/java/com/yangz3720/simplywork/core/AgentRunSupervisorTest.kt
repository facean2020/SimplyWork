package com.yangz3720.simplywork.core

import com.yangz3720.simplywork.core.agent.AgentStepEvent
import com.yangz3720.simplywork.core.agent.IncomingMessage
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import com.yangz3720.simplywork.infrastructure.model.ModelProvider
import com.yangz3720.simplywork.infrastructure.model.config.ModelCapability
import com.yangz3720.simplywork.infrastructure.model.model.FinishReason
import com.yangz3720.simplywork.infrastructure.model.model.Message
import com.yangz3720.simplywork.infrastructure.model.model.ModelRequest
import com.yangz3720.simplywork.infrastructure.model.model.ModelResponse
import com.yangz3720.simplywork.infrastructure.model.model.TokenUsage
import com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouter
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行驱动（v1.6）：run 现在活在进程级 supervisor 里，本文件验的正是"驱动与 UI 无关"这件事
 * —— 没有 ViewModel，run 照样跑完、照样被停下、照样触发前台服务的启停回调。
 */
class AgentRunSupervisorTest {

    private class ScriptedProvider(vararg responses: ModelResponse) : ModelProvider {
        val queue = ArrayDeque(responses.toList())
        override val providerId = "scripted"
        override val supportedCapabilities = emptySet<ModelCapability>()
        override suspend fun generate(request: ModelRequest) = Result.success(queue.removeFirst())
        override fun generateStream(request: ModelRequest) = throw UnsupportedOperationException()
    }

    /** 一直挂起：用来验停止/取消语义。 */
    private inner class HangingProvider : ModelProvider {
        val blocker = CompletableDeferred<Unit>()
        override val providerId = "hanging"
        override val supportedCapabilities = emptySet<ModelCapability>()
        override suspend fun generate(request: ModelRequest): Result<ModelResponse> {
            blocker.await()
            return Result.success(response("never"))
        }
        override fun generateStream(request: ModelRequest) = throw UnsupportedOperationException()
    }

    /** 单轮纯文本响应：run 会在这一轮直接收尾（无工具调用 → 完成守卫放行）。 */
    private fun response(text: String) = ModelResponse(
        id = "resp-${System.nanoTime()}",
        model = "scripted",
        message = Message.Assistant(content = text),
        finishReason = FinishReason.STOP,
        usage = TokenUsage(promptTokens = 10, completionTokens = 7)
    )

    private fun buildRuntime(provider: ModelProvider): AgentRuntime = AgentRuntime.createWithStores(
        structureStore = InMemorySessionStore(),
        payloadStore = InMemoryPayloadStore(),
        provider = provider,
        router = mockk<SystemOperationRouter>(relaxed = true)
    )

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun waitFor(timeoutMs: Long = 8000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for state")
            Thread.sleep(25)
        }
    }

    @Test
    fun `start drives the loop to completion and emits events without any ViewModel`() = runBlocking {
        val runtime = buildRuntime(ScriptedProvider(response("all done")))
        val session = runtime.repository.createSession("t")
        val events = CopyOnWriteArrayList<AgentStepEvent>()
        val supervisor = AgentRunSupervisor(scope(), { runtime })
        val collector = scope().collectEvents(supervisor, events)

        supervisor.start(session.id, "你好")
        assertTrue(supervisor.state.value.running)
        assertEquals(session.id, supervisor.state.value.sessionId)

        waitFor { !supervisor.state.value.running }

        // 循环真的跑完了：会话被落库，事件里出现过 Completed。
        assertTrue(runtime.repository.activePath(session.id).size >= 3)
        assertTrue(events.any { it is AgentStepEvent.Completed })
        assertNull(supervisor.state.value.sessionId)
        assertEquals(0, supervisor.state.value.iteration)
        collector.cancel()
    }

    @Test
    fun `foreground service hooks bracket the run`() = runBlocking {
        val runtime = buildRuntime(ScriptedProvider(response("done")))
        val session = runtime.repository.createSession("t")
        val started = mutableListOf<String>()
        var stopped = 0
        val supervisor = AgentRunSupervisor(
            scope = scope(),
            runtime = { runtime },
            onRunStarted = { started += it },
            onRunStopped = { stopped += 1 }
        )

        supervisor.start(session.id, "go")
        waitFor { !supervisor.state.value.running }

        assertEquals(listOf(session.id), started)
        assertEquals(1, stopped)
    }

    @Test
    fun `stop cancels a hanging run and clears the running state`() = runBlocking {
        val provider = HangingProvider()
        val runtime = buildRuntime(provider)
        val session = runtime.repository.createSession("t")
        var stopped = 0
        val supervisor = AgentRunSupervisor(scope(), { runtime }, onRunStopped = { stopped += 1 })

        supervisor.start(session.id, "start")
        waitFor { supervisor.state.value.running }

        supervisor.stop()
        assertFalse(supervisor.state.value.running)
        assertNull(supervisor.state.value.sessionId)
        waitFor { stopped == 1 }
    }

    @Test
    fun `stop without a run does not touch the service`() = runBlocking {
        val runtime = buildRuntime(ScriptedProvider(response("done")))
        var stopped = 0
        val supervisor = AgentRunSupervisor(scope(), { runtime }, onRunStopped = { stopped += 1 })

        supervisor.stop()

        assertEquals(0, stopped)
        assertFalse(supervisor.state.value.running)
    }

    @Test
    fun `steer enqueues a steering message into the runtime queue`() = runBlocking {
        val runtime = buildRuntime(HangingProvider())
        val supervisor = AgentRunSupervisor(scope(), { runtime })

        supervisor.steer("换个方向")

        val drained = runtime.queue.drainAll()
        assertEquals(1, drained.size)
        assertTrue(drained.single() is IncomingMessage.Steer)
    }

    @Test
    fun `start without a configured runtime is a no-op`() = runBlocking {
        var started = 0
        val supervisor = AgentRunSupervisor(scope(), { null }, onRunStarted = { started += 1 })

        supervisor.start("whatever", "go")

        assertFalse(supervisor.state.value.running)
        assertEquals(0, started)
    }

    /** 事件收集器：在独立 scope 里订阅，模拟 UI 侧的投影订阅者。 */
    private fun CoroutineScope.collectEvents(
        supervisor: AgentRunSupervisor,
        sink: MutableList<AgentStepEvent>
    ): Job = launch { supervisor.events.collect { sink += it } }
}