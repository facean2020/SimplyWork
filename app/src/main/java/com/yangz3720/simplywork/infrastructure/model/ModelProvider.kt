package com.yangz3720.simplywork.infrastructure.model

import com.yangz3720.simplywork.infrastructure.model.config.ModelCapability
import com.yangz3720.simplywork.infrastructure.model.model.ModelRequest
import com.yangz3720.simplywork.infrastructure.model.model.ModelResponse
import com.yangz3720.simplywork.infrastructure.model.model.ModelStreamChunk
import kotlinx.coroutines.flow.Flow

/**
 * Unified gateway interface for LLM invocation.
 */
interface ModelProvider {

    /** Identifier of the model provider (e.g. "openai-compatible", "deepseek", "local-qwen") */
    val providerId: String

    /** Declared capabilities */
    val supportedCapabilities: Set<ModelCapability>

    /**
     * Executes single-turn model generation.
     */
    suspend fun generate(request: ModelRequest): Result<ModelResponse>

    /**
     * Executes streaming model generation returning a cold Flow of [ModelStreamChunk].
     */
    fun generateStream(request: ModelRequest): Flow<ModelStreamChunk>

    /**
     * Lists model ids available at this endpoint (OpenAI-compatible GET /models).
     * Default: unsupported — concrete providers override.
     */
    suspend fun listModels(): Result<List<String>> =
        Result.failure(UnsupportedOperationException("listModels not supported by $providerId"))

    /**
     * GET /models 的完整条目（含供应商报的上下文长度，可能没有）。
     *
     * 默认由 [listModels] 退化而来：没实现它的 provider 不会因此失败，只是拿不到
     * 上下文长度，上层会走"模型名规格 / 默认值"那条路。反过来 [OpenAiCompatibleClient]
     * 是单向委托（catalog 为真实现、listModels 由它派生），两边不会互相递归。
     */
    suspend fun listModelCatalog(): Result<List<ModelInfo>> =
        listModels().map { ids -> ids.map { ModelInfo(it) } }
}
