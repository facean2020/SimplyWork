package com.yangz3720.simplywork.infrastructure.model.model

import kotlinx.serialization.Serializable

/**
 * Token usage accounting.
 */
@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = promptTokens + completionTokens
)

/**
 * Reason the model stopped generating.
 */
@Serializable
enum class FinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    ERROR,
    UNKNOWN
}
