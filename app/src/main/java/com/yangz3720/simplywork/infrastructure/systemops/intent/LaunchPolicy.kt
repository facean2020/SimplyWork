package com.yangz3720.simplywork.infrastructure.systemops.intent

import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext

/**
 * Pure decision output of the C4 background-activity-launch policy.
 * Kept free of Android dependencies so the full matrix is unit-testable on the JVM.
 */
sealed interface LaunchDecision {
    /** Safe to fire startActivity right now (app foreground, or SAW exemption holds). */
    data object StartActivityNow : LaunchDecision

    /** Background launch would be silently dropped by the system — deliver via a
     *  tappable notification instead (user tap = user-initiated launch, naturally compliant). */
    data object NotifyFallback : LaunchDecision

    /** Caller demanded a strict foreground launch but conditions are not met. */
    data class Blocked(val reason: String) : LaunchDecision
}

object LaunchPolicy {

    /**
     * C4 decision matrix.
     *
     * Note: the OS does NOT throw when a background activity start is denied — it silently
     * drops the start and logs. That is exactly why the decision must happen BEFORE firing
     * the intent (P2: no silent failures).
     */
    fun decide(launchContext: LaunchContext, isForeground: Boolean, canDrawOverlays: Boolean): LaunchDecision =
        when (launchContext) {
            LaunchContext.VIA_NOTIFICATION_FALLBACK -> LaunchDecision.NotifyFallback

            LaunchContext.FOREGROUND_NOW ->
                if (isForeground) LaunchDecision.StartActivityNow
                else LaunchDecision.Blocked("FOREGROUND_NOW requested but the app has no foreground focus")

            LaunchContext.FROM_OVERLAY_SERVICE ->
                // Overlay windows are only shown when SAW was granted; without it the launch
                // would be dropped, so fall back to the notification path.
                if (canDrawOverlays) LaunchDecision.StartActivityNow else LaunchDecision.NotifyFallback

            LaunchContext.AUTO -> when {
                isForeground -> LaunchDecision.StartActivityNow
                canDrawOverlays -> LaunchDecision.StartActivityNow // SAW exemption (C4)
                else -> LaunchDecision.NotifyFallback
            }
        }
}
