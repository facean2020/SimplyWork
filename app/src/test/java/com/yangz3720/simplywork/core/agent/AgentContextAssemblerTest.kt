package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.agent.tool.AgentToolRegistry
import com.yangz3720.simplywork.core.agent.tool.RegisteredTool
import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.session.SessionRepositoryImpl
import com.yangz3720.simplywork.core.session.StateMemory
import com.yangz3720.simplywork.core.session.TodoItem
import com.yangz3720.simplywork.core.session.TodoStatus
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.infrastructure.model.model.Message
import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentContextAssemblerTest {

    private val repository = SessionRepositoryImpl(InMemorySessionStore(), InMemoryPayloadStore())
    private val registry = AgentToolRegistry().apply {
        register(
            RegisteredTool(
                definition = ToolDefinition(name = "fake_tool", description = "", parametersSchema = buildJsonObject { put("type", "object") }),
                dangerLevel = DangerLevel.SAFE,
                parser = { Result.success(null) },
                runner = { _, _ -> buildJsonObject { put("ok", true) } }
            )
        )
    }
    private val config = AgentConfig(systemPrompt = "BASE-PROMPT")
    private val assembler = AgentContextAssembler(
        repository,
        registry,
        config,
        AgentSoul(File("build/test-agent-context/souls")),
        AgentWorkspace(File("build/test-agent-context/workspace"))
    )

    private suspend fun appendNode(sessionId: String, parentId: String?, type: SessionNodeType, payload: String): SessionNode =
        repository.appendNode(sessionId, parentId, type, Json.parseToJsonElement(payload) as JsonObject, 1, SessionNodeStatus.success)

    @Test
    fun `system message leads with prompt and state memory`() = runTest {
        val sid = repository.createSession("t").id
        repository.saveState(sid, StateMemory(todos = listOf(TodoItem("t1", "task", TodoStatus.pending, 1, 1))))
        val request = assembler.assemble(sid, emptyList())

        val system = request.messages.single() as Message.System
        assertTrue(system.content.startsWith("BASE-PROMPT"))
        assertTrue(system.content.contains("[STATE MEMORY]"))
        assertTrue(system.content.contains("task"))
    }

    @Test
    fun `path nodes map to messages in order`() = runTest {
        val sid = repository.createSession("t").id
        val base = appendNode(sid, null, SessionNodeType.SystemPrompt, """{"kind":"base","text":"BASE-PROMPT"}""")
        val prompt = appendNode(sid, base.id, SessionNodeType.UserPrompt, """{"type":"user","content":"hello"}""")
        val assistant = appendNode(sid, prompt.id, SessionNodeType.ToolCall, """{"type":"assistant","content":"doing"}""")
        appendNode(sid, assistant.id, SessionNodeType.ToolResult, """{"type":"tool","toolCallId":"c1","toolName":"fake_tool","content":"done","isError":false}""")

        val request = assembler.assemble(sid, repository.activePath(sid))

        val types = request.messages.map { it::class.simpleName }
        assertEquals(listOf("System", "User", "Assistant", "ToolResult"), types)
    }

    @Test
    fun `user turns carry a human-readable time anchor`() = runTest {
        val sid = repository.createSession("t").id
        val base = appendNode(sid, null, SessionNodeType.SystemPrompt, """{"kind":"base","text":"BASE"}""")
        val prompt = appendNode(sid, base.id, SessionNodeType.UserPrompt, """{"type":"user","content":"hello"}""")
        appendNode(sid, prompt.id, SessionNodeType.ToolCall, """{"type":"assistant","content":null,"toolCalls":[{"id":"c2","name":"fake_tool","argumentsJson":"{}"}]}""")

        val messages = assembler.assemble(sid, repository.activePath(sid)).messages

        // v1.2: exactly one wall-clock line per user turn (never per node, never epoch ms),
        // DST already resolved into the printed UTC offset.
        val user = messages.filterIsInstance<Message.User>().first()
        assertTrue(user.content.startsWith("[message time: "))
        assertTrue(Regex("""\[message time: \d{4}-\d{2}-\d{2} \w{3} \d{2}:\d{2} UTC[+-]\d{2}:\d{2}]""").containsMatchIn(user.content))
        assertTrue(user.content.endsWith("\nhello"))
        val assistant = messages.filterIsInstance<Message.Assistant>().single()
        assertTrue(!assistant.content.orEmpty().contains("message time"))
    }

    @Test
    fun `steer and follow_up and summary map per design`() = runTest {
        val sid = repository.createSession("t").id
        val base = appendNode(sid, null, SessionNodeType.SystemPrompt, """{"kind":"base","text":"BASE"}""")
        val summary = appendNode(sid, base.id, SessionNodeType.Summary, """{"text":"earlier stuff"}""")
        val steer = appendNode(sid, summary.id, SessionNodeType.Steer, """{"text":"focus on X"}""")
        appendNode(sid, steer.id, SessionNodeType.SystemPrompt, """{"kind":"follow_up","text":"still open"}""")

        val messages = assembler.assemble(sid, repository.activePath(sid)).messages

        assertTrue(messages[1] is Message.System && messages[1].toString().contains("earlier stuff"))
        assertTrue(messages[2] is Message.User && messages[2].toString().contains("[steering] focus on X"))
        assertTrue(messages[3] is Message.User && messages[3].toString().contains("[system follow-up] still open"))
    }

    @Test
    fun `assistant tool calls without results get synthetic error tools`() = runTest {
        val sid = repository.createSession("t").id
        val base = appendNode(sid, null, SessionNodeType.SystemPrompt, """{"kind":"base","text":"BASE"}""")
        appendNode(
            sid, base.id, SessionNodeType.ToolCall,
            """{"type":"assistant","content":null,"toolCalls":[{"id":"call-9","name":"fake_tool","argumentsJson":"{}"}]}"""
        )

        val messages = assembler.assemble(sid, repository.activePath(sid)).messages

        val tool = messages.filterIsInstance<Message.ToolResult>().single()
        assertEquals("call-9", tool.toolCallId)
        assertTrue(tool.isError)
        assertTrue(tool.content.contains("No result was produced"))
    }

    @Test
    fun `tools come from the registry`() = runTest {
        val sid = repository.createSession("t").id
        val request = assembler.assemble(sid, emptyList())
        assertEquals(listOf("fake_tool"), request.tools.map { it.name })
    }
}
