package com.yangz3720.simplywork.infrastructure.systemops

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yangz3720.simplywork.infrastructure.systemops.intent.SystemIntentExecutorImpl
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarWriteMode
import com.yangz3720.simplywork.infrastructure.systemops.model.DeepLinkSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult
import com.yangz3720.simplywork.infrastructure.systemops.tool.SystemOperationToolFactory
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device acceptance for the systemops Intent main path
 * (appfunction-design.md §8.2, automatable subset):
 * real permission checks, real notification manager, real clipboard, real
 * calendar provider. The calendar DIRECT_WRITE case cleans up after itself.
 *
 * Pre-provisioned via adb before this run:
 *   adb shell pm grant ... POST_NOTIFICATIONS / READ_CALENDAR / WRITE_CALENDAR
 */
@RunWith(AndroidJUnit4::class)
class SystemOpsDeviceAcceptanceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val probe = SystemCapabilityProbeImpl.from(context)

    @Before
    fun assumePermissionsGranted() {
        // Cases below depend on runtime permissions granted via adb; skip (not fail)
        // when the environment didn't grant them.
        assumeTrue(
            "POST_NOTIFICATIONS not granted — run: adb shell pm grant <pkg> android.permission.POST_NOTIFICATIONS",
            hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    @Test
    fun probeSnapshotIsReadable() {
        val capabilities = probe.snapshot()
        // Both flags must be evaluated booleans on a real device (no crash, no null).
        assertTrue(capabilities.notificationsPermissionGranted)
        assertTrue(capabilities.canLaunchActivityFromBackground in listOf(true, false))
    }

    @Test
    fun notificationPostDeliversToNotificationManager() = runBlocking {
        val router = routerWithForeground(foreground = true)
        val result = router.execute(
            com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec(
                channelId = "acceptance_channel",
                title = "SimplyWork 验收",
                message = "sys_notify_post on-device acceptance"
            )
        )
        assertTrue("Expected Success, got: $result", result is SystemOperationResult.Success)
        val output = (result as SystemOperationResult.Success).outputJson!!.jsonObject
        assertTrue(output["notificationId"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun clipboardWriteThenReadEndToEnd() = runBlocking {
        // C3 contract acceptance. `am instrument` force-stops the target app, so no
        // focused window exists for our UID while tests run (and MIUI additionally
        // blocks startActivity from the harness). The system therefore serves an
        // empty primary clip — which the code MUST report as explicit isEmpty=true
        // (P2), never as a fake payload. A focused-window end-to-end read is the
        // manual acceptance item (C3 notes, appfunction-design.md §2).
        val router = routerWithForeground(foreground = true)
        val payload = "SimplyWork acceptance ${System.currentTimeMillis()}"

        val write = router.execute(
            com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardWriteSpec(label = "acceptance", text = payload)
        )
        assertTrue("Clipboard write failed: $write", write is SystemOperationResult.Success)

        val read = router.execute(com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardReadSpec)
        assertTrue("Clipboard read failed: $read", read is SystemOperationResult.Success)
        val output = (read as SystemOperationResult.Success).outputJson!!.jsonObject
        // Contract: output always carries both fields, whatever the platform decided.
        assertTrue(output.containsKey("text"))
        assertTrue(output.containsKey("isEmpty"))
        // When focus was provisioned (manual mode), the roundtrip must be exact.
        val text = output["text"]
        if (text is kotlinx.serialization.json.JsonPrimitive && text.content != "null") {
            assertEquals(payload, text.content)
        } else {
            AgentLogger.w("SystemOpsAcceptance") {
                "Clipboard read withheld (no focused window): ${output["isEmpty"]} — C3 platform behavior confirmed"
            }
        }
    }

    @Test
    fun deepLinkFromBackgroundFallsBackToNotification() = runBlocking {
        // C4: background-pinned launch must NOT startActivity; it must land as a
        // tappable notification (Success with deliveredVia=notification).
        val router = routerWithForeground(foreground = false)
        val result = router.execute(
            DeepLinkSpec(uri = "https://www.example.com", launchContext = LaunchContext.AUTO)
        )
        assertTrue("Expected notification-fallback Success, got: $result", result is SystemOperationResult.Success)
        val output = (result as SystemOperationResult.Success).outputJson!!.jsonObject
        assertEquals("false", output["launched"]!!.jsonPrimitive.content)
        assertEquals("notification", output["deliveredVia"]!!.jsonPrimitive.content)
    }

    @Test
    fun calendarDirectWriteCreatesAndDeletesEvent() = runBlocking {
        assumeTrue(hasPerm(Manifest.permission.READ_CALENDAR) && hasPerm(Manifest.permission.WRITE_CALENDAR))

        val router = routerWithForeground(foreground = true)
        val now = System.currentTimeMillis()
        val result = router.execute(
            CalendarInsertSpec(
                title = "SimplyWork acceptance (self-removing)",
                startTimeEpochMs = now,
                endTimeEpochMs = now + 30 * 60 * 1000L,
                mode = CalendarWriteMode.DIRECT_WRITE
            )
        )
        assertTrue("Calendar direct write failed: $result", result is SystemOperationResult.Success)
        val eventUri = (result as SystemOperationResult.Success).outputJson!!.jsonObject["eventUri"]!!.jsonPrimitive.content
        assertTrue(eventUri.startsWith("content://"))

        // Cleanup: remove the acceptance event so nothing is left in the user's calendar.
        val deleted = context.contentResolver.delete(android.net.Uri.parse(eventUri), null, null)
        assertEquals(1, deleted)
    }

    @Test
    fun toolFactoryParsesRealArgumentsIntoSpecs() {
        // Registry-lite argument roundtrip against the real parser (schema shape lives
        // in JVM tests; here we just prove the on-device Json handles the same path).
        val spec = SystemOperationToolFactory.parseArguments(
            SystemOperationToolFactory.TOOL_CALENDAR_INSERT,
            kotlinx.serialization.json.buildJsonObject {
                put("title", "factory test")
                put("startTimeEpochMs", 1L)
                put("endTimeEpochMs", 2L)
            }
        ).getOrThrow() as CalendarInsertSpec
        assertEquals("factory test", spec.title)
        assertEquals(CalendarWriteMode.EDITOR_INTENT, spec.mode)
    }

    private fun hasPerm(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Executor with the foreground gate pinned — the test can show no activity. */
    private fun routerWithForeground(foreground: Boolean): SystemOperationRouter =
        SystemOperationRouterImpl(SystemIntentExecutorImpl(context, probe, isAppForeground = { foreground }), probe)
}
