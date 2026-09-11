package com.yangz3720.simplywork.ui.agent

import com.yangz3720.simplywork.infrastructure.systemops.SystemPermissionRequester
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时权限协调器（v1.2 / P4 缝）行为：挂起等待 → 系统授权结果回灌 → 状态清理。
 * 与 [ApprovalCoordinatorTest] 同构，额外覆盖交给基础设施的适配器形状。
 */
class PermissionCoordinatorTest {

    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"

    @Test
    fun `request suspends and granted response completes with true`() = runTest {
        val coordinator = PermissionCoordinator()
        var result: Boolean? = null
        val job = launch { result = coordinator.request(listOf(coarse), "Locate this device.") }

        advanceUntilIdle()
        assertEquals(listOf(coarse), coordinator.pending.value?.permissions)
        assertEquals("Locate this device.", coordinator.pending.value?.rationale)

        coordinator.respond(true)
        advanceUntilIdle()
        assertEquals(true, result)
        assertNull(coordinator.pending.value)
        job.join()
    }

    @Test
    fun `denied response completes with false`() = runTest {
        val coordinator = PermissionCoordinator()
        var result: Boolean? = null
        val job = launch { result = coordinator.request(listOf(fine, coarse), "reason") }

        advanceUntilIdle()
        coordinator.respond(false)
        advanceUntilIdle()
        assertEquals(false, result)
        assertNull(coordinator.pending.value)
        job.join()
    }

    /**
     * The router wraps every tool call in `withTimeout`. If the user never answers the system
     * dialog, that timeout must cancel the wait *and* clear the pending state — otherwise a
     * ghost permission prompt is left on screen for the next, unrelated run.
     */
    @Test
    fun `cancelled request clears pending state`() = runTest {
        val coordinator = PermissionCoordinator()
        val job = launch {
            runCatching { coordinator.request(listOf(coarse), "reason") }
        }

        advanceUntilIdle()
        assertEquals(listOf(coarse), coordinator.pending.value?.permissions)

        job.cancel()
        advanceUntilIdle()
        assertNull(coordinator.pending.value)
    }

    /** A system dialog result can land after the caller already timed out; that must be inert. */
    @Test
    fun `late respond after cancellation is a no-op`() = runTest {
        val coordinator = PermissionCoordinator()
        val job = launch { runCatching { coordinator.request(listOf(coarse), "reason") } }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()

        coordinator.respond(true)
        assertNull(coordinator.pending.value)
    }

    @Test
    fun `respond with no pending request is a no-op`() {
        val coordinator = PermissionCoordinator()
        coordinator.respond(true) // 不抛异常即可
        assertNull(coordinator.pending.value)
    }

    /** The seam the executor actually holds: same semantics through the infrastructure interface. */
    @Test
    fun `asRequester bridges the infrastructure seam`() = runTest {
        val coordinator = PermissionCoordinator()
        val requester: SystemPermissionRequester = coordinator.asRequester()

        var result: Boolean? = null
        val job = launch { result = requester.request(listOf(fine), "needs location") }
        advanceUntilIdle()
        assertTrue("UI should observe the pending request", coordinator.pending.value != null)

        coordinator.respond(true)
        advanceUntilIdle()
        assertEquals(true, result)
        job.join()
    }

    /**
     * 复核 S10：串行假设被打破（竞态/重建）时，第二个 request 不允许让第一个调用方
     * 永久悬空 —— 旧请求按"未授予"收敛，且 finally 清理不得误伤新请求的挂起态。
     */
    @Test
    fun `overlapping request resolves the earlier one without clobbering state`() = runTest {
        val coordinator = PermissionCoordinator()
        var first: Boolean? = null
        var second: Boolean? = null
        val job1 = launch { first = coordinator.request(listOf(coarse), "first") }
        advanceUntilIdle()
        val job2 = launch { second = coordinator.request(listOf(fine), "second") }
        advanceUntilIdle()

        assertEquals(false, first)
        assertEquals(listOf(fine), coordinator.pending.value?.permissions)

        coordinator.respond(true)
        advanceUntilIdle()
        assertEquals(true, second)
        assertNull(coordinator.pending.value)
        job1.join()
        job2.join()
    }
}
