package com.yangz3720.simplywork.ui.agent

import com.yangz3720.simplywork.core.agent.NodePayloads
import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.core.workspace.FileChangeKind
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.model.model.Attachment
import com.yangz3720.simplywork.infrastructure.model.model.Message
import com.yangz3720.simplywork.infrastructure.model.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConversationRenderer pure-function tests: DAG active path -> UiMessage projection.
 */
class ConversationRendererTest {

    private fun messagePayload(message: Message): JsonObject =
        Json.encodeToJsonElement(Message.serializer(), message) as JsonObject

    private fun node(
        id: String,
        nodeType: SessionNodeType,
        status: SessionNodeStatus = SessionNodeStatus.success,
        payload: () -> JsonObject
    ) = SessionNode(
        id = id, sessionId = "s", parentId = null, nodeType = nodeType,
        tokenCount = 1, status = status, createdAt = 42L
    ).let { it to payload() }

    private fun render(vararg pairs: Pair<SessionNode, JsonObject>): List<UiMessage> =
        ConversationRenderer.render(pairs.map { it.first }) { node -> pairs.first { it.first.id == node.id }.second }

    // ----- cases -----

    @Test
    fun `user prompt renders as user message`() {
        val messages = render(node("u1", SessionNodeType.UserPrompt) { messagePayload(Message.User("你好")) })
        assertEquals(1, messages.size)
        assertEquals(UiMessageKind.User, messages[0].kind)
        assertEquals("你好", messages[0].text)
    }

    @Test
    fun `a user turn carries its uploads into the projection`() {
        val messages = render(
            node("u1", SessionNodeType.UserPrompt) {
                messagePayload(
                    Message.User(
                        content = "",
                        attachments = listOf(Attachment.Image(id = "blob-1", mimeType = "image/webp", name = "cat.jpg"))
                    )
                )
            }
        )

        // An upload-only turn shows its thumbnail and no body: the bubble must not render an empty text block.
        assertEquals("", messages[0].text)
        assertEquals(
            UiAttachment(name = "cat.jpg", image = true, imageId = "blob-1"),
            messages[0].attachments.single()
        )
    }

    @Test
    fun `base system prompt hidden and follow_up rendered`() {
        val base = node("b", SessionNodeType.SystemPrompt) { buildJsonObject { put("kind", "base"); put("text", "system") } }
        val followUp = node("f", SessionNodeType.SystemPrompt) { buildJsonObject { put("kind", "follow_up"); put("text", "继续完成") } }
        val messages = render(base, followUp)
        assertEquals(1, messages.size)
        assertEquals(UiMessageKind.FollowUp, messages[0].kind)
        assertEquals("继续完成", messages[0].text)
    }

    @Test
    fun `assistant turn splits into text and tool activities`() {
        val assistant = Message.Assistant(
            content = "我先记录待办",
            toolCalls = listOf(
                com.yangz3720.simplywork.infrastructure.model.model.ToolCall(
                    id = "c1", name = "todo_write", argumentsJson = """{"items":[]}"""
                ),
                com.yangz3720.simplywork.infrastructure.model.model.ToolCall(
                    id = "c2", name = "sys_clipboard_read", argumentsJson = """{}"""
                )
            )
        )
        val messages = render(node("a", SessionNodeType.ToolCall) { messagePayload(assistant) })
        assertEquals(3, messages.size)
        assertEquals(UiMessageKind.Assistant, messages[0].kind)
        assertEquals("我先记录待办", messages[0].text)
        assertEquals(UiMessageKind.ToolActivity, messages[1].kind)
        assertEquals("c1", messages[1].toolCallId)
        assertEquals("todo_write", messages[1].toolName)
        assertEquals(UiMessageKind.ToolActivity, messages[2].kind)
        assertEquals("c2", messages[2].toolCallId)
    }

    @Test
    fun `tool result joins matching call entry`() {
        val assistant = Message.Assistant(
            content = null,
            toolCalls = listOf(
                com.yangz3720.simplywork.infrastructure.model.model.ToolCall(id = "c1", name = "sys_clipboard_read", argumentsJson = "{}")
            )
        )
        val call = node("a", SessionNodeType.ToolCall) { messagePayload(assistant) }
        val result = node("r", SessionNodeType.ToolResult) {
            messagePayload(Message.ToolResult(toolCallId = "c1", toolName = "sys_clipboard_read", content = "剪贴板内容", isError = false))
        }
        val messages = render(call, result)
        assertEquals(1, messages.size)
        assertEquals("剪贴板内容", messages[0].toolResult)
        assertEquals(false, messages[0].toolResultIsError)
    }

    @Test
    fun `orphan tool result becomes standalone activity`() {
        val orphan = node("r", SessionNodeType.ToolResult) {
            messagePayload(Message.ToolResult(toolCallId = "ghost", toolName = "sys_x", content = "resumed", isError = true))
        }
        val messages = render(orphan)
        assertEquals(1, messages.size)
        assertEquals(UiMessageKind.ToolActivity, messages[0].kind)
        assertEquals("sys_x", messages[0].toolName)
        assertEquals(true, messages[0].toolResultIsError)
    }

    @Test
    fun `failed assistant node renders as error`() {
        val assistant = Message.Assistant(content = "被拒绝了", toolCalls = emptyList())
        val messages = render(
            node("a", SessionNodeType.ToolCall, SessionNodeStatus.failed) { messagePayload(assistant) }
        )
        assertEquals(1, messages.size)
        assertEquals(UiMessageKind.Assistant, messages[0].kind)
        assertTrue(messages[0].isError)
    }

    @Test
    fun `steer and summary render`() {
        val steer = node("s", SessionNodeType.Steer) { buildJsonObject { put("text", "换个方向"); put("atLoop", 3) } }
        val summary = node("m", SessionNodeType.Summary) { buildJsonObject { put("text", "摘要内容") } }
        val messages = render(steer, summary)
        assertEquals(UiMessageKind.Steer, messages[0].kind)
        assertEquals("换个方向", messages[0].text)
        assertEquals(UiMessageKind.Summary, messages[1].kind)
        assertEquals("摘要内容", messages[1].text)
    }

    @Test
    fun `unreadable payload renders explicit system note`() {
        val node = SessionNode(
            id = "broken", sessionId = "s", parentId = null, nodeType = SessionNodeType.UserPrompt,
            tokenCount = 1, status = SessionNodeStatus.success, createdAt = 1L
        )
        val messages = ConversationRenderer.render(listOf(node)) {
            throw InfrastructureException.Session.PayloadMissing(node.id)
        }
        assertEquals(1, messages.size)
        assertEquals(UiMessageKind.SystemNote, messages[0].kind)
        assertTrue(messages[0].isError)
    }

    // ----- branch info (edit and resend) -----

    @Test
    fun `user prompt carries branch index and count from sibling prompts`() {
        val u1 = node("u1", SessionNodeType.UserPrompt) { messagePayload(Message.User("原提问")) }
        val u2 = node("u2", SessionNodeType.UserPrompt) { messagePayload(Message.User("编辑后的提问")) }

        // The active path contains only the new branch u2; the full set contains both siblings
        val messages = ConversationRenderer.render(
            listOf(u2.first),
            listOf(u1.first, u2.first)
        ) { n -> listOf(u1, u2).first { it.first.id == n.id }.second }

        assertEquals(1, messages.size)
        assertEquals(UiMessageKind.User, messages[0].kind)
        assertEquals(1, messages[0].branchIndex)
        assertEquals(2, messages[0].branchCount)
    }

    @Test
    fun `single branch keeps default branch info`() {
        val messages = render(node("u1", SessionNodeType.UserPrompt) { messagePayload(Message.User("唯一提问")) })
        assertEquals(0, messages[0].branchIndex)
        assertEquals(1, messages[0].branchCount)
    }

    // ----- change cards (v1.5) -----

    private fun writeNode() = node("a", SessionNodeType.ToolCall) {
        messagePayload(
            Message.Assistant(
                content = null,
                toolCalls = listOf(ToolCall(id = "c1", name = "fs_write", argumentsJson = """{"path":"a.md"}"""))
            )
        )
    }

    private fun change(toolCallId: String?, path: String) = FileChange(
        toolCallId = toolCallId,
        sessionId = "s",
        projectId = "p",
        path = path,
        kind = FileChangeKind.Created,
        addedLines = 2,
        removedLines = 0
    )

    @Test
    fun `file change attaches to the tool activity with the same call id`() {
        val call = writeNode()
        val messages = ConversationRenderer.render(
            listOf(call.first),
            changes = listOf(change("c1", "a.md"))
        ) { call.second }

        assertEquals(1, messages.size)
        assertEquals("a.md", messages[0].fileChange?.path)
        assertEquals(FileChangeKind.Created, messages[0].fileChange?.kind)
        assertEquals(2, messages[0].fileChange?.addedLines)
    }

    @Test
    fun `change without a matching call id is not attached to anything`() {
        val call = writeNode()
        val messages = ConversationRenderer.render(
            listOf(call.first),
            changes = listOf(change("ghost", "a.md"), change(null, "b.md"))
        ) { call.second }

        assertEquals(1, messages.size)
        assertNull(messages[0].fileChange)
    }
}