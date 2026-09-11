package com.yangz3720.simplywork.infrastructure.model.config

import kotlinx.serialization.Serializable

/**
 * Supported capabilities by a ModelProvider.
 */
enum class ModelCapability {
    STREAMING,
    TOOL_CALLING,
    REASONING_CONTENT,
    VISION,
    SYSTEM_PROMPT,
    OFFLINE_ROUTING,
    CONTEXT_COMPACTION
}

/**
 * Model provider connection and execution configuration.
 */
@Serializable
data class ModelConfig(
    val baseUrl: String,
    val apiKey: String = "",
    val defaultModel: String,
    val timeoutMs: Long = 60_000L,
    val maxRetries: Int = 2,
    val customHeaders: Map<String, String> = emptyMap()
)
