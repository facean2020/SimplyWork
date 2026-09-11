package com.yangz3720.simplywork.core.session

import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {

    private val repository = SessionRepositoryImpl(InMemorySessionStore(), InMemoryPayloadStore())

    private suspend fun append(
        sessionId: String,
        parentId: String?,
        type: SessionNodeType,
        text: String,
        status: SessionNodeStatus = SessionNodeStatus.success,
        tokenCount: Int = 1
    ): SessionNode = repository.appendNode(
        sessionId = sessionId,
        parentId = parentId,
        nodeType = type,
        payload = buildJsonObject { put("text", text) },
        tokenCount = tokenCount,
        status = status
    )

    @Test
    fun `append advances the active leaf and path is rebuilt from parents`() = runTest {
        val session = repository.createSession("hello")
        val n1 = append(session.id, null, SessionNodeType.UserPrompt, "first")
        val n2 = append(session.id, n1.id, SessionNodeType.ToolCall, "answer")
        val n3 = append(session.id, n2.id, SessionNodeType.ToolResult, "result")

        val path = repository.activePath(session.id)
        assertTrue(path.map { it.id } == listOf(n1.id, n2.id, n3.id))
        assertTrue(repository.getSession(session.id)!!.activeLeafId == n3.id)
    }

    @Test
    fun `compaction rewiring places the summary between prefix and suffix`() = runTest {
        val session = repository.createSession("s")
        val base = append(session.id, null, SessionNodeType.SystemPrompt, "base")
        val n1 = append(session.id, base.id, SessionNodeType.UserPrompt, "u1")
        val n2 = append(session.id, n1.id, SessionNodeType.ToolCall, "a1")
        val n3 = append(session.id, n2.id, SessionNodeType.ToolResult, "r1")

        // Compress [n1..n2] into a summary hanging under `base` (compactor semantics:
        // the summary insert must not move the leaf).
        val summary = repository.appendNode(
            sessionId = session.id,
            parentId = base.id,
            nodeType = SessionNodeType.Summary,
            payload = buildJsonObject { put("text", "compressed") },
            tokenCount = 1,
            status = SessionNodeStatus.success,
            advanceLeaf = false
        )
        repository.rewireParent(n3.id, summary.id)

        val path = repository.activePath(session.id)
        assertTrue(path.map { it.nodeType } == listOf(
            SessionNodeType.SystemPrompt,
            SessionNodeType.Summary,
            SessionNodeType.ToolResult
        ))
    }

    @Test
    fun `status update is persisted`() = runTest {
        val session = repository.createSession("t")
        val node = append(session.id, null, SessionNodeType.ToolCall, "x", status = SessionNodeStatus.pending)
        repository.updateNodeStatus(node.id, SessionNodeStatus.failed)
        assertTrue(repository.activePath(session.id).single().status == SessionNodeStatus.failed)
    }

    @Test
    fun `missing payload surfaces explicitly`() = runTest {
        val session = repository.createSession("t")
        val node = append(session.id, null, SessionNodeType.UserPrompt, "x")
        // Simulate the crash window: DB row without payload line.
        val orphan = node.copy(id = "no-such-node")
        assertThrows(InfrastructureException.Session.PayloadMissing::class.java) { repository.payload(orphan) }
    }

    @Test
    fun `state memory roundtrip`() = runTest {
        val session = repository.createSession("t")
        repository.saveState(
            session.id,
            StateMemory(
                todos = listOf(TodoItem("t1", "buy milk", TodoStatus.in_progress, 1L, 1L)),
                preferences = listOf("Always reply in Chinese")
            )
        )
        val state = repository.loadState(session.id)
        assertTrue(state.openTodos.single().id == "t1")
        assertTrue(state.openTodos.single().title == "buy milk")
        assertTrue(state.preferences == listOf("Always reply in Chinese"))
    }

    @Test
    fun `cleanup marks pending nodes abandoned and synthesizes interrupted results`() = runTest {
        val session = repository.createSession("t")
        val base = append(session.id, null, SessionNodeType.SystemPrompt, "base")
        val prompt = append(session.id, base.id, SessionNodeType.UserPrompt, "go")
        // A pending assistant turn with one tool call (simulates a crash mid-execution).
        val pending = repository.appendNode(
            sessionId = session.id,
            parentId = prompt.id,
            nodeType = SessionNodeType.ToolCall,
            payload = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"type":"assistant","toolCalls":[{"id":"call-1","name":"sys_app_open","argumentsJson":"{}"}]}"""
            ) as kotlinx.serialization.json.JsonObject,
            tokenCount = 5,
            status = SessionNodeStatus.pending
        )

        repository.cleanupInterrupted(session.id)

        val path = repository.activePath(session.id)
        val pendingAfter = path.single { it.id == pending.id }
        assertTrue(pendingAfter.status == SessionNodeStatus.abandoned)
        val synth = path.last()
        assertTrue(synth.nodeType == SessionNodeType.ToolResult)
        val synthPayload = repository.payload(synth).toString()
        assertTrue(synthPayload.contains("call-1"))
        assertTrue(synthPayload.contains("interrupted"))
        assertNull(null)
    }

    @Test
    fun `createSession truncates long titles`() = runTest {
        val record = repository.createSession("x".repeat(100))
        assertTrue(record.title.length == 48)
        assertTrue(repository.getSession(record.id) != null)
    }

    // ----- 分支（编辑重发，VSCode-Copilot 式） -----

    @Test
    fun `appendUserPrompt creates sibling branch and advances leaf`() = runTest {
        val session = repository.createSession("s")
        val base = append(session.id, null, SessionNodeType.SystemPrompt, "base")
        val original = repository.appendUserPrompt(session.id, base.id, "原提问")
        repository.appendNode(
            session.id, original.id, SessionNodeType.ToolCall,
            buildJsonObject { put("text", "a") }, 1, SessionNodeStatus.success
        )

        val edited = repository.appendUserPrompt(session.id, base.id, "编辑后的提问")

        // 叶子推进到新分支；旧分支节点保留在库里
        assertEquals(edited.id, repository.getSession(session.id)!!.activeLeafId)
        assertTrue(repository.listNodes(session.id).any { it.id == original.id })

        // 同 parent 的 UserPrompt 兄弟 = 原始 + 编辑（createdAt 升序）
        val siblings = repository.promptSiblings(session.id, original.id)
        assertEquals(listOf(original.id, edited.id), siblings.map { it.id })
    }

    @Test
    fun `branchLeaf walks to newest descendant and switch restores branch`() = runTest {
        val session = repository.createSession("s")
        val original = repository.appendUserPrompt(session.id, null, "原提问")
        val child1 = repository.appendNode(
            session.id, original.id, SessionNodeType.ToolCall,
            buildJsonObject { put("text", "a") }, 1, SessionNodeStatus.success
        )
        val child2 = repository.appendNode(
            session.id, child1.id, SessionNodeType.ToolResult,
            buildJsonObject { put("text", "r") }, 1, SessionNodeStatus.success
        )

        assertEquals(child2.id, repository.branchLeaf(session.id, original.id))

        // 切回旧分支：活跃路径恢复整条旧链
        repository.updateActiveLeaf(session.id, repository.branchLeaf(session.id, original.id))
        assertEquals(
            listOf(original.id, child1.id, child2.id),
            repository.activePath(session.id).map { it.id }
        )
    }

    @Test
    fun `promptSiblings returns empty for unknown node`() = runTest {
        val session = repository.createSession("s")
        assertTrue(repository.promptSiblings(session.id, "ghost").isEmpty())
    }
}
