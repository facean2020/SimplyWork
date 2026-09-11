package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.infrastructure.model.ModelProvider
import com.yangz3720.simplywork.infrastructure.model.model.Message
import com.yangz3720.simplywork.infrastructure.model.model.ModelRequest
import kotlinx.serialization.json.JsonObject

/**
 * Model-backed summarizer (agent-core-design.md §7.2): one-shot generate() with a
 * structured compaction prompt. Errors propagate — the compactor catches them and
 * skips the round (§7.3: explicit, no silent truncation).
 */
class ModelSummarizer(
    private val modelProvider: ModelProvider,
    private val model: String? = null,
    private val maxNodeChars: Int = 2_000
) : Summarizer {

    override val strategyName = "model"

    override suspend fun summarize(nodes: List<SessionNode>, payloads: List<JsonObject>): String {
        val transcript = nodes.zip(payloads)
            .joinToString("\n") { (node, payload) ->
                val text = NodePayloads.flatText(node, payload).take(maxNodeChars)
                "[${node.nodeType.name}] $text"
            }
        val request = ModelRequest(
            messages = listOf(
                Message.System(content = COMPACTION_PROMPT),
                Message.User(content = transcript)
            ),
            model = model,
            temperature = 0.2f
        )
        val response = modelProvider.generate(request).getOrElse {
            throw IllegalStateException("Compaction model call failed: ${it.message}", it)
        }
        return response.text.ifBlank { throw IllegalStateException("Compaction model returned an empty summary") }
    }

    private companion object {
        val COMPACTION_PROMPT = """
            You are compacting a conversation segment of an assistant agent into a durable summary.
            Produce a concise structured summary (same language as the segment) with exactly these sections:
            1. USER GOAL — what the user wants, verbatim intent
            2. COMPLETED — key steps already taken and their tool results (keep exact names, ids, numbers)
            3. UNRESOLVED — open questions, failed/blocked operations, pending todo items
            4. CONSTRAINTS — user preferences or corrections stated along the way
            Keep every concrete fact (paths, ids, dates, numbers); drop pleasantries and filler.
        """.trimIndent()
    }
}
