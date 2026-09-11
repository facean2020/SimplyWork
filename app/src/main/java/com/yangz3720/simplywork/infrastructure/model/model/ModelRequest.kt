package com.yangz3720.simplywork.infrastructure.model.model

import kotlinx.serialization.Serializable

/**
 * Tool choice policy for model generation.
 */
@Serializable
sealed interface ToolChoice {
    @Serializable data object Auto : ToolChoice
    @Serializable data object None : ToolChoice
    @Serializable data object Required : ToolChoice
    @Serializable data class Specific(val toolName: String) : ToolChoice
}

/**
 * Unified request payload passed to a [com.yangz3720.simplywork.infrastructure.model.ModelProvider].
 */
@Serializable
data class ModelRequest(
    val messages: List<Message>,
    val tools: List<ToolDefinition> = emptyList(),
    val model: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val stopSequences: List<String> = emptyList(),
    val systemPromptOverride: String? = null,
    val toolChoice: ToolChoice = ToolChoice.Auto
)

/**
 * Unified response payload returned from a single-turn model invocation.
 */
@Serializable
data class ModelResponse(
    val id: String,
    val model: String,
    val message: Message.Assistant,
    val finishReason: FinishReason,
    val usage: TokenUsage = TokenUsage(),
    val latencyMs: Long = 0L,
    val cachedTokens: Int = 0
) {
    val hasToolCalls: Boolean get() = message.toolCalls.isNotEmpty()
    val toolCalls: List<ToolCall> get() = message.toolCalls
    val text: String get() = message.content.orEmpty()
    val isFinalAnswer: Boolean get() = finishReason == FinishReason.STOP && !hasToolCalls
}
