package com.yangz3720.simplywork.infrastructure.systemops

/**
 * Request seam for missing Android runtime permissions (v1.2, P4).
 *
 * P4 says infrastructure reports and never prompts. This keeps that true while still
 * letting a tool self-heal: the executor *asks* an injected handler to run the grant UX,
 * the UI layer decides, and a `false` answer turns into the same
 * [SystemOperationResult.PermissionRequired][com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult.PermissionRequired]
 * as before. It is the same shape as `AuditApprovalHandler` in agent-core — a suspend
 * bridge from a background loop into a UI decision.
 *
 * Suspending is bounded by the router timeout: a request the user never answers degrades
 * to `Timeout`, never a hung agent loop.
 */
fun interface SystemPermissionRequester {

    /**
     * @param permissions Android permission names to request (already known missing).
     * @param rationale   why the tool needs them; surfaced verbatim to the user.
     * @return true when the grant UX reported success — the caller must still re-check,
     *         because "user tapped Allow" and "permission actually granted" diverge for
     *         one-time grants and partial accept-all-denials.
     */
    suspend fun request(permissions: List<String>, rationale: String): Boolean

    /** Default: never prompts. Preserves the pre-v1.2 behaviour for unit tests. */
    companion object {
        val None = SystemPermissionRequester { _, _ -> false }
    }
}
