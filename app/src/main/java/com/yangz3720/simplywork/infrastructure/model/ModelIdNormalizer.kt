package com.yangz3720.simplywork.infrastructure.model

/**
 * Turns the many spellings a vendor may use for **the same model** into one comparable token list, so the
 * built-in tables ([ModelVisionTable] here, `ModelContextTable` in `core.agent`) don't have to list every
 * spelling by hand.
 *
 * Why it has to exist: there is no naming convention across vendors, and they genuinely disagree on the
 * separators for identical models. The three spellings below are all `qwen3.6-plus`, and all three are served by
 * endpoints this app talks to -- observed 2026-10-21:
 *
 *  - `qwen3.6-plus` / `qwen3.6-plus-2026-04-02` (Bailian direct)
 *  - `Qwen/Qwen3.6-Plus`, `anthropic/claude-3.5-sonnet-20241022` (aggregator / HuggingFace-style paths)
 *  - `ZHIPU/GLM-5.3-Flash`, `kimi/kimi-k2.6`, `vanchin/deepseek-v4.1-flash` (the gateway catalogue Bailian
 *    itself returns, i.e. what this app actually shows on the Qwen provider)
 *
 * A plain `startsWith` on the raw id missed most of those. Normalisation is therefore **tokenisation**, not
 * string stripping:
 *  1. take the last path segment (`kimi/kimi-k2.6` -> `kimi-k2.6`; every aggregator id is `vendor/model`)
 *  2. lowercase
 *  3. cut at every non-alphanumeric character (`-`, `_`, `.`, space, `:`, ...)
 *  4. cut at every letter<->digit transition, so `qwen3` -> `qwen`,`3` and `4o` -> `4`,`o`
 *  5. **except** a digit run glued to a size unit stays whole: `27b`, `8b`, `72b`, `1m`, `128k` -> one token
 *
 * Steps 4-5 are what make the comparison safe. Dropping separators entirely (`qwen-3.6` -> `qwen36`) would
 * collide version numbers with unrelated digit runs -- `gpt-4.1` and `gpt-4-1106-preview` both begin `gpt41…` --
 * whereas keeping a digit run as its own token lets us require numbers to line up **as whole tokens**. Step 5
 * exists because without it `qwen3-8b` -> `qwen`,`3`,`8`,`b` would happily swallow the `27` of `qwen3.8-27b`
 * and hand an 8B model's budget to a 27B one.
 */
object ModelIdNormalizer {

    /** Single-letter suffixes that denote a size, not a variant: `27b` billions, `128k` thousands, `1m`, `1t`. */
    private const val SIZE_UNITS = "bmkt"

    /**
     * `"Qwen/Qwen3.6-VL-Plus"` -> `["qwen", "3", "6", "vl", "plus"]`, `"qwen3.8-27b"` -> `["qwen","3","8","27b"]`.
     *
     * Empty input (or an id that is nothing but separators) yields an empty list; callers treat that as
     * "unknown model" and must not match anything.
     */
    fun tokens(modelId: String): List<String> {
        val leaf = modelId.trim().lowercase().substringAfterLast('/')
        if (leaf.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var currentIsDigit = false
        var index = 0
        while (index < leaf.length) {
            val ch = leaf[index]
            if (!ch.isLetterOrDigit()) {
                flush(tokens, current)
                index++
                continue
            }
            val isDigit = ch.isDigit()
            if (current.isNotEmpty() && isDigit != currentIsDigit) {
                val gluedSizeUnit = currentIsDigit && ch in SIZE_UNITS &&
                    leaf.getOrNull(index + 1)?.isLetterOrDigit() != true
                if (gluedSizeUnit) {
                    current.append(ch)
                    flush(tokens, current)
                    index++
                    continue
                }
                flush(tokens, current)
            }
            currentIsDigit = isDigit
            current.append(ch)
            index++
        }
        flush(tokens, current)
        return tokens
    }

    private fun flush(tokens: MutableList<String>, current: StringBuilder) {
        if (current.isNotEmpty()) {
            tokens += current.toString()
            current.clear()
        }
    }

    /**
     * How many model tokens had to be **skipped** for [prefixTokens] to match [modelTokens], or `null` when it
     * doesn't match at all. `0` means the prefix lines up exactly.
     *
     * Skipping exists because a version often sits in the middle of a name: the prefix `qwen-vl` has to match
     * `qwen3.5-vl-plus`, `claude-sonnet` has to match `claude-3-5-sonnet`. Only **numeric** model tokens may be
     * skipped, and only when the prefix expects a non-numeric token at that spot:
     *
     *  - `gpt-4-o` vs `gpt-4-1106-preview`: `1106` is numeric and the prefix expects `o`, so `1106` is skipped --
     *    but then nothing is left to match `o`, so this is **no match**. Correct: `gpt-4-1106-preview` is
     *    text-only and must not inherit `gpt-4o`'s flag.
     *  - `qwen-3-6` vs `qwen-3-5-plus`: `6` and `5` are both numeric, so **no skip** is allowed -> no match.
     *    Correct: two versions of the same family never collapse into each other.
     *
     * The returned skip count is the tables' tie-breaker: among equally long prefixes the one that needed fewer
     * skips is the more specific answer.
     */
    fun matchSkips(modelTokens: List<String>, prefixTokens: List<String>): Int? {
        if (prefixTokens.isEmpty() || modelTokens.isEmpty()) return null
        var index = 0
        var skips = 0
        prefixTokens.forEach { expected ->
            val expectedIsNumeric = expected.all { it.isDigit() }
            while (true) {
                if (index >= modelTokens.size) return null
                val actual = modelTokens[index]
                if (actual == expected) {
                    index++
                    return@forEach
                }
                if (!expectedIsNumeric && actual.all { it.isDigit() }) {
                    index++
                    skips++
                    continue
                }
                return null
            }
        }
        return skips
    }

    /** Convenience wrapper over [matchSkips]. */
    fun matches(modelId: String, prefix: String): Boolean =
        matchSkips(tokens(modelId), tokens(prefix)) != null
}
