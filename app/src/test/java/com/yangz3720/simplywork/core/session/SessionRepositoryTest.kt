package com.yangz3720.simplywork.core.session

import com.yangz3720.simplywork.core.attachment.InMemoryImageBlobStore
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.model.model.Attachment
import com.yangz3720.simplywork.infrastructure.model.model.Message
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
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

    // ----- branching (edit-and-resend, VSCode-Copilot style) -----

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

        // the leaf advances onto the new branch; the old branch's nodes stay in the store
        assertEquals(edited.id, repository.getSession(session.id)!!.activeLeafId)
        assertTrue(repository.listNodes(session.id).any { it.id == original.id })

        // UserPrompt siblings under the same parent = original + edit (ascending createdAt)
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

        // switching back to the old branch: the active path restores the whole old chain
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

    // ----- deleting a session -----

    @Test
    fun `deleteSession removes nodes, state and payload`() = runTest {
        val session = repository.createSession("bye")
        val node = append(session.id, null, SessionNodeType.UserPrompt, "hi")
        repository.saveState(session.id, StateMemory(todos = listOf(TodoItem("t1", "x", TodoStatus.done, 1L, 1L))))

        repository.deleteSession(session.id)

        assertNull(repository.getSession(session.id))
        assertTrue(repository.listNodes(session.id).isEmpty())
        assertTrue(repository.activePath(session.id).isEmpty())
        assertTrue(repository.loadState(session.id).todos.isEmpty())
        // the payload must be gone too: reading it has to fail explicitly, not quietly return the old content
        assertThrows(InfrastructureException.Session.PayloadMissing::class.java) { repository.payload(node) }
    }

    @Test
    fun `deleteSession leaves other sessions untouched`() = runTest {
        val keep = repository.createSession("keep")
        val gone = repository.createSession("gone")
        append(keep.id, null, SessionNodeType.UserPrompt, "k")
        append(gone.id, null, SessionNodeType.UserPrompt, "g")

        repository.deleteSession(gone.id)

        assertNull(repository.getSession(gone.id))
        assertTrue(repository.listNodes(gone.id).isEmpty())
        assertEquals(1, repository.listNodes(keep.id).size)
        assertTrue(repository.activePath(keep.id).single().nodeType == SessionNodeType.UserPrompt)
    }

    @Test
    fun `deleteSession also drops the image blobs its payloads reference`() = runTest {
        val blobs = InMemoryImageBlobStore()
        val repo = SessionRepositoryImpl(InMemorySessionStore(), InMemoryPayloadStore(), blobs)
        blobs.save("img-1", byteArrayOf(1))
        blobs.save("img-2", byteArrayOf(2))
        val session = repo.createSession("pics")
        val payload = Json.encodeToJsonElement(
            Message.serializer(),
            Message.User(
                content = "看图",
                attachments = listOf(Attachment.Image(id = "img-1", mimeType = "image/webp", name = "a.webp"))
            )
        ) as JsonObject
        repo.appendNode(session.id, null, SessionNodeType.UserPrompt, payload, 1, SessionNodeStatus.success)

        repo.deleteSession(session.id)

        assertNull(blobs.read("img-1"))
        // A blob another session still owns must survive.
        assertArrayEquals(byteArrayOf(2), blobs.read("img-2"))
    }
}
