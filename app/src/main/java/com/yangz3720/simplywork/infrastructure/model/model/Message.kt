package com.yangz3720.simplywork.infrastructure.model.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unified representation of chat messages in the Agent Loop.
 */
@Serializable
sealed interface Message {

    /**
     * System prompt setting role, persona, and instructions.
     */
    @Serializable
    @SerialName("system")
    data class System(
        val content: String,
        val name: String? = null
    ) : Message

    /**
     * User prompt or steering injection.
     */
    @Serializable
    @SerialName("user")
    data class User(
        val content: String,
        val attachments: List<Attachment> = emptyList()
    ) : Message

    /**
     * Model assistant response, possibly containing text, tool calls, and/or reasoning.
     */
    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val content: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoningContent: String? = null
    ) : Message

    /**
     * Result of executing a tool call, fed back into the conversation context.
     */
    @Serializable
    @SerialName("tool")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: String,
        val isError: Boolean = false
    ) : Message
}

/**
 * Multimodal input attachment (e.g. image, file preview).
 */
@Serializable
sealed interface Attachment {

    @Serializable
    @SerialName("image")
    data class Image(
        val mimeType: String,
        val base64Data: String
    ) : Attachment
}
