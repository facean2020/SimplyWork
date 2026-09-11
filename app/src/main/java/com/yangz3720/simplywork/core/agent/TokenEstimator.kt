package com.yangz3720.simplywork.core.agent

/**
 * Token accounting for session nodes (agent-core-design.md §5.4).
 */
interface TokenEstimator {
    fun estimate(text: String): Int
}

/**
 * Heuristic estimator: ASCII ≈ 1 token per 4 chars, CJK ≈ 1 token per char.
 * Assistant turns prefer real usage numbers when the provider reports them;
 * this heuristic covers user prompts, tool results and system text.
 */
class HeuristicTokenEstimator : TokenEstimator {

    override fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var wide = 0
        for (ch in text) {
            if (ch.code < 0x2E80) ascii++ else wide++
        }
        return (ascii + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN + wide
    }

    private companion object {
        const val ASCII_CHARS_PER_TOKEN = 4
    }
}
