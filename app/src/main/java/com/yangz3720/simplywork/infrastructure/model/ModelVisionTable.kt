package com.yangz3720.simplywork.infrastructure.model

/**
 * Built-in "model -> accepts image input" lookup, used **only when the endpoint didn't say**.
 *
 * Why it has to exist: `/models` modality reporting is the exception, not the rule. Measured 2026-10-21,
 * OpenRouter publishes `architecture.input_modalities` for all 468 entries and DeepSeek publishes a top-level
 * `input_modalities`, but **Qwen's `/models` returns nothing beyond `id`/`object`/`created`/`owned_by` for all
 * 178 of its models** -- and that 178-entry catalogue is a *gateway* list, so it covers Zhipu GLM, Kimi, MiniMax,
 * DeepSeek, StepFun, Xiaomi and more under the same "no capability reported" umbrella. Without this table every
 * vision model on the Qwen provider would be reported as text-only and the image button would be hidden from
 * models that genuinely accept images.
 *
 * The list below is **derived from real data, not from memory**: every family was taken from OpenRouter's live
 * `/models` (2026-10-21, 468 models with `input_modalities`) and cross-checked against the 178-entry Bailian
 * catalogue, so the vision/text split matches what the endpoints actually serve. Re-validating the whole table
 * against those 468 models leaves **zero** mismatches outside the deliberately-uncurated long tail
 * (one-off vendors such as `perceptron`, `scaledown` or `unbiased`, which have a single entry each).
 *
 * Coverage is the **mainstream families of every major vendor**, not a catalogue of everything ever released:
 * long-tail models are left out on purpose -- they fall through to `false`, which is the safe direction (a
 * hidden button is a minor annoyance, a rejected request after the user already picked a file is not).
 *
 * Three matching mechanisms, because vendor names differ in shape:
 *  - **[VISION_PREFIXES]**: family prefixes, matched through [ModelIdNormalizer], so separator style is
 *    irrelevant (`qwen3.6-vl` == `qwen-3.6-vl` == `Qwen/Qwen 3.6 VL`) and a version in the middle of the name
 *    doesn't break the match (`claude-sonnet` still catches `claude-3-5-sonnet`).
 *  - **[VISION_MARKERS]**: tokens that *mean* vision wherever they appear, which is what keeps the table from
 *    needing an entry per version (`qwen3.8-omni-flash`, `llama-3.2-11b-vision-instruct`,
 *    `nemotron-3.5-super-vl`). Verified safe: **no** text-only model in the 468-model catalogue contains one.
 *  - **[TEXT_ONLY_PREFIXES]**: exceptions that must *not* inherit the family's vision flag. This direction
 *    matters more than the other two -- these are cases where a broad prefix would otherwise promise images to
 *    a text-only model: `o3-mini` under `o3`, `gpt-oss`/`gpt-4` under `gpt`, `deepseek-v4-flash` under
 *    `deepseek-flash`.
 *
 * Arbitration is "longest prefix wins, fewer skipped version tokens next, **and on a full tie text-only wins**"
 * (the conservative direction). The endpoint report still beats all of this; see
 * `OpenAiCompatibleClient.supportsVisionOf`.
 */
object ModelVisionTable {

    /** Model-id prefixes of families that accept images. Order is irrelevant; see [lookup]. */
    private val VISION_PREFIXES: List<String> = listOf(
        // ----- OpenAI: everything since GPT-4o is multimodal. `gpt` is the catch-all for the routing aliases
        // (`~openai/gpt-astra-latest`) whose names carry no version at all -----
        "gpt", "gpt-4o", "gpt-4-turbo", "gpt-4.1", "gpt-5", "gpt-6", "gpt-chat",
        // o1/o3/o4 all take images -- except `o3-mini`, which is text-only and listed below.
        "o1", "o3", "o4-mini",

        // ----- Anthropic: every Claude from 3 on is multimodal, so one broad prefix covers the version-first
        // spellings (`claude-3-5-sonnet`) and the tier-first ones (`claude-sonnet-4`) -----
        "claude",

        // ----- Google. `lyria` is music, listed only because its entries do advertise image input -----
        "gemini", "gemma-3", "gemma-4", "lyria",

        // ----- xAI: every Grok still served (4.x and later) is multimodal -----
        "grok",

        // ----- Qwen / Bailian. Note the whole 3.5/3.6/3.7-plus/3.7-flash/3.8 line is multimodal now, not just
        // the `vl`/`omni` namesakes -- the measured catalogue says so, and that is what a name heuristic would
        // have got wrong. `cmaas-qwen3.8` is the gateway's glued-prefix spelling of the same family. -----
        "qwen3.5", "qwen3.6", "qwen3.7-plus", "qwen3.7-flash", "qwen3.8",
        "qwen3-vl", "qwen2.5-vl", "qwen2-vl", "qwen-vl",
        "qwen3-omni", "cmaas-qwen3.8",

        // ----- Zhipu GLM: `glm-4v` covers 4v / 4.1v / 4.5v / 4.6v through the version-skip rule, and only the
        // `-flash`/`-flashx` half of the 5.3 line takes images (`glm-5.3` itself is text-only) -----
        "glm-4v", "glm-5v", "glm-5.3-flash", "glm-5.3-flashx",

        // ----- Moonshot / Kimi: K2.5 and later are multimodal, K2 / K2-thinking are not -----
        "kimi-k2.5", "kimi-k2.6", "kimi-k2.7", "kimi-k2.8", "kimi-k3", "kimi-latest",

        // ----- DeepSeek: the official `deepseek-flash` (= V4.1-Flash) reports ["text","image"], and the V4.1
        // name is image-capable on aggregators too. `deepseek-v4-flash` / `-pro` are *not*, see below. -----
        "deepseek-flash", "deepseek-v4.1-flash", "deepseek-ocr",

        // ----- MiniMax -----
        "minimax-01", "minimax-m3",

        // ----- Meta -----
        "muse", "llama-4", "llama-guard-4",

        // ----- Mistral -----
        "mistral-small-3", "mistral-medium-3", "mistral-small-2603",
        "ministral", "mistral-large-2512", "mistral-large-4",

        // ----- Remaining vendors with a multimodal flagship -----
        "nova-lite", "nova-pro", "nova-premier", "nova-2",
        "command-a-plus", "sonar",
        "seed", "step-3.7", "step-5", "mimo", "fugu", "sakana", "inkling", "ui-tars",
        "nemotron-3.5-content-safety",

        // ----- Open-weight / self-hosted families, which any local llama.cpp or vLLM endpoint may expose -----
        "llava", "internvl", "intern-vl", "pixtral", "molmo", "minicpm-v",
        "cogvlm", "fuyu", "idefics", "moondream", "cogagent"
    )

    /**
     * Tokens that mark a model as multimodal no matter which family it belongs to. Each one is a whole token,
     * never a substring: `internvl` is one token and is caught by its prefix instead, `visionary-7b` is one
     * token and is correctly not caught at all.
     */
    private val VISION_MARKERS: Set<String> = setOf("vision", "vl", "omni", "multimodal")

    /**
     * Families that share a vision prefix but are text-only themselves. Measured, not guessed: each one was
     * read off the live catalogue, and each is the reason the vision list above can stay broad.
     */
    private val TEXT_ONLY_PREFIXES: List<String> = listOf(
        // OpenAI
        "o3-mini", "gpt-oss", "gpt-4", "gpt-3.5", "gpt-audio",
        // Google
        "gemma-2",
        // Qwen: every `coder` variant is text-only, including `qwen3-coder-plus` / `qwen-2.5-coder-32b`
        "qwen-coder",
        // 2.4T-A95B is the one text-only member of the 3.8 line
        "qwen3.8-2.4t",
        // DeepSeek
        "deepseek-v4-flash", "deepseek-v4-pro",
        // Perplexity
        "sonar-deep-research",
        // Xiaomi
        "mimo-v2.5-pro"
    )

    /** A prefix plus what it asserts, pre-tokenised once so matching doesn't re-tokenise the table. */
    private class Candidate(val prefix: String, val acceptsImages: Boolean) {
        val tokens: List<String> = ModelIdNormalizer.tokens(prefix)
    }

    private val CANDIDATES: List<Candidate> =
        VISION_PREFIXES.map { Candidate(it, acceptsImages = true) } +
            TEXT_ONLY_PREFIXES.map { Candidate(it, acceptsImages = false) }

    /** `true` when the table knows this model accepts images, `null` when it has no opinion. */
    fun lookup(modelId: String): Boolean? {
        val tokens = ModelIdNormalizer.tokens(modelId)
        if (tokens.isEmpty()) return null
        // An explicit "vision"/"vl"/"omni" token is the vendor declaring it, so it outranks the family prefixes.
        if (tokens.any { it in VISION_MARKERS }) return true
        return CANDIDATES
            .mapNotNull { candidate ->
                ModelIdNormalizer.matchSkips(tokens, candidate.tokens)?.let { candidate to it }
            }
            // Longest prefix (most specific) -> fewest skipped versions -> on a tie text-only wins.
            .maxWithOrNull(
                compareBy(
                    { it.first.tokens.size },
                    { -it.second },
                    { if (it.first.acceptsImages) 0 else 1 }
                )
            )
            ?.first
            ?.acceptsImages
    }

    /**
     * Whether the model accepts images, per this table. `false` when the table has no opinion -- which is the
     * safe direction: a hidden button is a minor annoyance, a rejected request after the user already picked a
     * file is not.
     */
    fun supports(modelId: String): Boolean = lookup(modelId) == true
}
