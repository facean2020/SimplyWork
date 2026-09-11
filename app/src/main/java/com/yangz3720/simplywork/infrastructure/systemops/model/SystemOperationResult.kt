package com.yangz3720.simplywork.infrastructure.systemops.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Execution path identifier: a normalized result must answer "which path produced this result".
 * v1.1: only the Intent path exists. The AppFunction path (API 36 gate) and the Automation
 * fallback (Shizuku rejected / Accessibility deferred) were removed from scope — see
 * `docs/PlaningStages/appfunction-design.md` v1.1.
 */
@Serializable
enum class ExecutionPath {
    SYSTEM_INTENT
}

/**
 * Unified result for ALL system meta-operations (P2: failures must be explicit).
 * No silent `null` / `false` — system restrictions (e.g. background clipboard read)
 * are surfaced as typed failures so the agent can self-heal instead of misreading
 * them as "empty data".
 */
@Serializable
sealed interface SystemOperationResult {

    /** Operation succeeded. [outputJson] carries structured output when applicable. */
    @Serializable
    data class Success(
        val outputJson: JsonObject? = null,
        val executedVia: ExecutionPath = ExecutionPath.SYSTEM_INTENT,
        /** Wall time in ms — stamped centrally by [SystemOperationRouter][com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouter]. */
        val executionTimeMs: Long = 0L
    ) : SystemOperationResult

    /**
     * UI layer must request these permissions before the operation can be retried (P4:
     * infrastructure only reports; permission UX is owned by the UI layer).
     */
    @Serializable
    data class PermissionRequired(
        val permissions: List<String>,
        val rationale: String
    ) : SystemOperationResult

    /** System-level restriction blocked the operation (e.g. background clipboard read, C3). */
    @Serializable
    data class SecurityBlocked(val reason: String) : SystemOperationResult

    /** No matching target: package not installed / no handler for the URI / no calendar editor. */
    @Serializable
    data class TargetNotFound(val target: String) : SystemOperationResult

    @Serializable
    data class Timeout(val timeoutMs: Long, val executionTimeMs: Long) : SystemOperationResult

    @Serializable
    data class Failed(val message: String, val cause: String? = null) : SystemOperationResult
}
