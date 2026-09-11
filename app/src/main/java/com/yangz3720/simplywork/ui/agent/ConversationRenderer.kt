package com.yangz3720.simplywork.ui.agent

import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.agent.NodePayloads
import com.yangz3720.simplywork.infrastructure.model.model.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 会话 DAG 活跃路径 → [UiMessage] 列表的纯函数投影（可 JVM 测试）。
 *
 * 映射规则（简约原则）：
 * - SystemPrompt(base) 不渲染（内部注入）；follow_up 渲染为 FollowUp 条目；
 * - ToolCall 节点拆为 Assistant 正文 + N 条 ToolActivity；
 * - ToolResult 就近归并到同 toolCallId 的 ToolActivity（孤儿结果独立成条）；
 * - UserPrompt 附分支信息：[allNodes] 中同 parent 的 UserPrompt 兄弟数（编辑重发
 *   产生的多分支，VSCode-Copilot 式）。
 */
object ConversationRenderer {

    fun render(
        path: List<SessionNode>,
        allNodes: List<SessionNode> = path,
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
                                branchIndex = index, branchCount = siblings.size
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
                    // kind=base：内部注入，不渲染
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
        return messages
    }

    private fun decodeOrNull(json: JsonObject): Message? = runCatching {
        NodePayloads.decodeMessage(json)
    }.getOrNull()
}