package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.infrastructure.model.ModelIdNormalizer

/**
 * Built-in "model -> context length" lookup table (v1.6, rebuilt on measured data in v1.9).
 *
 * Why it has to exist: the OpenAI spec for `/v1/models` only guarantees an `id`, and most official endpoints
 * report **no context length at all** -- measured 2026-10-21, Qwen's `/models` returns nothing beyond
 * `id`/`object`/`created`/`owned_by` for all 178 entries and OpenAI's returns nothing beyond
 * `id`/`created`/`owned_by`. On those endpoints `providerTokens` is permanently null, so without this table the
 * budget forever fell back to the 24k default and compaction fired at entirely the wrong time. (DeepSeek and
 * OpenRouter *do* report one -- `context_window` / `context_length`, see
 * `OpenAiCompatibleClient.contextTokensOf` -- which puts them on the `Provider` rung ahead of this table.)
 *
 * Division of labour with `ContextBudget.fromModelName`: that one honours only a spec **written explicitly** in
 * the model name (`128k`/`1m`), where the endpoint is itself declaring its window, the highest confidence; this
 * table covers models "whose name says nothing but whose factory spec is known". In the arbitration order it
 * ranks **after** the provider report and the name marker (see [ContextBudget.resolve]).
 *
 * Matching is done on [ModelIdNormalizer] tokens, so the spelling doesn't matter: `qwen3.6-plus`,
 * `qwen-3.6-plus`, `qwen 3.6 plus` and `Qwen/Qwen3.6-Plus` all hit the same entry, and an entry may skip the
 * version in the middle of a name (`glm-4v` also covers `glm-4.5v`).
 *
 * Three disciplines, straight from the fact that "the table goes stale":
 *  1. **Conservative values**: when two sources disagree, take the smaller one. Overestimating gets the request
 *     rejected outright by the server, whereas underestimating merely compacts early -- the costs are
 *     asymmetric, so we would rather underestimate.
 *  2. **Only measured or documented values**: the OpenAI/Anthropic/Google/xAI/Zhipu/Moonshot/MiniMax/Meta/
 *     Mistral/Amazon/Cohere/Perplexity entries added in v1.9 were read off **OpenRouter's live `/models`**
 *     (2026-10-21) and cross-checked against the Bailian catalogue for the models both serve; the DeepSeek and
 *     Qwen blocks below predate that and come from the official pricing/spec pages. Nothing is filled in from
 *     memory.
 *  3. **The user can always override**: the manual setting on the settings page has the highest priority and is
 *     the escape hatch for once this table goes stale.
 *
 * Sources, verified 2026-10-21:
 *  - OpenRouter  https://openrouter.ai/api/v1/models   (468 models, `context_length`, no key needed)
 *  - Bailian/Qwen https://help.aliyun.com/zh/model-studio/text-generation-model/  (+ live `/models`)
 *  - DeepSeek  https://api-docs.deepseek.com/quick_start/pricing
 *  - OpenAI    https://platform.openai.com/docs/models
 *  - Anthropic https://docs.anthropic.com/en/docs/about-claude/models
 *  - Google    https://ai.google.dev/gemini-api/docs/models
 *  - Zhipu     https://docs.bigmodel.cn/cn/guide/models/text/glm-4.6
 *  - Moonshot  https://platform.moonshot.cn/docs/intro
 *  - xAI       https://docs.x.ai/docs/models
 * The model catalogue moves fast (new releases make it lag), so updating means changing this one file.
 */
object ModelContextTable {

    /**
     * Matching: **prefix over normalised tokens, longest hit wins**, ties broken by the entry that needed fewer
     * skipped version tokens.
     *
     * Taking the longest hit is essential: `qwen-plus-character` (32k) has to beat `qwen-plus` (1M), otherwise we
     * would overestimate by 32x -- precisely the direction discipline 1 exists to avoid. The skip tie-break is
     * what keeps `qwen3.8` (0 skips) ahead of the unrelated legacy `qwen3-8b` (1 skip) for `qwen3.8-27b`.
     */
    fun lookup(modelId: String): Int? {
        val tokens = ModelIdNormalizer.tokens(modelId)
        if (tokens.isEmpty()) return null
        return ENTRIES
            .mapNotNull { entry -> ModelIdNormalizer.matchSkips(tokens, entry.tokens)?.let { entry to it } }
            .maxWithOrNull(compareBy({ it.first.tokens.size }, { -it.second }))
            ?.first
            ?.contextTokens
    }

    private class Entry(prefix: String, val contextTokens: Int) {
        val tokens: List<String> = ModelIdNormalizer.tokens(prefix)
    }

    private val ENTRIES: List<Entry> = listOf(
        // ----- DeepSeek (`/models` reports `context_window` now, so this is only the fallback) -----
        Entry("deepseek-flash", 1_000_000),
        Entry("deepseek-v4-pro", 1_000_000),
        // Retired -legacy name, the official docs still accept it and V4.1-Flash takes it over, also 1M
        Entry("deepseek-v4-flash", 1_000_000),
        // V3.x / R1 line, 163,840 measured
        Entry("deepseek-chat", 163_840),
        Entry("deepseek-reasoner", 163_840),
        Entry("deepseek-r1", 163_840),
        Entry("deepseek-v3", 163_840),

        // ----- OpenAI -----
        // GPT-6 family, 1.05M
        Entry("gpt-6", 1_050_000),
        // 5.4 and later got the 1.05M window; 5.0-5.3 had 400k, and the small/image variants less still
        Entry("gpt-5.4", 1_050_000),
        Entry("gpt-5.5", 1_050_000),
        Entry("gpt-5.6", 1_050_000),
        Entry("gpt-5.4-mini", 400_000),
        Entry("gpt-5.4-nano", 400_000),
        Entry("gpt-5.4-image", 272_000),
        Entry("gpt-5.2-chat", 128_000),
        Entry("gpt-5", 400_000),
        Entry("gpt-chat", 400_000),
        Entry("gpt-4.1", 1_047_576),
        Entry("gpt-4o", 128_000),
        Entry("gpt-4-turbo", 128_000),
        // Legacy gpt-4 / 3.5, all tiny -- and the two 4k variants exist, so they are spelled out rather than
        // letting the 16k one cover them
        Entry("gpt-3.5-turbo-0613", 4_095),
        Entry("gpt-3.5-turbo-instruct", 4_095),
        Entry("gpt-3.5-turbo", 16_385),
        Entry("gpt-4", 8_191),
        // Open-weight gpt-oss, text only, 131,072
        Entry("gpt-oss", 131_072),
        // The o-series reasoning models are all 200k
        Entry("o1", 200_000),
        Entry("o3", 200_000),
        Entry("o4", 200_000),

        // ----- Anthropic: 200k across the 3.x/4.x lines, 1M from sonnet-4.5 / opus-4.6 onwards. The 200k
        // exceptions are spelled out because they sit under the 1M default. -----
        Entry("claude", 1_000_000),
        Entry("claude-3", 200_000),
        Entry("claude-4.1", 200_000),
        Entry("claude-opus-4.1", 200_000),
        Entry("claude-opus-4.5", 200_000),
        Entry("claude-haiku-4.5", 200_000),
        Entry("claude-2", 100_000),

        // ----- Google: 1M input across the 1.5/2/3/4 lines. The image-output models are far smaller, and
        // `gemini-1.0-pro` (32k) would otherwise inherit the 1M default. -----
        Entry("gemini", 1_048_576),
        Entry("gemini-1.0", 32_768),
        Entry("gemini-pro-vision", 12_288),
        Entry("gemini-2.5-flash-image", 32_768),
        Entry("gemini-nano-banana", 65_536),
        Entry("gemini-3.1-flash-lite-image", 65_536),
        Entry("gemini-3.1-flash-image", 65_536),
        Entry("gemini-3-pro-image", 65_536),
        Entry("gemma-2", 8_192),
        Entry("gemma-3", 131_072),
        Entry("gemma-4", 262_144),

        // ----- xAI: 4.3 is 1M, 4.5-4.7 are 500k, 4.20 is 2M; the plain `grok` base stays at the smallest
        // served window so an unknown Grok is never overestimated -----
        Entry("grok", 256_000),
        Entry("grok-2", 131_072),
        Entry("grok-3", 131_072),
        Entry("grok-4", 256_000),
        Entry("grok-4.3", 1_000_000),
        Entry("grok-4.5", 500_000),
        Entry("grok-4.6", 500_000),
        Entry("grok-4.7", 500_000),
        Entry("grok-4.20", 2_000_000),

        // ----- Zhipu GLM. `glm-4v` has to sit here with its own (small) value, otherwise it would be caught by
        // `glm-4` and overestimate by 16x -----
        Entry("glm-4", 128_000),
        Entry("glm-4v", 8_192),
        Entry("glm-4.5v", 65_536),
        Entry("glm-4.6v", 131_072),
        Entry("glm-4.5", 131_072),
        Entry("glm-4.6", 204_800),
        Entry("glm-4.7", 204_800),
        Entry("glm-4.7-flash", 200_000),
        Entry("glm-4-long", 1_000_000),
        Entry("glm-5", 204_800),
        Entry("glm-5-turbo", 202_752),
        Entry("glm-5v", 202_752),
        Entry("glm-5.2", 1_048_576),
        Entry("glm-5.3", 1_048_576),
        Entry("glm-5.3-prime", 1_000_000),

        // ----- Moonshot / Kimi. `moonshot-v1-*` writes its window in the name (`-8k`/`-32k`/`-128k`), which
        // `fromModelName` already wins on. K2 is 131k, K2.5-K2.8 are 262k, K3 is 1M. -----
        Entry("kimi", 131_072),
        Entry("kimi-k2.5", 262_144),
        Entry("kimi-k2.6", 262_144),
        Entry("kimi-k2.7", 262_144),
        Entry("kimi-k2.8", 262_144),
        Entry("kimi-k3", 1_048_576),

        // ----- MiniMax -----
        Entry("minimax", 65_536),
        Entry("minimax-m2", 204_800),
        Entry("minimax-m2-her", 65_536),
        Entry("minimax-m1", 1_000_000),
        Entry("minimax-01", 1_000_000),
        Entry("minimax-m3", 1_048_576),

        // ----- Meta -----
        Entry("llama-3.1", 131_072),
        Entry("llama-3.2", 131_072),
        Entry("llama-3.2-1b", 60_000),
        Entry("llama-3.3", 131_072),
        Entry("llama-4-maverick", 524_288),
        Entry("llama-4-scout", 1_310_720),
        Entry("muse", 1_048_576),
        Entry("muse-glimmer", 131_072),

        // ----- Mistral -----
        Entry("mistral-small", 32_768),
        Entry("mistral-small-3", 128_000),
        Entry("mistral-small-2603", 262_144),
        Entry("mistral-medium", 131_072),
        Entry("mistral-large", 128_000),
        Entry("mistral-large-4", 1_048_576),
        Entry("ministral", 131_072),
        Entry("codestral", 256_000),
        Entry("devstral", 262_144),
        Entry("mixtral", 65_536),
        Entry("voxtral", 32_768),
        Entry("mistral-nemo", 131_072),
        Entry("mistral-saba", 32_768),

        // ----- The remaining mainstream vendors, base windows only -----
        Entry("nova", 128_000),
        Entry("command", 128_000),
        Entry("sonar", 127_072),
        Entry("seed", 262_144),
        Entry("step", 262_144),
        Entry("mimo", 1_048_576),
        Entry("nemotron", 131_072),

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

        // ----- Qwen: generation bases, so a variant that isn't listed by name still gets the family's window.
        // 3.5/3.6 are 256k, 3.7/3.8 are 1M; the `-plus`/`-flash` entries above are more specific and win. -----
        Entry("qwen3.5", 256_000),
        Entry("qwen3.6", 256_000),
        Entry("qwen3.7", 1_000_000),
        Entry("qwen3.8", 1_000_000),

        // ----- Qwen: the multimodal lines (`vl` / `omni`), each generation has its own window -----
        Entry("qwen3-vl", 262_144),
        Entry("qwen2.5-vl", 128_000),
        Entry("qwen-vl", 32_000),
        Entry("qwen3-omni", 262_144),
        Entry("qwen-omni", 32_000),
        Entry("qwen3-coder", 262_144),

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
