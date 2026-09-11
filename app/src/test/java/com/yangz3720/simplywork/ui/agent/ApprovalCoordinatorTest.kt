package com.yangz3720.simplywork.ui.agent

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HITL 审批协调器（Q1 缝）行为：挂起等待 → 决策回灌 → 状态清理。
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
        coordinator.respond(true) // 不抛异常即可
        assertNull(coordinator.pending.value)
    }
}