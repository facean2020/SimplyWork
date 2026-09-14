package com.yangz3720.simplywork.infrastructure.model

/**
 * `GET /models` 的一个条目。
 *
 * [contextTokens] 是**可选**的：OpenAI 的规范只保证 `id`，只有部分兼容端点
 * （OpenRouter、llama.cpp、自建 vLLM…）额外报上下文长度。null 的含义是"这个端点没说"，
 * 不是 0 —— 上层据此退回"从模型名读"或"用默认值"，而不是把一个编出来的数当成事实。
 */
data class ModelInfo(val id: String, val contextTokens: Int? = null)
