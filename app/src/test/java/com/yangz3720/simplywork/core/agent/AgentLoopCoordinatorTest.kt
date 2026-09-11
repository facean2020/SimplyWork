package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.session.TodoStatus
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import com.yangz3720.simplywork.infrastructure.model.ModelProvider
import com.yangz3720.simplywork.infrastructure.model.config.ModelCapability
import com.yangz3720.simplywork.infrastructure.model.model.FinishReason
import com.yangz3720.simplywork.infrastructure.model.model.Message
import com.yangz3720.simplywork.infrastructure.model.model.ModelRequest
import com.yangz3720.simplywork.infrastructure.model.model.ModelResponse
import com.yangz3720.simplywork.infrastructure.model.model.TokenUsage
import com.yangz3720.simplywork.infrastructure.model.model.ToolCall
import com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouter
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end loop tests over the real AgentRuntime wiring (in-memory stores +
 * TruncateSummarizer) with a scripted ModelProvider.
 */
class AgentLoopCoordinatorTest {

    /** Scripted provider: pops responses in order; records every request. */
    private class ScriptedProvider(vararg responses: ModelResponse) : ModelProvider {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<ModelRequest>()

        override val providerId = "scripted"
        override val supportedCapabilities = emptySet<ModelCapability>()

        override suspend fun generate(request: ModelRequest): Result<ModelResponse> {
            requests.add(request)
            return Result.success(queue.removeFirst())
        }

        override fun generateStream(request: ModelRequest) = throw UnsupportedOperationException()
    }

    private fun response(text: String, toolCalls: List<ToolCall> = emptyList(), completionTokens: Int = 7) = ModelResponse(
        id = "resp-${System.nanoTime()}",
        model = "scripted",
        message = Message.Assistant(content = text.ifBlank { null }, toolCalls = toolCalls),
        finishReason = if (toolCalls.isEmpty()) FinishReason.STOP else FinishReason.TOOL_CALLS,
        usage = TokenUsage(promptTokens = 10, completionTokens = completionTokens)
    )

    private fun todoCall(id: String, title: String, status: TodoStatus) = ToolCall(
        id = id,
        name = "todo_write",
        argumentsJson = """{"items":[{"title":"$title","status":"${status.name}"}]}"""
    )

    private fun runtime(provider: ModelProvider, config: AgentConfig): AgentRuntime =
        AgentRuntime.createWithStores(
            structureStore = InMemorySessionStore(),
            payloadStore = InMemoryPayloadStore(),
            provider = provider,
            router = mockk<SystemOperationRouter>(relaxed = true),
            config = config,
            summarizer = TruncateSummarizer()
        )

    @Test
    fun `direct answer completes the loop`() = runTest {
        val provider = ScriptedProvider(response("Hello, done!"))
        val agent = runtime(provider, AgentConfig())
        val session = agent.repository.createSession("t")

        val events = agent.coordinator.run(session.id, "hi there").toList()

        assertTrue(events.first() is AgentStepEvent.LoopStarted)
        assertTrue(events.last() is AgentStepEvent.Completed)
        assertEquals("Hello, done!", (events.last() as AgentStepEvent.Completed).finalText)

        // Node chain: base system prompt → user prompt → assistant turn (closed as success).
        val path = agent.repository.activePath(session.id)
        assertEquals(listOf("SystemPrompt", "UserPrompt", "ToolCall"), path.map { it.nodeType.name })
        assertEquals(SessionNodeStatus.success, path.last().status)
    }

    @Test
    fun `todo tool roundtrip then guard follow-up then closure`() = runTest {
        val provider = ScriptedProvider(
            response("", toolCalls = listOf(todoCall("c1", "check mailbox", TodoStatus.pending))),
            response("working on it"),
            response("", toolCalls = listOf(todoCall("c2", "check mailbox", TodoStatus.done))),
            response("all finished!")
        )
        val agent = runtime(provider, AgentConfig(maxGuardInterventions = 3, maxLoopIterations = 20))
        val session = agent.repository.createSession("t")

        val events = agent.coordinator.run(session.id, "handle my mailbox").toList()

        // Guard follow-up happened exactly once, then the run completed naturally.
        assertEquals(1, events.count { it is AgentStepEvent.FollowUpInjected })
        val completed = events.filterIsInstance<AgentStepEvent.Completed>().single()
        assertEquals("all finished!", completed.finalText)

        // todo_write really wrote State Memory: first pending, then done.
        assertTrue(agent.repository.loadState(session.id).openTodos.isEmpty())
        assertEquals(TodoStatus.done, agent.repository.loadState(session.id).todos.single().status)

        // The turn that emitted the follow-up kept its ToolCall node success.
        val nodes = agent.repository.activePath(session.id)
        assertTrue(nodes.filter { it.nodeType == SessionNodeType.ToolCall }.all { it.status == SessionNodeStatus.success })
    }

    @Test
    fun `steering pre-queued before the run lands as a user message`() = runTest {
        val provider = ScriptedProvider(response("acknowledged"))
        val agent = runtime(provider, AgentConfig())
        val session = agent.repository.createSession("t")
        agent.queue.enqueue(IncomingMessage.Steer("focus on the weather"))

        agent.coordinator.run(session.id, "hi").toList()

        val firstRequest = provider.requests[0]
        assertTrue(firstRequest.messages.any { it is Message.User && it.content.contains("[steering] focus on the weather") })
    }

    @Test
    fun `unknown tool denial is fed back and ToolCall node marked failed`() = runTest {
        val provider = ScriptedProvider(
            response("", toolCalls = listOf(ToolCall("c1", "nonexistent_tool", "{}"))),
            response("adapted")
        )
        val agent = runtime(provider, AgentConfig())
        val session = agent.repository.createSession("t")

        val events = agent.coordinator.run(session.id, "go").toList()

        val rejected = events.filterIsInstance<AgentStepEvent.ToolRejected>().single()
        assertEquals("nonexistent_tool", rejected.toolName)

        // The assistant turn node is failed; the rejection is a visible error result.
        val nodes = agent.repository.activePath(session.id)
        val assistantNode = nodes.first { it.nodeType == SessionNodeType.ToolCall }
        assertEquals(SessionNodeStatus.failed, assistantNode.status)
        val toolResultPayload = agent.repository.payload(nodes.first { it.nodeType == SessionNodeType.ToolResult }).toString()
        assertTrue(toolResultPayload.contains("Unknown tool"))
    }

    @Test
    fun `guard interventions are capped by maxGuardInterventions`() = runTest {
        // Blank answers keep tripping the guard's empty-response check; the cap must
        // force-stop instead of looping forever.
        val provider = ScriptedProvider(
            response(""),
            response("")
        )
        val agent = runtime(provider, AgentConfig(maxGuardInterventions = 2))
        val session = agent.repository.createSession("t")

        val events = agent.coordinator.run(session.id, "long task").toList()

        assertEquals(1, events.count { it is AgentStepEvent.FollowUpInjected })
        val completed = events.filterIsInstance<AgentStepEvent.Completed>().single()
        assertEquals(AgentStepEvent.COMPLETED_GUARD_EXHAUSTED, completed.reason)
    }

    @Test
    fun `loop budget exhaustion fails the run`() = runTest {
        val tool = com.yangz3720.simplywork.core.agent.tool.RegisteredTool(
            definition = com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition("noop", "", kotlinx.serialization.json.buildJsonObject { put("type", "object") }),
            dangerLevel = com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel.SAFE,
            parser = { Result.success(null) },
            runner = { _, _ -> kotlinx.serialization.json.buildJsonObject { put("ok", true) } }
        )
        val provider = ScriptedProvider(
            response("", toolCalls = listOf(ToolCall("c1", "loop_tool", "{}"))),
            response("", toolCalls = listOf(ToolCall("c2", "loop_tool", "{}"))),
            response("", toolCalls = listOf(ToolCall("c3", "loop_tool", "{}")))
        )
        val agent = runtime(provider, AgentConfig(maxLoopIterations = 2))
        agent.registry.register(tool)
        val session = agent.repository.createSession("t")

        val events = agent.coordinator.run(session.id, "loop forever").toList()

        assertTrue(events.last() is AgentStepEvent.Failed)
    }

    @Test
    fun `model failure terminates with Failed event`() = runTest {
        val failing = object : ModelProvider {
            override val providerId = "failing"
            override val supportedCapabilities = emptySet<ModelCapability>()
            override suspend fun generate(request: ModelRequest): Result<ModelResponse> =
                Result.failure(IllegalStateException("connection reset"))
            override fun generateStream(request: ModelRequest) = throw UnsupportedOperationException()
        }
        val agent = runtime(failing, AgentConfig())
        val session = agent.repository.createSession("t")

        val events = agent.coordinator.run(session.id, "hi").toList()

        assertTrue(events.last() is AgentStepEvent.Failed)
        assertTrue((events.last() as AgentStepEvent.Failed).cause.message!!.contains("connection reset"))
        // No assistant node was persisted for the failed turn.
        assertTrue(agent.repository.activePath(session.id).none { it.nodeType == SessionNodeType.ToolCall })
    }
}
