package com.yangz3720.simplywork.ui.agent

import com.yangz3720.simplywork.core.agent.NodePayloads
import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.infrastructure.model.model.Attachment
import com.yangz3720.simplywork.infrastructure.model.model.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A pure-function projection of the session DAG's active path -> a list of [UiMessage] (JVM-testable).
 *
 * Mapping rules (principle of parsimony):
 * - SystemPrompt(base) is not rendered (injected internally); follow_up renders as a FollowUp entry;
 * - a ToolCall node is split into Assistant body text + N ToolActivity entries;
 * - ToolResult is merged nearby into the ToolActivity with the same toolCallId (orphan results become their own entry);
 * - UserPrompt carries branch info: the number of UserPrompt siblings sharing the same parent in [allNodes] (the multiple branches
 *   produced by edit-and-resend, VSCode-Copilot style);
 * - v1.5: fs_* [changes] are pasted back onto the matching tool entry by toolCallId (a Codex-style diff card).
 */
object ConversationRenderer {

    fun render(
        path: List<SessionNode>,
        allNodes: List<SessionNode> = path,
        changes: List<FileChange> = emptyList(),
        payload: (SessionNode) -> JsonObject
    ): List<UiMessage> {
        val messages = ArrayList<UiMessage>(path.size)
        for (node in path) {
            val json: JsonObject = try {
                payload(node)
            } catch (e: Exception) {
                messages.add(UiMessage(node.id, UiMessageKind.SystemNote, "节点 payload 不可读（${e.message ?: "未知原因"}）", node.createdAt, isError = true))
                continue
            }
            when (node.nodeType) {
                SessionNodeType.UserPrompt -> {
                    val siblings = allNodes
                        .filter { it.nodeType == SessionNodeType.UserPrompt && it.parentId == node.parentId }
                        .sortedBy { it.createdAt }
                    val index = siblings.indexOfFirst { it.id == node.id }.coerceAtLeast(0)
                    (decodeOrNull(json) as? Message.User)?.let {
                        messages.add(
                            UiMessage(
                                node.id, UiMessageKind.User, it.content, node.createdAt,
                                branchIndex = index, branchCount = siblings.size,
                                // v1.8: uploads render as chips, never as raw payload.
                                attachments = it.attachments.map(::toUiAttachment)
                            )
                        )
                    }
                }

                SessionNodeType.SystemPrompt -> {
                    val kind = runCatching { json["kind"]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")
                    val text = runCatching { json["text"]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")
                    if (kind == "follow_up" && text.isNotBlank()) {
                        messages.add(UiMessage(node.id, UiMessageKind.FollowUp, text, node.createdAt))
                    }
                    // kind=base: injected internally, not rendered
                }

                SessionNodeType.ToolCall -> {
                    val assistant = decodeOrNull(json) as? Message.Assistant
                    if (assistant == null) {
                        messages.add(UiMessage(node.id, UiMessageKind.SystemNote, "助手节点不可读", node.createdAt, isError = true))
                    } else {
                        if (assistant.content.orEmpty().isNotBlank()) {
                            messages.add(
                                UiMessage(node.id, UiMessageKind.Assistant, assistant.content.orEmpty(), node.createdAt, isError = node.status == SessionNodeStatus.failed)
                            )
                        }
                        assistant.toolCalls.forEach { call ->
                            messages.add(
                                UiMessage(
                                    id = node.id,
                                    kind = UiMessageKind.ToolActivity,
                                    text = "",
                                    createdAt = node.createdAt,
                                    toolCallId = call.id,
                                    toolName = call.name,
                                    toolArgs = call.argumentsJson
                                )
                            )
                        }
                    }
                }

                SessionNodeType.ToolResult ->
                    (decodeOrNull(json) as? Message.ToolResult)?.let { result ->
                        val target = messages.lastOrNull {
                            it.kind == UiMessageKind.ToolActivity && it.toolCallId == result.toolCallId
                        }
                        if (target != null) {
                            val index = messages.indexOf(target)
                            messages[index] = target.copy(toolResult = result.content, toolResultIsError = result.isError || target.toolResultIsError)
                        } else {
                            messages.add(
                                UiMessage(
                                    id = node.id,
                                    kind = UiMessageKind.ToolActivity,
                                    text = "",
                                    createdAt = node.createdAt,
                                    toolName = result.toolName,
                                    toolResult = result.content,
                                    toolResultIsError = result.isError
                                )
                            )
                        }
                    }

                SessionNodeType.Steer ->
                    messages.add(
                        UiMessage(node.id, UiMessageKind.Steer, runCatching { json["text"]?.jsonPrimitive?.content.orEmpty() }.getOrDefault(""), node.createdAt)
                    )

                SessionNodeType.Summary ->
                    messages.add(
                        UiMessage(node.id, UiMessageKind.Summary, runCatching { json["text"]?.jsonPrimitive?.content.orEmpty() }.getOrDefault(""), node.createdAt)
                    )
            }
        }
        return attachFileChanges(messages, changes)
    }

    /**
     * Paste changes onto the matching tool entry (v1.5). Matched exactly by toolCallId, so a single tool call carries at most one change;
     * changes that do not match are **not rendered** -- attaching them in the wrong place is worse than not attaching them (the reader would think a different file was changed).
     */
    private fun attachFileChanges(
        messages: List<UiMessage>,
        changes: List<FileChange>
    ): List<UiMessage> {
        if (changes.isEmpty() || messages.isEmpty()) return messages
        val byCall = changes.mapNotNull { change -> change.toolCallId?.let { it to change } }.toMap()
        return messages.map { message ->
            byCall[message.toolCallId]?.let { message.copy(fileChange = it) } ?: message
        }
    }

    /** Payload → display: images carry their blob id for the thumbnail; documents are name-only chips. */
    private fun toUiAttachment(attachment: Attachment): UiAttachment = when (attachment) {
        is Attachment.Image -> UiAttachment(
            name = attachment.name ?: "已上传",
            image = true,
            imageId = attachment.id
        )
        is Attachment.Document -> UiAttachment(name = attachment.name, image = false, truncated = attachment.truncated)
    }

    private fun decodeOrNull(json: JsonObject): Message? = runCatching {
        NodePayloads.decodeMessage(json)
    }.getOrNull()
}