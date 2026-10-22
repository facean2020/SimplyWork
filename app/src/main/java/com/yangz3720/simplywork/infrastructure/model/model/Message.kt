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

    /**
     * An uploaded image held by reference (v1.9): [id] keys a binary WebP blob in
     * `ImageBlobStore`, never base64 in the JSONL. Bytes are loaded and base64-encoded only
     * when a model request is formatted. [name] is the original file name -- kept for the
     * transcript, never sent to the provider.
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val id: String,
        val mimeType: String,
        val name: String? = null
    ) : Attachment

    /**
     * An uploaded document whose text was already extracted (v1.8): sent to the model as an extra text part, so a
     * format (pdf/word later) only has to produce [text] -- the request format never changes.
     */
    @Serializable
    @SerialName("document")
    data class Document(
        val name: String,
        val text: String,
        /** The source was longer than the extractor's cap and only its head is here. */
        val truncated: Boolean = false
    ) : Attachment
}
