package com.yangz3720.simplywork.infrastructure.model

/**
 * One entry of `GET /models`.
 *
 * [contextTokens] is **optional**: OpenAI's spec only guarantees `id`, and only some compatible endpoints (OpenRouter,
 * llama.cpp, self-hosted vLLM...) additionally report a context length. null means "this endpoint didn't say", not 0
 * -- callers use it to fall back to "read it off the model name" or "use the default", rather than treating an invented number as fact.
 */
data class ModelInfo(val id: String, val contextTokens: Int? = null)
