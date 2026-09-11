package com.yangz3720.simplywork.infrastructure.systemops

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCapabilityProbeTest {

    @Test
    fun testSnapshotReflectsCurrentGrantState() {
        val probe = SystemCapabilityProbeImpl(
            notificationsGranted = { true },
            overlayGranted = { false }
        )
        val capabilities = probe.snapshot()
        assertTrue(capabilities.notificationsPermissionGranted)
        assertFalse(capabilities.canLaunchActivityFromBackground)
    }

    @Test
    fun testProbeReevaluatesOnEachSnapshot() {
        // Simulates the user toggling a permission in system settings between checks.
        var granted = false
        val probe = SystemCapabilityProbeImpl(
            notificationsGranted = { granted },
            overlayGranted = { false }
        )
        assertFalse(probe.snapshot().notificationsPermissionGranted)
        granted = true
        assertTrue(probe.snapshot().notificationsPermissionGranted)
    }

    @Test
    fun testObserveEmitsFreshEvaluationPerCollect() = runTest {
        var overlay = false
        val probe = SystemCapabilityProbeImpl(
            notificationsGranted = { true },
            overlayGranted = { overlay }
        )
        assertFalse(probe.observe().first().canLaunchActivityFromBackground)
        overlay = true
        assertTrue(probe.observe().first().canLaunchActivityFromBackground)
    }
}
