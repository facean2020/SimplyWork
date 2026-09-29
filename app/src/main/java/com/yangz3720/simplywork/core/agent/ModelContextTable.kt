package com.yangz3720.simplywork.core.agent

/**
 * Built-in "model -> context length" lookup table (v1.6).
 *
 * Why it has to exist: the OpenAI spec for `/v1/models` only guarantees an `id`, and the official
 * endpoints (OpenAI / DeepSeek / Bailian) **never report a context length** -- measured on this
 * path `providerTokens` is permanently null, so the budget forever fell back to the 24k default and
 * compaction fired at entirely the wrong time. Only aggregating endpoints like OpenRouter report
 * one (`context_length`, see `OpenAiCompatibleClient.contextTokensOf`).
 *
 * Division of labour with `ContextBudget.fromModelName`: that one honours only a spec **written
 * explicitly** in the model name (`128k`/`1m`), where the endpoint is itself declaring its window,
 * the highest confidence; this table covers models "whose name says nothing but whose factory spec
 * is known". In the arbitration order it ranks **after** the provider report and the name marker
 * (see [ContextBudget.resolve]).
 *
 * Three disciplines, straight from the fact that "the table goes stale":
 *  1. **Conservative values**: when unsure, take the smaller one. Overestimating gets the request
 *     rejected outright by the server, whereas underestimating merely compacts early -- the costs
 *     are asymmetric, so we would rather underestimate.
 *  2. **Only entries with a source**: every one of them maps to an official doc below, nothing
 *     filled in from memory. A model name with no traceable source is better left out (it falls
 *     through to `Source.Default`'s 24k) than given an invented number.
 *  3. **The user can always override**: the manual setting on the settings page has the highest
 *     priority and is the escape hatch for once this table goes stale.
 *
 * Values verified on **2026-09-29**, from:
 *  - DeepSeek  https://api-docs.deepseek.com/quick_start/pricing
 *  - OpenAI    https://platform.openai.com/docs/models
 *  - Bailian/Qwen https://help.aliyun.com/zh/model-studio/text-generation-model/
 * The model catalogue moves fast (new releases make it lag), so updating means changing this one
 * file.
 */
object ModelContextTable {

    /**
     * Matching: **case-insensitive prefix match, longest hit wins**.
     *
     * Prefixes rather than exact equality, so that snapshot/date suffixes are covered (names like
     * `qwen3.8-max-0902`, `gpt-4o-2024-08-06`). Taking the longest hit is essential:
     * `qwen-plus-character` (32k) has to beat `qwen-plus` (1M), otherwise we would overestimate by
     * 32x -- precisely the direction discipline 1 exists to avoid.
     */
    private data class Entry(val prefix: String, val tokens: Int)

    fun lookup(modelId: String): Int? {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return null
        return ENTRIES
            .filter { id.startsWith(it.prefix) }
            .maxByOrNull { it.prefix.length }
            ?.tokens
    }

    private val ENTRIES: List<Entry> = listOf(
        // ----- DeepSeek (the official endpoint's /models reports no context) -----
        Entry("deepseek-flash", 1_000_000),
        Entry("deepseek-v4-pro", 1_000_000),
        // Retired -legacy name, the official docs still accept it and V4.1-Flash takes it over, also 1M
        Entry("deepseek-v4-flash", 1_000_000),

        // ----- OpenAI flagships (GPT-6 family, all 1.05M) -----
        Entry("gpt-6-astra", 1_050_000),
        Entry("gpt-6-sol", 1_050_000),
        Entry("gpt-6-luna", 1_050_000),

        // ----- Qwen (Bailian): currently recommended -----
        Entry("qwen3.8-max", 1_000_000),
        Entry("qwen3.8-flash", 1_000_000),
        Entry("qwen3.7-plus", 1_000_000),
        Entry("qwen3.7-flash", 1_000_000),
        Entry("qwen3-coder-plus", 1_000_000),
        Entry("qwen3-coder-flash", 1_000_000),

        // ----- Qwen: the previous generation or two (still common on existing API keys) -----
        Entry("qwen3.7-max", 1_000_000),
        Entry("qwen3.6-plus", 1_000_000),
        Entry("qwen3.6-flash", 1_000_000),
        Entry("qwen3.6-max", 256_000),
        Entry("qwen3.5-plus", 1_000_000),
        Entry("qwen3.5-flash", 1_000_000),
        Entry("qwen3.5-397b", 256_000),
        Entry("qwen3.5-122b", 256_000),
        Entry("qwen3.5-35b", 256_000),
        Entry("qwen3.5-27b", 256_000),
        Entry("qwen3-coder-next", 256_000),
        Entry("qwen3-max", 256_000),
        Entry("qwen3-next-80b", 128_000),
        Entry("qwen3-235b-a22b", 128_000),
        Entry("qwen3-32b", 128_000),
        Entry("qwen3-30b-a3b-thinking", 80_000),
        Entry("qwen3-30b-a3b", 128_000),
        Entry("qwen3-14b", 128_000),
        Entry("qwen3-8b", 128_000),

        // ----- Qwen: legacy naming and specialised models -----
        // Note order is irrelevant (longest hit wins), but the character series must coexist with qwen-plus / qwen-flash
        Entry("qwen-plus-character", 32_000),
        Entry("qwen-flash-character", 8_000),
        Entry("qwen-plus", 1_000_000),
        Entry("qwen-flash", 1_000_000),
        Entry("qwen-turbo", 128_000),
        Entry("qwen-max", 32_000),
        Entry("qwen-long", 10_000_000),
        Entry("qwen-mt-", 16_000),
        Entry("qwen2.5-omni-7b", 32_000),
        Entry("qwq-plus", 128_000),
        Entry("qvq-max", 128_000)
    )
}
