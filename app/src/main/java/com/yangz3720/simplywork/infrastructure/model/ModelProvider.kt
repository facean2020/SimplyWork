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
     * The full entry of GET /models (including the context length the vendor reports, if any).
     *
     * Defaults to degrading from [listModels]: a provider that doesn't implement it doesn't fail, it just can't get a
     * context length, and callers take the "spec from the model name / default value" route. [OpenAiCompatibleClient]
     * in turn is a one-way delegation (catalog is the real impl and listModels is derived from it), so neither recurses.
     */
    suspend fun listModelCatalog(): Result<List<ModelInfo>> =
        listModels().map { ids -> ids.map { ModelInfo(it) } }
}
