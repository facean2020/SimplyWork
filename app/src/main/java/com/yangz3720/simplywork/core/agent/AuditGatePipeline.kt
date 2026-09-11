package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.agent.tool.AgentToolRegistry
import com.yangz3720.simplywork.core.agent.tool.RegisteredTool
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
    private val maxToolResultChars: Int = 8_000
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
        if (effectiveDanger == DangerLevel.USER_CONFIRMING) {
            val approved = approvalHandler.approve(toolCall.name, rawArguments, effectiveDanger)
            if (!approved) return AuditDecision.Denied("User declined approval for ${toolCall.name}")
        }
        return AuditDecision.Approved(tool, parsed)
    }

    /**
     * Flow node Q: normalized tool-result JSON → final model-facing content
     * (sanitize → truncate with an explicit marker).
     */
    fun postToolProcess(resultJson: JsonObject): String {
        val raw = resultJson.toString()
        val sanitized = sanitizer.sanitize(raw)
        return if (sanitized.length > maxToolResultChars) {
            sanitized.take(maxToolResultChars) + "[truncated ${sanitized.length - maxToolResultChars} chars]"
        } else {
            sanitized
        }
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
