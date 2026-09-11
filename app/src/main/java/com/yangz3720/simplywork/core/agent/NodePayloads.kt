package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.infrastructure.model.model.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared payload decoding: JSONL payload → text / [Message] per node type
 * (agent-core-design.md §2.1 payload table). Used by the assembler, compactor and
 * summarizers.
 */
object NodePayloads {

    val json = Json { ignoreUnknownKeys = true }

    /** Flat human-readable text of a payload (summarizer input / debugging). */
    fun flatText(node: SessionNode, payload: JsonObject): String = try {
        when (node.nodeType) {
            SessionNodeType.UserPrompt ->
                (decodeMessage(payload) as? Message.User)?.content.orEmpty()
            SessionNodeType.SystemPrompt ->
                payload["text"]?.jsonPrimitive?.content.orEmpty()
            SessionNodeType.ToolCall -> {
                val assistant = decodeMessage(payload) as? Message.Assistant
                buildString {
                    assistant?.content?.takeIf { it.isNotBlank() }?.let {
                        append(it)
                        append('\n')
                    }
                    assistant?.toolCalls?.forEach { call ->
                        append("tool_call ${call.name}(${call.argumentsJson})")
                    }
                }.trim()
            }
            SessionNodeType.ToolResult ->
                (decodeMessage(payload) as? Message.ToolResult)?.content.orEmpty()
            SessionNodeType.Steer ->
                payload["text"]?.jsonPrimitive?.content.orEmpty()
            SessionNodeType.Summary ->
                payload["text"]?.jsonPrimitive?.content.orEmpty()
        }
    } catch (_: Exception) {
        ""
    }

    /** Decodes a stored [Message] payload back into its runtime form. */
    fun decodeMessage(payload: JsonObject): Message =
        json.decodeFromString(Message.serializer(), payload.toString())
}
