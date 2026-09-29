package com.yangz3720.simplywork.ui.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * HITL approval coordinator (agent-core-design.md §8.2 A4 / §12 Q1): implements the UI-side semantics
 * of `AuditApprovalHandler` -- `request` suspends until the user decides, and the decision is fed back via [respond].
 *
 * Single-request assumption: the Agent Loop calls `preToolAudit` serially inside the collector coroutine, so
 * at most one approval is pending at a time; when the run is cancelled, `await` exits with CancellationException
 * and `finally` clears the pending state (no ghost dialog left behind).
 */
class ApprovalCoordinator {

    private val _requests = MutableStateFlow<ApprovalRequest?>(null)

    /** The approval request currently pending; when non-null the UI shows the approval dialog. */
    val pending: StateFlow<ApprovalRequest?> = _requests.asStateFlow()

    private var deferred: CompletableDeferred<Boolean>? = null

    /** Called by AuditApprovalHandler: suspends until the user decides or the run is cancelled. */
    suspend fun request(toolName: String, argsJson: String): Boolean {
        val pendingDeferred = CompletableDeferred<Boolean>()
        deferred = pendingDeferred
        _requests.value = ApprovalRequest(toolName, argsJson)
        try {
            return pendingDeferred.await()
        } finally {
            deferred = null
            _requests.value = null
        }
    }

    /** Entry point for the user's decision: true allows / false denies. No-op when nothing is pending. */
    fun respond(allowed: Boolean) {
        deferred?.complete(allowed)
    }
}