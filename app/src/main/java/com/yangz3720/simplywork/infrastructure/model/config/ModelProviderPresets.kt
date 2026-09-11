package com.yangz3720.simplywork.infrastructure.model.config

import kotlinx.serialization.Serializable

/**
 * 一个服务商账号：预设（OpenRouter/DeepSeek/Qwen/OpenAI）或自定义端点。
 * 多服务商并存，各只保存 API Key；模型在对话界面顶栏动态选择（不再随配置固化）。
 */
@Serializable
data class ProviderAccount(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = ""
)

/** 内置 OpenAI 兼容端点预设。 */
object ModelProviderPresets {

    val ALL: List<ProviderAccount> = listOf(
        ProviderAccount(id = "openrouter", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1"),
        ProviderAccount(id = "deepseek", name = "DeepSeek", baseUrl = "https://api.deepseek.com/v1"),
        ProviderAccount(id = "qwen", name = "Qwen", baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        ProviderAccount(id = "openai", name = "OpenAI", baseUrl = "https://api.openai.com/v1")
    )

    /** 按 baseUrl 匹配预设（legacy 迁移用）。 */
    fun matchBaseUrl(baseUrl: String): ProviderAccount? =
        ALL.firstOrNull { it.baseUrl == baseUrl.trim() }
}
