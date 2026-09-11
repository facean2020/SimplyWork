package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeType

/**
 * Splits the active path into the compactable prefix and the working-memory window
 * (agent-core-design.md §5.1 / §7.1) — the single source both the assembler's view of
 * "recent rounds" and the compactor's span selection build on.
 *
 * A "round" starts at a user-type node ([SessionNodeType.UserPrompt] or [SessionNodeType.Steer])
 * and extends to just before the next one. The path's leading run of SystemPrompt nodes
 * is the preamble: never compacted, never part of a round.
 */
object WorkingWindowSelector {

    data class WorkingWindow(
        /** Nodes outside the working window (older rounds + previous summaries), chronological. */
        val compactablePrefix: List<SessionNode>,
        /** Nodes of the retained rounds (working memory), chronological. */
        val workingNodes: List<SessionNode>
    )

    fun select(path: List<SessionNode>, maxRounds: Int, tokenBudget: Int): WorkingWindow {
        if (path.isEmpty()) return WorkingWindow(emptyList(), emptyList())

        val preambleEnd = path.indexOfFirst { it.nodeType != SessionNodeType.SystemPrompt }
            .let { if (it == -1) path.size else it }
        val preamble = path.subList(0, preambleEnd)
        val body = path.subList(preambleEnd, path.size)

        // Round boundaries: indices in `body` where a user-type node starts.
        val boundaries = body.withIndex()
            .filter { (_, node) -> node.nodeType == SessionNodeType.UserPrompt || node.nodeType == SessionNodeType.Steer }
            .map { it.index }

        // Round i spans [boundaries[i], boundaries[i+1]) — the last one to the end of body.
        val rounds = boundaries.indices.map { i ->
            val start = boundaries[i]
            val end = if (i + 1 < boundaries.size) boundaries[i + 1] else body.size
            body.subList(start, end)
        }

        // Keep the newest rounds within both budgets; always keep at least the newest round.
        val kept = ArrayList<List<SessionNode>>()
        var keptTokens = 0
        for (i in rounds.indices.reversed()) {
            val round = rounds[i]
            val roundTokens = round.sumOf { it.tokenCount }
            val roundsExhausted = kept.size >= maxRounds
            val budgetExceeded = kept.isNotEmpty() && keptTokens + roundTokens > tokenBudget
            if (roundsExhausted || budgetExceeded) break
            kept.add(round)
            keptTokens += roundTokens
        }

        val keptStartIndex = kept.lastOrNull()?.firstOrNull()
            ?.let { oldestKept -> body.indexOf(oldestKept) }
            ?: body.size

        return WorkingWindow(
            compactablePrefix = preamble + body.subList(0, keptStartIndex),
            // kept is newest-first; restore chronological order.
            workingNodes = kept.asReversed().flatten()
        )
    }
}
