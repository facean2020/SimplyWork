package com.yangz3720.simplywork.ui.agent

import com.yangz3720.simplywork.infrastructure.systemops.SystemPermissionRequester
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime permission coordinator (v1.2, P4): implements the UI-side semantics of
 * `SystemPermissionRequester` -- [request] suspends for the system grant result, which is fed back via [respond].
 *
 * Isomorphic to [ApprovalCoordinator] (same "background loop suspends for a UI decision" seam): the Agent Loop executes
 * tools serially, so at most one grant is pending at a time; when the run is cancelled (including the Router's 10s
 * timeout), `await` exits with CancellationException, `finally` clears the pending state, and no ghost dialog is left behind.
 *
 * Note that [request]'s return value only says "what the user tapped"; the caller (the executor) must re-check
 * `checkSelfPermission` before allowing it -- one-time grants and partial approval both make the two disagree.
 */
class PermissionCoordinator {

    private val _pending = MutableStateFlow<PermissionRequest?>(null)

    /** The grant request currently pending; when non-null the UI triggers the system permission dialog. */
    val pending: StateFlow<PermissionRequest?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<Boolean>? = null

    /** Called by the executor: suspends until the user decides or the run is cancelled/times out. */
    suspend fun request(permissions: List<String>, rationale: String): Boolean {
        val pendingDeferred = CompletableDeferred<Boolean>()
        // Overlap protection (v1.2 review S10): normally the Agent Loop executes serially, so at most one pending;
        // but a second request after a screen recreation or a race must not leave the first caller hanging forever --
        // the old request converges to "not granted", and the caller re-checks permissions and naturally lands in PermissionRequired (P2 explicit failure).
        deferred?.complete(false)
        deferred = pendingDeferred
        _pending.value = PermissionRequest(permissions, rationale)
        try {
            return pendingDeferred.await()
        } finally {
            // Clear only when the pending slot is still this request: if a newer request has
            // already overwritten it, clearing would clobber the new request's pending state.
            if (deferred === pendingDeferred) {
                deferred = null
                _pending.value = null
            }
        }
    }

    /**
     * Grant-result entry point. No-op when nothing is pending -- the system dialog's result can arrive after
     * the Router timeout, at which point the suspending side has already returned with Timeout and no state should be written.
     */
    fun respond(granted: Boolean) {
        deferred?.complete(granted)
    }

    /** Adapter handed to the infrastructure layer; keeps systemops free of UI imports. */
    fun asRequester(): SystemPermissionRequester = SystemPermissionRequester { permissions, rationale ->
        request(permissions, rationale)
    }
}
