package com.yangz3720.simplywork.infrastructure.model.model

import kotlinx.serialization.Serializable

/**
 * Token usage accounting.
 *
 * The three fields OpenAI's spec guarantees are only the beginning: every vendor that matters appends its own
 * counters (measured 2026-10 across OpenRouter / DeepSeek / Qwen). The extras here are the ones that survive
 * across all three, so they can be shown without asking "which provider is this":
 *
 *  - [cachedTokens] -- present in all three as `prompt_tokens_details.cached_tokens`. DeepSeek additionally reports
 *    `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`; they carry the same information, so only the common
 *    shape is modelled.
 *  - [reasoningTokens] -- all three as `completion_tokens_details.reasoning_tokens`. Worth separating from
 *    [completionTokens] because thinking tokens are billed but are not the answer the user sees.
 *  - [costUsd] -- **OpenRouter only**, as a float in USD. This is the one vendor that tells you what the request
 *    actually cost instead of making you multiply tokens by a price table; elsewhere it stays `null` rather than
 *    being estimated into existence.
 */
@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = promptTokens + completionTokens,
    /** Input tokens served from the provider's cache (billed at a lower rate, or not at all). */
    val cachedTokens: Int = 0,
    /** Of [completionTokens], how many were reasoning/thinking tokens. */
    val reasoningTokens: Int = 0,
    /** Actual cost of this request in USD, when the provider reports it (OpenRouter). */
    val costUsd: Double? = null
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
