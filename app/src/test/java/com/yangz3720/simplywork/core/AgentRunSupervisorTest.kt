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
 * Run driver (v1.6): a run now lives in a process-level supervisor, and what this file pins down is
 * exactly that "the driver is independent of the UI" -- with no ViewModel the run still finishes, is still stopped, and still fires the foreground service start/stop hooks.
 */
class AgentRunSupervisorTest {

    private class ScriptedProvider(vararg responses: ModelResponse) : ModelProvider {
        val queue = ArrayDeque(responses.toList())
        override val providerId = "scripted"
        override val supportedCapabilities = emptySet<ModelCapability>()
        override suspend fun generate(request: ModelRequest) = Result.success(queue.removeFirst())
        override fun generateStream(request: ModelRequest) = throw UnsupportedOperationException()
    }

    /** Hangs forever: used to pin down stop/cancel semantics. */
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

    /** A single plain-text turn: the run wraps up in this turn (no tool call -> the completion guard lets it through). */
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

        // the loop really did run to the end: the session is persisted, and a Completed event showed up.
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

    /** Event collector: subscribes in its own scope, standing in for the UI-side projection subscriber. */
    private fun CoroutineScope.collectEvents(
        supervisor: AgentRunSupervisor,
        sink: MutableList<AgentStepEvent>
    ): Job = launch { supervisor.events.collect { sink += it } }
}