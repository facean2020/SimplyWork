package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.session.SessionRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Context compaction self-healing engine (flow node E; agent-core-design.md §7).
 *
 * Trigger: the assembled-context token estimate (State Memory + full active path)
 * exceeds [AgentConfig.compactionTriggerTokens]. The compactable span is the path
 * prefix outside the working-memory window (excluding the leading SystemPrompt
 * preamble), at least one node long. The span is summarized into a new Summary node;
 * the first remaining node is re-wired under the summary (§4). Compacted nodes are
 * never deleted or re-statused — history stays auditable off the active path.
 */
class ContextCompactor(
    private val repository: SessionRepository,
    private val summarizer: Summarizer,
    private val estimator: TokenEstimator,
    private val config: AgentConfig
) {

    sealed interface Outcome {
        data object NotNeeded : Outcome
        data class Compacted(val summaryNodeId: String, val savedTokens: Int) : Outcome
        data class Skipped(val reason: String) : Outcome
    }

    suspend fun maybeCompact(sessionId: String, path: List<SessionNode>): Outcome {
        val stateTokens = estimator.estimate(repository.loadState(sessionId).toPromptBlock())
        val pathTokens = path.sumOf { it.tokenCount }
        if (stateTokens + pathTokens <= config.compactionTriggerTokens) return Outcome.NotNeeded

        val window = WorkingWindowSelector.select(path, config.maxWorkingRounds, config.workingTokenBudget)
        // Compactable prefix minus the leading SystemPrompt preamble (never compacted).
        val preambleEnd = path.indexOfFirst { it.nodeType != SessionNodeType.SystemPrompt }
            .let { if (it == -1) path.size else it }
        val compactableCount = window.compactablePrefix.size - preambleEnd
        if (compactableCount <= 0) {
            return Outcome.Skipped("no compactable prefix outside the working window")
        }

        val span = path.subList(preambleEnd, preambleEnd + compactableCount)
        val coveredTokens = span.sumOf { it.tokenCount }

        val summaryText = try {
            val payloads = span.map { repository.payload(it) }
            summarizer.summarize(span, payloads)
        } catch (e: Exception) {
            return Outcome.Skipped("summarizer failed: ${e.message}")
        }
        val summaryTokenCount = estimator.estimate(summaryText)

        // Summary node: parent = last preamble node (or root when no preamble).
        // advanceLeaf=false: the leaf stays on the working window; the summary only
        // becomes an ancestor once the first-after-span node is re-wired below.
        val summaryParent = path.getOrNull(preambleEnd - 1)?.id
        val summaryNode = repository.appendNode(
            sessionId = sessionId,
            parentId = summaryParent,
            nodeType = SessionNodeType.Summary,
            payload = buildJsonObject {
                put("text", summaryText)
                put("coveredTokenCount", coveredTokens)
                put("strategy", summarizer.strategyName)
                put("coveredNodeIds", JsonArray(span.map { JsonPrimitive(it.id) }))
            },
            tokenCount = summaryTokenCount,
            status = SessionNodeStatus.success,
            advanceLeaf = false
        )

        // Re-wire: the first node after the span now hangs under the summary (§4).
        val firstAfterSpan = path.getOrNull(preambleEnd + span.size)
        firstAfterSpan?.let { repository.rewireParent(it.id, summaryNode.id) }

        return Outcome.Compacted(
            summaryNodeId = summaryNode.id,
            savedTokens = (coveredTokens - summaryTokenCount).coerceAtLeast(0)
        )
    }
}

/**
 * Summarizer contract for compaction (agent-core-design.md §7.2).
 */
interface Summarizer {
    val strategyName: String

    /** @return the summary text for the given nodes with their decoded payloads. */
    suspend fun summarize(nodes: List<SessionNode>, payloads: List<JsonObject>): String
}

/** Deterministic fallback: flat concatenation with a hard cap — no LLM dependency (§7.2). */
class TruncateSummarizer(private val maxChars: Int = 2_000) : Summarizer {

    override val strategyName = "truncate"

    override suspend fun summarize(nodes: List<SessionNode>, payloads: List<JsonObject>): String {
        val joined = nodes.zip(payloads)
            .joinToString("\n") { (node, payload) -> "- [${node.nodeType.name}] ${NodePayloads.flatText(node, payload)}" }
            .trim()
        return if (joined.length > maxChars) {
            joined.take(maxChars) + "…[compacted ${joined.length - maxChars} chars]"
        } else {
            joined
        }
    }
}
