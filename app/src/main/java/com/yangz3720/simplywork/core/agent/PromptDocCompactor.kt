package com.yangz3720.simplywork.core.agent

/**
 * Rule-based compaction for the Markdown documents injected into the system prompt (project Soul,
 * project `AGENTS.md`).
 *
 * No model call on purpose: `assemble()` runs once per loop iteration, so compaction has to be free
 * and reproducible -- the same document must always yield the same text, otherwise the prompt cache
 * would never hit. Compression is therefore mechanical, in this order:
 *
 * 1. Under budget -> the text is returned untouched (and normalised only, see below).
 * 2. Whitespace: line endings normalised, trailing spaces dropped, blank-line runs collapsed to one,
 *    leading/trailing blank lines dropped. Markdown meaning is preserved (a single blank line still
 *    separates blocks); only the visual padding goes.
 * 3. Still over budget -> whole lines from the top are kept until the budget is used up, and a
 *    marker is appended. The cut is **declared**, never silent: the model must know the document was
 *    truncated instead of believing it read the whole file (same rule as the sandbox read side,
 *    `AgentWorkspace.maxFileBytes`).
 */
object PromptDocCompactor {

    /** Injection budget of one document (~2.5k tokens: a few pages, not the whole project). */
    const val DEFAULT_MAX_TOKENS = 2_500

    /** Appended to a document that had to be cut; Also what makes the truncation observable. */
    const val TRUNCATION_MARKER: String =
        "\n\n[… this document was too long and has been truncated here: only the part above fits the context budget]"

    /** Compact [text] to at most [maxTokens] (estimated with [estimator]); see the class comment. */
    fun compact(
        text: String,
        estimator: TokenEstimator,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): String {
        if (text.isBlank()) return ""
        if (estimator.estimate(text) <= maxTokens) return text
        val tidied = tidy(text)
        if (estimator.estimate(tidied) <= maxTokens) return tidied
        // Reserve room for the marker so the declaration itself never pushes us over the budget.
        val budget = (maxTokens - estimator.estimate(TRUNCATION_MARKER)).coerceAtLeast(1)
        return keepWholeLines(tidied, budget, estimator) + TRUNCATION_MARKER
    }

    /** Cheap, meaning-preserving whitespace cleanup (step 2 of the class comment). */
    internal fun tidy(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().map { it.trimEnd() }
        val kept = ArrayList<String>(lines.size)
        var blankRun = 0
        lines.forEach { line ->
            if (line.isBlank()) {
                blankRun++
                if (blankRun > 1) return@forEach
            } else {
                blankRun = 0
            }
            kept.add(line)
        }
        return kept.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    }

    /** Keeps whole lines from the top while they fit [budget]; a single over-budget line is cut by characters. */
    private fun keepWholeLines(text: String, budget: Int, estimator: TokenEstimator): String {
        val kept = ArrayList<String>()
        var used = 0
        for (line in text.lines()) {
            val cost = estimator.estimate(line) + 1 // + the newline
            if (used + cost > budget) break
            kept.add(line)
            used += cost
        }
        // Nothing fit (one enormous line): cut it by characters rather than dropping the document.
        if (kept.isEmpty()) kept.add(charPrefix(text.lines().first(), budget, estimator))
        return kept.joinToString("\n")
    }

    /** Longest prefix of [line] whose estimate stays within [budget] (binary search: no per-char allocation). */
    private fun charPrefix(line: String, budget: Int, estimator: TokenEstimator): String {
        var low = 0
        var high = line.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (estimator.estimate(line.substring(0, mid)) <= budget) low = mid else high = mid - 1
        }
        return line.substring(0, low)
    }
}
