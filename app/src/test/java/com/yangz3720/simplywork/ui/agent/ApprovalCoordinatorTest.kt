package com.yangz3720.simplywork.ui.agent

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HITL approval coordinator (seam Q1) behaviour: suspend and wait -> decision fed back -> state cleanup.
 */
class ApprovalCoordinatorTest {

    @Test
    fun `request suspends and approve completes with true`() = runTest {
        val coordinator = ApprovalCoordinator()
        var result: Boolean? = null
        val job = launch { result = coordinator.request("sys_open_app", """{"packageName":"com.x"}""") }

        advanceUntilIdle()
        assertEquals("sys_open_app", coordinator.pending.value?.toolName)

        coordinator.respond(true)
        advanceUntilIdle()
        assertEquals(true, result)
        assertNull(coordinator.pending.value)
        job.join()
    }

    @Test
    fun `deny completes with false`() = runTest {
        val coordinator = ApprovalCoordinator()
        var result: Boolean? = null
        val job = launch { result = coordinator.request("sys_deep_link", """{"uri":"https://x"}""") }

        advanceUntilIdle()
        coordinator.respond(false)
        advanceUntilIdle()
        assertEquals(false, result)
        job.join()
    }

    @Test
    fun `cancelled request clears pending state`() = runTest {
        val coordinator = ApprovalCoordinator()
        val job = launch {
            runCatching { coordinator.request("sys_open_app", "{}") }
        }

        advanceUntilIdle()
        assertEquals("sys_open_app", coordinator.pending.value?.toolName)

        job.cancel()
        advanceUntilIdle()
        assertNull(coordinator.pending.value)
    }

    @Test
    fun `respond with no pending request is a no-op`() {
        val coordinator = ApprovalCoordinator()
        coordinator.respond(true) // not throwing is enough
        assertNull(coordinator.pending.value)
    }
}