package com.yangz3720.simplywork.infrastructure.model.config

import kotlinx.serialization.Serializable

/**
 * One provider account: a preset (OpenRouter/DeepSeek/Qwen/OpenAI) or a custom endpoint.
 * Several providers coexist, each storing only its API key; the model is picked dynamically in the chat UI top bar (no longer baked into the config).
 */
@Serializable
data class ProviderAccount(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = ""
)

/** Built-in OpenAI-compatible endpoint presets. */
object ModelProviderPresets {

    val ALL: List<ProviderAccount> = listOf(
        ProviderAccount(id = "openrouter", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1"),
        ProviderAccount(id = "deepseek", name = "DeepSeek", baseUrl = "https://api.deepseek.com/v1"),
        ProviderAccount(id = "qwen", name = "Qwen", baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        ProviderAccount(id = "openai", name = "OpenAI", baseUrl = "https://api.openai.com/v1")
    )

    /** Match a preset by baseUrl (used by legacy migration). */
    fun matchBaseUrl(baseUrl: String): ProviderAccount? =
        ALL.firstOrNull { it.baseUrl == baseUrl.trim() }
}
