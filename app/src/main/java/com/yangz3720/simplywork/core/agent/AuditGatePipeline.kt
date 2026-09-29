package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.agent.tool.AgentToolRegistry
import com.yangz3720.simplywork.core.agent.tool.RegisteredTool
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Pre-Tool Audit Gate (flow node O) + Post-Tool Result Gate (flow node Q)
 * (agent-core-design.md §8.2 / §8.4).
 *
 * Pre-tool decision table: unknown tool → deny; unparseable arguments → deny;
 * SAFE / USER_VISIBLE → allow; USER_CONFIRMING → consult the [AuditApprovalHandler]
 * (HITL seam, P4: infrastructure reports, UI decides; v1 default auto-approves).
 * Denials are returned as values — the loop persists them as error ToolResults
 * so the model can self-correct (P2), nothing is silently dropped.
 */
class AuditGatePipeline(
    private val registry: AgentToolRegistry,
    private val approvalHandler: AuditApprovalHandler = AuditApprovalHandler.AutoApprove,
    private val sanitizer: ToolResultSanitizer = ToolResultSanitizer.Identity,
    private val maxToolResultChars: Int = 8_000,
    /**
     * Auto-approval (v1.1, comes from [AgentConfig]): by default every USER_CONFIRMING call asks the
     * human; once enabled, only **irreversible** calls ([RegisteredTool.destructive]) ask -- confirming
     * every location read, calendar query or app launch is pure friction, whereas deleting a file must
     * leave the human a beat.
     *
     * Default false: this is the most conservative side, and it keeps behaviour identical to before
     * (existing unit-test expectations are unaffected). The product layer's default is decided by
     * `KeyringState`.
     */
    private val autoApproveNonDestructive: Boolean = false
) {

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface AuditDecision {
        data class Approved(val tool: com.yangz3720.simplywork.core.agent.tool.RegisteredTool, val parsed: Any?) : AuditDecision
        data class Denied(val reason: String) : AuditDecision
    }

    /**
     * Flow node O. Parses and danger-checks the call; on approval the parsed value is
     * handed back so the loop can execute without re-parsing.
     */
    suspend fun preToolAudit(toolCall: com.yangz3720.simplywork.infrastructure.model.model.ToolCall): AuditDecision {
        val tool = registry.get(toolCall.name)
            ?: return AuditDecision.Denied("Unknown tool: ${toolCall.name}")

        val rawArguments = runCatching { json.parseToJsonElement(toolCall.argumentsJson).jsonObject }
            .getOrElse { return AuditDecision.Denied("Invalid arguments for ${toolCall.name}: not a JSON object") }

        val parsed = tool.parse(rawArguments).getOrElse {
            return AuditDecision.Denied("Invalid arguments for ${toolCall.name}: ${it.message}")
        }

        val effectiveDanger = tool.instanceDangerLevel?.invoke(parsed) ?: tool.dangerLevel
        if (requiresUserApproval(tool, effectiveDanger)) {
            val approved = approvalHandler.approve(toolCall.name, rawArguments, effectiveDanger)
            if (!approved) return AuditDecision.Denied("User declined approval for ${toolCall.name}")
        }
        return AuditDecision.Approved(tool, parsed)
    }

    /**
     * Whether a dialog has to ask the human.
     *
     * Auto-approval applies only to "non-irreversible" USER_CONFIRMING calls: delete-style ones are
     * indifferent to the switch and always go through [approvalHandler] -- a delete confirmation a
     * setting can turn off is no confirmation at all.
     */
    private fun requiresUserApproval(tool: RegisteredTool, level: DangerLevel): Boolean = when {
        level != DangerLevel.USER_CONFIRMING -> false
        tool.destructive -> true
        else -> !autoApproveNonDestructive
    }

    /**
     * Flow node Q: normalized tool-result JSON → final model-facing content
     * (sanitize → truncate with an explicit marker).
     *
     * Truncation is JSON-aware (v1.2 review S6): a byte-cut of a serialized object yields
     * invalid JSON, which the model then tries to parse — a busy calendar or app list comes
     * back as a syntax error instead of a partial answer. When the (sanitized) payload still
     * parses, we shrink it structurally; only text the sanitizer has mangled beyond
     * recognition falls back to the legacy byte-cut.
     */
    fun postToolProcess(resultJson: JsonObject): String {
        val raw = resultJson.toString()
        val sanitized = sanitizer.sanitize(raw)
        if (sanitized.length <= maxToolResultChars) return sanitized

        val element = runCatching { json.parseToJsonElement(sanitized) }.getOrNull()
            ?: return sanitized.take(maxToolResultChars) +
                "[truncated ${sanitized.length - maxToolResultChars} chars]"

        // The shrunk payload carries its own `_truncated` notes, so no suffix is appended:
        // anything after the closing brace/bracket would make the JSON unparseable again.
        return shrink(element, maxToolResultChars).toString()
    }

    /**
     * Structural shrink that is guaranteed to stay parseable by construction: arrays keep
     * their leading entries plus a note item, objects cap oversized strings first and then
     * drop trailing fields plus a note field. Longest-prefix search (binary) keeps as much
     * data as the budget allows instead of halving blindly.
     */
    private fun shrink(element: JsonElement, budget: Int): JsonElement {
        if (element.toString().length <= budget) return element
        return when (element) {
            is JsonArray -> {
                var best: JsonArray? = null
                var lo = 0
                var hi = element.size
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val probe = buildJsonArray {
                        element.take(mid).forEach { add(it) }
                        add(truncationNote("${element.size - mid} more entries omitted"))
                    }
                    if (probe.toString().length <= budget) {
                        best = probe
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                best ?: fallback(element.toString(), budget)
            }

            is JsonObject -> {
                // Cap runaway values first: one huge string loses its tail, one oversized
                // nested array/object shrinks itself — neither drags whole sibling fields
                // out with it when the budget could have held both.
                val capped = buildJsonObject {
                    element.forEach { (key, value) ->
                        put(key, when {
                            value is JsonPrimitive && value.isString && value.content.length > 512 ->
                                JsonPrimitive(cutText(value.content, 512))
                            value.toString().length > budget ->
                                // 75% headroom: the child must leave room for the key and its
                                // siblings, or the parent overflows right back into field-drop.
                                shrink(value, (budget * 3) / 4)
                            else -> value
                        })
                    }
                }
                if (capped.toString().length <= budget) return capped

                val entries = capped.entries.toList()
                var best: JsonObject? = null
                var lo = 0
                var hi = entries.size
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val probe = buildJsonObject {
                        entries.take(mid).forEach { (key, value) -> put(key, value) }
                        if (mid < entries.size) put("_truncated", "${entries.size - mid} more fields omitted")
                    }
                    if (probe.toString().length <= budget) {
                        best = probe
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                best ?: fallback(element.toString(), budget)
            }

            is JsonPrimitive -> JsonPrimitive(cutText(element.content, budget))
            else -> fallback(element.toString(), budget)
        }
    }

    private fun truncationNote(message: String): JsonObject = buildJsonObject { put("_truncated", message) }

    /** When nothing (not even a note) fits, emit a plain string primitive — still valid JSON. */
    private fun fallback(raw: String, budget: Int): JsonElement = JsonPrimitive(cutText(raw, budget))

    private fun cutText(text: String, budget: Int): String {
        val room = (budget - 24).coerceAtLeast(0)
        return text.take(room) + "…[cut]"
    }
}

/**
 * HITL seam (agent-core-design.md §8.2 A4 / §12 Q1): USER_CONFIRMING tools require an
 * approval decision. The default auto-approves with a log line; the UI-layer approval
 * dialog replaces it in a later phase.
 */
fun interface AuditApprovalHandler {

    /** @return true to allow the call, false to deny. */
    suspend fun approve(toolName: String, argumentsJson: JsonObject, dangerLevel: DangerLevel): Boolean

    companion object {
        val AutoApprove = AuditApprovalHandler { name, _, level ->
            com.yangz3720.simplywork.infrastructure.util.AgentLogger.d("AuditGate") {
                "Auto-approved $name ($level) — HITL dialog not wired yet (design Q1)"
            }
            true
        }
    }
}

/** PII-redaction / normalization seam on tool output text (§8.4). v1: identity. */
fun interface ToolResultSanitizer {
    fun sanitize(raw: String): String

    companion object {
        val Identity = ToolResultSanitizer { it }
    }
}
