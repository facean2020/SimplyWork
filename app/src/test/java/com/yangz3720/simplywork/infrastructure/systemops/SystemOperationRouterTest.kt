package com.yangz3720.simplywork.infrastructure.systemops

import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.systemops.intent.SystemIntentExecutor
import com.yangz3720.simplywork.infrastructure.systemops.model.AppOpenSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarWriteMode
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardWriteSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DeepLinkSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.OperationSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ShareTextSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemOperationRouterTest {

    private val executor: SystemIntentExecutor = mockk(relaxed = true)
    private val probe = SystemCapabilityProbeImpl(
        notificationsGranted = { true },
        overlayGranted = { false }
    )
    private val router = SystemOperationRouterImpl(executor, probe)

    @Test
    fun testDispatchesEachSpecToTheRightExecutorMethod() = runTest {
        val clipboardSuccess = SystemOperationResult.Success(
            outputJson = null,
            executedVia = com.yangz3720.simplywork.infrastructure.systemops.model.ExecutionPath.SYSTEM_INTENT
        )
        coEvery { executor.launchDeepLink("https://x", LaunchContext.AUTO) } returns clipboardSuccess
        coEvery { executor.openApp("com.a") } returns clipboardSuccess
        coEvery { executor.readClipboard() } returns clipboardSuccess
        coEvery { executor.writeClipboard("l", "t") } returns clipboardSuccess
        coEvery { executor.insertCalendarEvent(any()) } returns clipboardSuccess
        coEvery { executor.postNotification(any()) } returns clipboardSuccess
        coEvery { executor.shareText("t", "c") } returns clipboardSuccess

        val calendar = CalendarInsertSpec(
            title = "e", startTimeEpochMs = 0, endTimeEpochMs = 1, mode = CalendarWriteMode.EDITOR_INTENT
        )
        val notification = NotificationPostSpec("ch", "t", "m")

        val specs: List<Pair<OperationSpec, suspend (OperationSpec) -> SystemOperationResult>> = listOf(
            DeepLinkSpec("https://x") to { router.execute(it) },
            AppOpenSpec("com.a") to { router.execute(it) },
            ClipboardReadSpec to { router.execute(it) },
            ClipboardWriteSpec("l", "t") to { router.execute(it) },
            calendar to { router.execute(it) },
            notification to { router.execute(it) },
            ShareTextSpec("t", "c") to { router.execute(it) }
        )
        specs.forEach { (spec, call) -> assertEquals(clipboardSuccess, call(spec)) }

        coVerify(exactly = 1) { executor.launchDeepLink("https://x", LaunchContext.AUTO) }
        coVerify(exactly = 1) { executor.openApp("com.a") }
        coVerify(exactly = 1) { executor.readClipboard() }
        coVerify(exactly = 1) { executor.writeClipboard("l", "t") }
        coVerify(exactly = 1) { executor.insertCalendarEvent(calendar) }
        coVerify(exactly = 1) { executor.postNotification(notification) }
        coVerify(exactly = 1) { executor.shareText("t", "c") }
    }

    @Test
    fun testStampsExecutionTimeOnSuccess() = runTest {
        coEvery { executor.readClipboard() } returns SystemOperationResult.Success()
        val result = router.execute(ClipboardReadSpec)
        assertTrue(result is SystemOperationResult.Success)
        assertTrue((result as SystemOperationResult.Success).executionTimeMs >= 0)
    }

    /** §4.3: path implementations throw, the router normalizes to Failed — tool-core never sees exceptions. */
    @Test
    fun testNormalizesSystemOperationExceptionsIntoFailed() = runTest {
        coEvery { executor.insertCalendarEvent(any()) } throws
            InfrastructureException.SystemOperation.CalendarWriteFailed("provider crash")
        val result = router.execute(
            CalendarInsertSpec(title = "e", startTimeEpochMs = 0, endTimeEpochMs = 1, mode = CalendarWriteMode.DIRECT_WRITE)
        )
        assertEquals(
            SystemOperationResult.Failed(
                message = "Calendar write failed: provider crash",
                cause = null
            ),
            result
        )
    }

    @Test
    fun testNormalizesUnexpectedExceptionsIntoFailed() = runTest {
        coEvery { executor.readClipboard() } throws IllegalStateException("boom")
        val result = router.execute(ClipboardReadSpec)
        assertEquals(
            SystemOperationResult.Failed(
                message = "Unhandled failure in ClipboardReadSpec: boom",
                cause = "IllegalStateException"
            ),
            result
        )
    }

    /** P5: the caller is guaranteed a result within the timeout. */
    @Test
    fun testTimeoutIsExplicit() = runTest {
        coEvery { executor.readClipboard() } coAnswers { delay(500); SystemOperationResult.Success() }
        val result = router.execute(ClipboardReadSpec, timeoutMs = 100)
        assertTrue("expected Timeout but was $result", result is SystemOperationResult.Timeout)
        assertEquals(100L, (result as SystemOperationResult.Timeout).timeoutMs)
    }

    @Test
    fun testCapabilitiesDelegateToProbe() {
        val capabilities = router.capabilities()
        assertTrue(capabilities.notificationsPermissionGranted)
        assertTrue(!capabilities.canLaunchActivityFromBackground)
    }
}
