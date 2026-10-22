package com.yangz3720.simplywork.infrastructure.model

/**
 * One entry of `GET /models`, carrying whatever the vendor was willing to disclose beyond OpenAI's `id`.
 *
 * Every field past [id] is **optional on purpose**: the OpenAI spec guarantees only an id, and the four built-in
 * vendors disagree wildly about the rest (measured 2026-10: OpenRouter reports context/pricing/modalities for all
 * 468 of its models, DeepSeek reports context and output limits for its 2, Qwen reports nothing at all for its 178).
 * `null` means "this endpoint didn't say" -- never 0, never a guess, because a caller has to be able to tell
 * "unknown" apart from "known to be zero" and fall back accordingly.
 *
 * The two boolean capabilities have **deliberately opposite defaults**, because getting them wrong costs in
 * opposite directions:
 *
 *  - [supportsVision] defaults to `false`. Attaching an image to a model that cannot read one is rejected by the
 *    server outright, and the user has already picked the file by then. Under-reporting merely hides a button that
 *    would have worked, so when unsure we say no.
 *  - [supportsTools] defaults to `true`. Tool calling is the agent loop itself; defaulting it off would make every
 *    model of every endpoint that doesn't publish `supported_parameters` (DeepSeek and Qwen both don't) look
 *    unusable, which is a far worse failure than sending a tool schema that a model then ignores.
 */
data class ModelInfo(
    val id: String,
    /** Context window the vendor reports, if any. */
    val contextTokens: Int? = null,
    /** Upper bound on generated tokens the vendor reports, if any. */
    val maxOutputTokens: Int? = null,
    /** Whether this model accepts image input. See the class comment for why this defaults to `false`. */
    val supportsVision: Boolean = false,
    /** Whether this model accepts tool definitions. See the class comment for why this defaults to `true`. */
    val supportsTools: Boolean = true,
    /** Price per million tokens, when the vendor publishes one (in practice: only OpenRouter). */
    val pricing: ModelPricing? = null
)

/**
 * Model pricing, normalised to **USD per 1 million tokens**.
 *
 * Normalising at the parsing boundary is the point: OpenRouter quotes USD per *single* token as a **string**
 * (`"0.00000015"` = $0.15/M), and every UI wants per-million anyway. Converting once here keeps the float
 * arithmetic (and its rounding) out of the UI layer.
 */
data class ModelPricing(
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    /** Cached-input price, when the vendor breaks it out; `null` = not published. */
    val cacheReadPerMillion: Double? = null
)
