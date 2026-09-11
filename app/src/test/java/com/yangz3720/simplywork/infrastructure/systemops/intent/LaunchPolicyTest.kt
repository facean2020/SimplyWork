package com.yangz3720.simplywork.infrastructure.systemops.intent

import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPolicyTest {

    @Test
    fun testForegroundAlwaysStartsDirectly() {
        // Foreground start is safe for every launch context that permits direct starts.
        assertEquals(
            LaunchDecision.StartActivityNow,
            LaunchPolicy.decide(LaunchContext.AUTO, isForeground = true, canDrawOverlays = false)
        )
        assertEquals(
            LaunchDecision.StartActivityNow,
            LaunchPolicy.decide(LaunchContext.FOREGROUND_NOW, isForeground = true, canDrawOverlays = false)
        )
        assertEquals(
            LaunchDecision.StartActivityNow,
            LaunchPolicy.decide(LaunchContext.FROM_OVERLAY_SERVICE, isForeground = true, canDrawOverlays = true)
        )
    }

    @Test
    fun testBackgroundWithSawExemptionStartsDirectly() {
        // C4: SYSTEM_ALERT_WINDOW holders are exempt from background activity start restrictions.
        assertEquals(
            LaunchDecision.StartActivityNow,
            LaunchPolicy.decide(LaunchContext.AUTO, isForeground = false, canDrawOverlays = true)
        )
        assertEquals(
            LaunchDecision.StartActivityNow,
            LaunchPolicy.decide(LaunchContext.FROM_OVERLAY_SERVICE, isForeground = false, canDrawOverlays = true)
        )
    }

    @Test
    fun testBackgroundWithoutSawFallsBackToNotification() {
        // AUTO degrades to the notification path instead of a silently dropped start (P2).
        assertEquals(
            LaunchDecision.NotifyFallback,
            LaunchPolicy.decide(LaunchContext.AUTO, isForeground = false, canDrawOverlays = false)
        )
    }

    @Test
    fun testForegroundNowDemandIsBlockedInBackground() {
        // FOREGROUND_NOW is an explicit caller requirement: no fallback, explicit block.
        val decision = LaunchPolicy.decide(LaunchContext.FOREGROUND_NOW, isForeground = false, canDrawOverlays = true)
        assertTrue(decision is LaunchDecision.Blocked)
        assertTrue((decision as LaunchDecision.Blocked).reason.contains("FOREGROUND_NOW"))
    }

    @Test
    fun testOverlayContextWithoutSawFallsBackToNotification() {
        assertEquals(
            LaunchDecision.NotifyFallback,
            LaunchPolicy.decide(LaunchContext.FROM_OVERLAY_SERVICE, isForeground = false, canDrawOverlays = false)
        )
    }

    @Test
    fun testExplicitNotificationFallbackAlwaysNotifies() {
        assertEquals(
            LaunchDecision.NotifyFallback,
            LaunchPolicy.decide(LaunchContext.VIA_NOTIFICATION_FALLBACK, isForeground = true, canDrawOverlays = true)
        )
    }
}
