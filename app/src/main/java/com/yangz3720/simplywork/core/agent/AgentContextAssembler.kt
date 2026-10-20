package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.agent.tool.AgentToolRegistry
import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.session.SessionRepository
import com.yangz3720.simplywork.core.session.SystemPromptKind
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.WorkspaceAgentMd
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.model.model.Message
import com.yangz3720.simplywork.infrastructure.model.model.ModelRequest
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Convert format" step (flow node F): active path + State Memory → [ModelRequest]
 * (agent-core-design.md §5, §6 step ⑥).
 *
 * Injection order (top-down):
 * 1. System message: base system prompt + skill fragment + State Memory block (forced, every loop)
 * 2. Episode summaries (Summary nodes) as System messages
 * 3. All remaining nodes in chronological order (working memory + not-yet-compacted rounds)
 *
 * Pairing repair: an Assistant message with tool calls must be followed by one Tool
 * message per call id (OpenAI-compatible APIs enforce this). Missing pairs — e.g. a
 * crash between ToolCall and ToolResult before the resume cleanup could run — get a
 * synthetic error Tool message injected here, so the request is always valid.
 */
class AgentContextAssembler(
    private val repository: SessionRepository,
    private val registry: AgentToolRegistry,
    private val config: AgentConfig,
    private val soul: AgentSoul,
    private val workspace: AgentWorkspace
) {

    suspend fun assemble(sessionId: String, path: List<SessionNode>): ModelRequest {
        val projectId = repository.getSession(sessionId)?.projectId
        val state = repository.loadState(sessionId)
        val systemText = buildString {
            append(config.systemPrompt)
            append("\n\n")
            if (projectId != null) {
                soul.loadProjectSoul(projectId)
                    ?.let { append(it).append("\n\\n") }
                WorkspaceAgentMd.loadAgentMd(workspace.projectDir(projectId, false))
                    ?.let { append(it).append("\n\n") }
            }
            append(state.toPromptBlock())
        }

        val messages = ArrayList<Message>(path.size + 1)
        messages.add(Message.System(content = systemText))

        for (node in path) {
            when (node.nodeType) {
                SessionNodeType.SystemPrompt -> {
                    // Base system prompt is represented by the injected system message;
                    // follow-ups are re-injected as user-role instructions.
                    val payload = repository.payload(node)
                    val kind = payload["kind"]?.jsonPrimitive?.content
                    if (kind == SystemPromptKind.follow_up.name) {
                        val text = payload["text"]?.jsonPrimitive?.content.orEmpty()
                        messages.add(Message.User(content = "[system follow-up] $text"))
                    }
                }
                else -> nodeToMessage(node)?.let(messages::add)
            }
        }

        return ModelRequest(
            messages = repairToolPairing(messages),
            tools = registry.definitions()
        )
    }

    /** Node → conversation message; null = node contributes no message (skipped defensively). */
    private suspend fun nodeToMessage(node: SessionNode): Message? = try {
        val payload = repository.payload(node)
        when (node.nodeType) {
            SessionNodeType.UserPrompt -> {
                val user = NodePayloads.decodeMessage(payload) as? Message.User
                    ?: return null
                // Per-turn time anchor (v1.2): exactly one human-readable line per user turn —
                // not per node. A wall-clock stamp is what the model can reason with ("yesterday",
                // "this Friday"); raw epoch ms is not. Zone rules resolve DST into the printed
                // offset, so no local DST knowledge is needed anywhere in the pipeline.
                Message.User(content = "[message time: ${turnTime(node.createdAt)}]\n${user.content}")
            }
            SessionNodeType.Steer -> Message.User(content = "[steering] ${payload["text"]?.jsonPrimitive?.content.orEmpty()}")
            SessionNodeType.ToolCall -> NodePayloads.decodeMessage(payload) as? Message.Assistant
            SessionNodeType.ToolResult -> NodePayloads.decodeMessage(payload) as? Message.ToolResult
            SessionNodeType.Summary -> Message.System(
                content = "Summary of earlier conversation (episode memory):\n${payload["text"]?.jsonPrimitive?.content.orEmpty()}"
            )
            SessionNodeType.SystemPrompt -> null
        }
    } catch (_: InfrastructureException.Session) {
        null // payload missing/corrupt: skip the node rather than break the request (logged upstream)
    }

    /** "2025-06-12 Thu 14:35 UTC+08:00" — wall clock on purpose; the model thinks in dates, not epoch. */
    private fun turnTime(epochMs: Long): String {
        val zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        val stamp = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.ENGLISH))
        val offset = zdt.offset.id.let { if (it == "Z") "+00:00" else it }
        return "$stamp UTC$offset"
    }

    /** Ensures every assistant tool call has a matching Tool message directly after it. */
    private fun repairToolPairing(messages: List<Message>): List<Message> {
        val repaired = ArrayList<Message>(messages.size)
        var i = 0
        while (i < messages.size) {
            val message = messages[i]
            repaired.add(message)
            if (message is Message.Assistant && message.toolCalls.isNotEmpty()) {
                i++
                val expected = message.toolCalls.map { it.id }.toMutableSet()
                while (i < messages.size && messages[i] is Message.ToolResult) {
                    val tool = messages[i] as Message.ToolResult
                    expected.remove(tool.toolCallId)
                    repaired.add(tool)
                    i++
                }
                // Missing results (crash between ToolCall and ToolResult) → synthetic error tool messages.
                expected.forEach { callId ->
                    repaired.add(
                        Message.ToolResult(
                            toolCallId = callId,
                            toolName = "unknown",
                            content = "No result was produced for this tool call (interrupted).",
                            isError = true
                        )
                    )
                }
                continue
            }
            i++
        }
        return repaired
    }
}
