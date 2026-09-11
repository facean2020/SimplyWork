package com.yangz3720.simplywork.infrastructure.systemops

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yangz3720.simplywork.infrastructure.systemops.intent.SystemIntentExecutorImpl
import com.yangz3720.simplywork.infrastructure.systemops.model.AppListSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarQuerySpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DeviceReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.LocationReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeUnit
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeCalcSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.Weekday
import com.yangz3720.simplywork.infrastructure.systemops.tool.SystemOperationToolFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device acceptance for the v1.2 read-side tools (appfunction-design.md §8.3).
 *
 * Separate from [SystemOpsDeviceAcceptanceTest] on purpose: that class assumes
 * POST_NOTIFICATIONS for every case in it, and the clock / device / app-inventory reads
 * need no permission at all — they must not be silently skipped because notifications
 * happen to be denied.
 *
 * Permission-gated cases (calendar, location) `assumeTrue` their own permission and skip
 * rather than fail when the harness did not grant it. Nothing here asserts a specific
 * timezone or coordinate: the device under test decides those.
 */
@RunWith(AndroidJUnit4::class)
class SystemOpsReadToolAcceptanceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val probe = SystemCapabilityProbeImpl.from(context)

    private val router: SystemOperationRouter =
        SystemOperationRouterImpl(SystemIntentExecutorImpl(context, probe), probe)

    private fun outputOf(result: SystemOperationResult) = when (result) {
        is SystemOperationResult.Success -> result.outputJson
            ?: throw AssertionError("read tool returned Success with no outputJson: $result")
        else -> throw AssertionError("expected Success, got: $result")
    }

    private fun longField(output: kotlinx.serialization.json.JsonObject, key: String): Long {
        val element = output[key] ?: throw AssertionError("$key missing from $output")
        return element.jsonPrimitive.content.toLong()
    }

    // ----- permission-free reads -----

    /** The whole point of sys_time_read: the model finally has a real epoch to build on. */
    @Test
    fun timeReadReturnsUsableEpoch() = runBlocking {
        val before = System.currentTimeMillis()
        val output = outputOf(router.execute(TimeReadSpec))
        val after = System.currentTimeMillis()

        val epochMs = longField(output, "epochMs")
        assertTrue("epochMs $epochMs outside [$before,$after]", epochMs in before..after)
        assertEquals("epochSeconds must agree", epochMs / 1000, longField(output, "epochSeconds"))
        assertNotNull("localIso", output["localIso"])
        assertNotNull("timeZoneId", output["timeZoneId"])
        // Offset must be a sane minute count; its value is the device's business, not ours.
        val offset = longField(output, "utcOffsetMinutes")
        assertTrue("implausible utcOffsetMinutes $offset", offset in -780..840)
        assertEquals(
            "weekday must agree with the epoch in the device's own zone",
            TimePointWeekday.of(epochMs),
            output["weekday"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun timeCalcOffsetsAgreeWithWallClock() = runBlocking {
        val now = longField(outputOf(router.execute(TimeReadSpec)), "epochMs")

        val plusOneDay = outputOf(router.execute(TimeCalcSpec(amount = 1, unit = TimeUnit.DAY, baseEpochMs = now)))
        assertEquals(24L * 60 * 60 * 1000, longField(plusOneDay, "deltaMs"))
        assertEquals(now + 24L * 60 * 60 * 1000, longField(plusOneDay, "epochMs"))

        // Weekday snapping must land on a real day of that name, whatever the zone is,
        // and must move strictly forward (never "today").
        val snapped = outputOf(router.execute(TimeCalcSpec(weekday = Weekday.FRIDAY, baseEpochMs = now)))
        val target = longField(snapped, "epochMs")
        assertEquals("FRIDAY", snapped["weekday"]!!.jsonPrimitive.content)
        assertTrue("snap must move forward, got $target from base $now", target > now)
        assertTrue("snap must be within a week", target - now <= 7L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun deviceReadReportsCoreStateWithoutAnyPermission() = runBlocking {
        val output = outputOf(router.execute(DeviceReadSpec))
        listOf("batteryPercent", "batteryCharging", "batteryStatus", "storage", "memory", "locale", "timeZone")
            .forEach { assertNotNull("$it must be reported", output[it]) }

        val network = output["network"]!!.jsonObject
        assertTrue(
            "network.connected must be a boolean",
            network["connected"]!!.jsonPrimitive.content in listOf("true", "false")
        )
        assertEquals(
            "sdkInt must match the running device",
            android.os.Build.VERSION.SDK_INT.toString(),
            output["device"]!!.jsonObject["sdkInt"]!!.jsonPrimitive.content
        )
        // Battery is not guaranteed on an emulator — the field must exist, null is acceptable.
        assertTrue(
            "batteryPercent must be null or 0..100",
            output["batteryPercent"]!!.jsonPrimitive.content == "null" ||
                output["batteryPercent"]!!.jsonPrimitive.content.toInt() in 0..100
        )
    }

    /**
     * Our own package has a LAUNCHER intent filter, so it must always appear in the
     * launchable inventory — a deterministic assertion that does not depend on what else
     * the device has installed.
     */
    @Test
    fun appListContainsOurOwnLaunchablePackage() = runBlocking {
        val output = outputOf(router.execute(AppListSpec(query = "simplywork", limit = 50)))
        val packages = output["apps"]!!.jsonArray.map { it.jsonObject["packageName"]!!.jsonPrimitive.content }
        assertTrue(
            "own package missing from launchable inventory: $packages",
            packages.contains(context.packageName)
        )
        assertTrue(
            "totalMatching must count at least the hit",
            output["totalMatching"]!!.jsonPrimitive.content.toInt() >= 1
        )
    }

    @Test
    fun appListRespectsLimitAndReportsTruncation() = runBlocking {
        val output = outputOf(router.execute(AppListSpec(limit = 1)))
        assertTrue("limit=1 must return at most one app", output["apps"]!!.jsonArray.size <= 1)
        assertEquals(1, output["returned"]!!.jsonPrimitive.content.toInt())
        val total = output["totalMatching"]!!.jsonPrimitive.content.toInt()
        assertEquals(
            "truncated must reflect whether anything was left out",
            total > 1,
            output["truncated"]!!.jsonPrimitive.content.toBoolean()
        )
    }

    // ----- permission-gated reads -----

    @Test
    fun calendarQueryReadsWindowWhenPermissionGranted() = runBlocking {
        assumeTrue(
            "READ_CALENDAR not granted — run: adb shell pm grant <pkg> android.permission.READ_CALENDAR",
            hasPerm(Manifest.permission.READ_CALENDAR)
        )
        val output = outputOf(router.execute(CalendarQuerySpec(limit = 5)))
        assertNotNull("windowStartIso", output["windowStartIso"])
        assertNotNull("windowEndIso", output["windowEndIso"])
        assertTrue("count must be non-negative", output["count"]!!.jsonPrimitive.content.toInt() >= 0)
        // An empty schedule is a legitimate Success with count=0 — never a Failed (P2).
        output["events"]!!.jsonArray.forEach { event ->
            val obj = event.jsonObject
            assertNotNull("event.title", obj["title"])
            assertNotNull("event.startTimeEpochMs", obj["startTimeEpochMs"])
            assertNotNull("event.allDay", obj["allDay"])
        }
    }

    @Test
    fun calendarQueryReportsPermissionRequiredWhenDenied() = runBlocking {
        assumeTrue("READ_CALENDAR must be absent for this case", !hasPerm(Manifest.permission.READ_CALENDAR))
        val result = router.execute(CalendarQuerySpec())
        assertTrue("expected PermissionRequired, got $result", result is SystemOperationResult.PermissionRequired)
        val denied = result as SystemOperationResult.PermissionRequired
        assertEquals(listOf(Manifest.permission.READ_CALENDAR), denied.permissions)
        assertTrue(
            "rationale must offer the permission-free alternative",
            denied.rationale.contains("EDITOR_INTENT")
        )
    }

    /**
     * C9 regression anchor: "no runtime permission" and "user turned Location off" are different
     * failures with different fixes. Collapsing them makes the agent ask for a permission that
     * will never help.
     */
    @Test
    fun locationReadDistinguishesMissingPermissionFromDeviceSwitchOff() = runBlocking {
        val result = router.execute(LocationReadSpec())
        when (result) {
            is SystemOperationResult.PermissionRequired -> assertTrue(
                "coarse location permission expected, got ${result.permissions}",
                result.permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION)
            )

            is SystemOperationResult.SecurityBlocked -> assertTrue(
                "blocked reason must point at the system Location toggle, not at permissions: ${result.reason}",
                result.reason.contains("location", ignoreCase = true) ||
                    result.reason.contains("provider", ignoreCase = true)
            )

            is SystemOperationResult.Success -> {
                val output = result.outputJson!!
                assertNotNull("latitude", output["latitude"])
                assertNotNull("longitude", output["longitude"])
                assertNotNull("accuracyMeters must be reported so the model can judge the fix", output["accuracyMeters"])
                assertNotNull("isFromCache", output["isFromCache"])
            }

            /**
             * Providers enabled but no fix within the call budget is a *designed* terminal
             * state (P2): a cold GPS fix indoors routinely outlasts the budget. Legitimate
             * only with the actionable guidance attached — a bare failure would strand the
             * model with no next step.
             */
            is SystemOperationResult.Failed -> assertTrue(
                "no-fix failure must carry the actionable guidance: ${result.message}",
                result.message.contains("No position fix") &&
                    (result.message.contains("APPROXIMATE") || result.message.contains("maxAgeSeconds"))
            )

            else -> throw AssertionError("unexpected terminal result: $result")
        }
    }

    /** A device without a geocoder backend must still hand back a usable fix (C10). */
    @Test
    fun locationReadWithAddressNeverFailsTheFix() = runBlocking {
        assumeTrue(
            "location permission not granted",
            hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        val result = router.execute(LocationReadSpec(includeAddress = true))
        if (result is SystemOperationResult.Success) {
            val output = result.outputJson!!
            assertNotNull("latitude", output["latitude"])
            assertTrue(
                "address must be present or explicitly unavailable",
                output.containsKey("address") || output.containsKey("addressUnavailable")
            )
        }
    }

    @Test
    fun readToolSchemasAdmitEmptyArguments() {
        // The model legitimately calls these with {} — the parser must not require anything.
        listOf(
            SystemOperationToolFactory.TOOL_TIME_READ,
            SystemOperationToolFactory.TOOL_DEVICE_READ,
            SystemOperationToolFactory.TOOL_APP_LIST,
            SystemOperationToolFactory.TOOL_CALENDAR_QUERY,
            SystemOperationToolFactory.TOOL_LOCATION_READ,
            SystemOperationToolFactory.TOOL_TIME_CALC
        ).forEach { name ->
            assertTrue("$name must accept {}", SystemOperationToolFactory.parseArguments(name, buildJsonObject { }).isSuccess)
        }
    }

    /** Bounds the parser is supposed to enforce on-device too. */
    @Test
    fun readToolBoundsFailAtParseTimeNotSilentlyClamped() {
        assertTrue(SystemOperationToolFactory.parseArguments(SystemOperationToolFactory.TOOL_APP_LIST, buildJsonObject { put("limit", 0) }).isFailure)
        assertTrue(SystemOperationToolFactory.parseArguments(SystemOperationToolFactory.TOOL_TIME_CALC, buildJsonObject { put("timeOfDay", "13pm") }).isFailure)
    }

    private fun hasPerm(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** Recomputes the expected weekday name from the epoch in the device's own zone. */
private object TimePointWeekday {
    fun of(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .dayOfWeek.name
}
