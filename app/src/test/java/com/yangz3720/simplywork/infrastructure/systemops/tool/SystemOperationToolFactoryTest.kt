package com.yangz3720.simplywork.infrastructure.systemops.tool

import com.yangz3720.simplywork.infrastructure.systemops.model.AppListSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.AppOpenSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarQuerySpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarWriteMode
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardWriteSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DeepLinkSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import com.yangz3720.simplywork.infrastructure.systemops.model.DeviceReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import com.yangz3720.simplywork.infrastructure.systemops.model.LocationAccuracy
import com.yangz3720.simplywork.infrastructure.systemops.model.LocationReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.OperationSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ShareTextSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeUnit
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeCalcSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.Weekday
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemOperationToolFactoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun testCatalogExposesThirteenClosedTools() {
        val entries = SystemOperationToolFactory.toolEntries()
        assertEquals(
            listOf(
                SystemOperationToolFactory.TOOL_DEEPLINK_OPEN,
                SystemOperationToolFactory.TOOL_APP_OPEN,
                SystemOperationToolFactory.TOOL_CLIPBOARD_READ,
                SystemOperationToolFactory.TOOL_CLIPBOARD_WRITE,
                SystemOperationToolFactory.TOOL_CALENDAR_INSERT,
                SystemOperationToolFactory.TOOL_NOTIFY_POST,
                SystemOperationToolFactory.TOOL_SHARE_TEXT,
                SystemOperationToolFactory.TOOL_TIME_READ,
                SystemOperationToolFactory.TOOL_TIME_CALC,
                SystemOperationToolFactory.TOOL_DEVICE_READ,
                SystemOperationToolFactory.TOOL_APP_LIST,
                SystemOperationToolFactory.TOOL_CALENDAR_QUERY,
                SystemOperationToolFactory.TOOL_LOCATION_READ
            ),
            entries.map { it.definition.name }
        )
    }

    @Test
    fun testEntryDangerLevelsMatchSpecDefaults() {
        val byName = SystemOperationToolFactory.toolEntries().associateBy { it.definition.name }
        assertEquals(DangerLevel.USER_CONFIRMING, byName.getValue(SystemOperationToolFactory.TOOL_DEEPLINK_OPEN).dangerLevel)
        assertEquals(DangerLevel.USER_CONFIRMING, byName.getValue(SystemOperationToolFactory.TOOL_APP_OPEN).dangerLevel)
        assertEquals(DangerLevel.SAFE, byName.getValue(SystemOperationToolFactory.TOOL_CLIPBOARD_READ).dangerLevel)
        assertEquals(DangerLevel.USER_VISIBLE, byName.getValue(SystemOperationToolFactory.TOOL_CLIPBOARD_WRITE).dangerLevel)
        assertEquals(DangerLevel.USER_VISIBLE, byName.getValue(SystemOperationToolFactory.TOOL_CALENDAR_INSERT).dangerLevel)
        assertEquals(DangerLevel.USER_VISIBLE, byName.getValue(SystemOperationToolFactory.TOOL_NOTIFY_POST).dangerLevel)
        assertEquals(DangerLevel.USER_VISIBLE, byName.getValue(SystemOperationToolFactory.TOOL_SHARE_TEXT).dangerLevel)
        // v1.2 read side: clock/device/app inventory are side-effect-free reads, while calendar
        // and location reach private user data and must clear the HITL gate.
        assertEquals(DangerLevel.SAFE, byName.getValue(SystemOperationToolFactory.TOOL_TIME_READ).dangerLevel)
        assertEquals(DangerLevel.SAFE, byName.getValue(SystemOperationToolFactory.TOOL_TIME_CALC).dangerLevel)
        assertEquals(DangerLevel.SAFE, byName.getValue(SystemOperationToolFactory.TOOL_DEVICE_READ).dangerLevel)
        assertEquals(DangerLevel.SAFE, byName.getValue(SystemOperationToolFactory.TOOL_APP_LIST).dangerLevel)
        assertEquals(DangerLevel.USER_CONFIRMING, byName.getValue(SystemOperationToolFactory.TOOL_CALENDAR_QUERY).dangerLevel)
        assertEquals(DangerLevel.USER_CONFIRMING, byName.getValue(SystemOperationToolFactory.TOOL_LOCATION_READ).dangerLevel)
    }

    @Test
    fun testSchemasAreObjectsWithRequiredFields() {
        val byName = SystemOperationToolFactory.toolEntries().associateBy { it.definition.name }

        val deeplink = byName.getValue(SystemOperationToolFactory.TOOL_DEEPLINK_OPEN).definition.parametersSchema
        assertEquals("object", deeplink["type"]?.toString()?.trim('"'))
        val required = deeplink["required"].toString()
        assertTrue(required.contains("uri"))
        assertEquals("schemas must be closed", "false", deeplink["additionalProperties"]?.toString())
    }

    /** Every catalog schema — not just one sample — must be a closed object with a description per property. */
    @Test
    fun testEverySchemaIsClosedAndDescribed() {
        SystemOperationToolFactory.toolEntries().forEach { entry ->
            val schema = entry.definition.parametersSchema
            assertEquals("${entry.definition.name} must be an object schema", "object", schema["type"]?.toString()?.trim('"'))
            assertEquals("${entry.definition.name} must be closed", "false", schema["additionalProperties"]?.toString())
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            properties.forEach { (field, value) ->
                assertTrue(
                    "${entry.definition.name}.$field needs a description",
                    value.jsonObject["description"] != null
                )
            }
        }
    }

    /** Descriptions are the model's only documentation: an empty one silently disables a tool. */
    @Test
    fun testEveryToolHasNonBlankNameAndDescription() {
        SystemOperationToolFactory.toolEntries().forEach { entry ->
            assertTrue("${entry.definition.name} has a blank description", entry.definition.description.isNotBlank())
            assertTrue(
                "${entry.definition.name} must stay in the sys_ namespace",
                entry.definition.name.startsWith("sys_")
            )
        }
    }

    @Test
    fun testParseArgumentsRoundTrip() {
        val specs: Map<String, OperationSpec> = mapOf(
            SystemOperationToolFactory.TOOL_DEEPLINK_OPEN to DeepLinkSpec(
                uri = "https://example.com/x",
                launchContext = LaunchContext.FROM_OVERLAY_SERVICE
            ),
            SystemOperationToolFactory.TOOL_APP_OPEN to AppOpenSpec("com.example.app"),
            SystemOperationToolFactory.TOOL_CLIPBOARD_READ to ClipboardReadSpec,
            SystemOperationToolFactory.TOOL_CLIPBOARD_WRITE to ClipboardWriteSpec("label", "text"),
            SystemOperationToolFactory.TOOL_CALENDAR_INSERT to CalendarInsertSpec(
                title = "Standup",
                description = "daily",
                location = "Room 1",
                startTimeEpochMs = 1_700_000_000_000,
                endTimeEpochMs = 1_700_000_900_000,
                isAllDay = true,
                mode = CalendarWriteMode.DIRECT_WRITE
            ),
            SystemOperationToolFactory.TOOL_NOTIFY_POST to NotificationPostSpec("ch", "title", "message", priority = 1),
            SystemOperationToolFactory.TOOL_SHARE_TEXT to ShareTextSpec("title", "content"),
            SystemOperationToolFactory.TOOL_TIME_READ to TimeReadSpec,
            SystemOperationToolFactory.TOOL_TIME_CALC to TimeCalcSpec(
                amount = 2,
                unit = TimeUnit.WEEK,
                weekday = Weekday.FRIDAY,
                timeOfDay = "15:30",
                baseEpochMs = 1_700_000_000_000
            ),
            SystemOperationToolFactory.TOOL_DEVICE_READ to DeviceReadSpec,
            SystemOperationToolFactory.TOOL_APP_LIST to AppListSpec(query = "map", limit = 5, launchableOnly = false),
            SystemOperationToolFactory.TOOL_CALENDAR_QUERY to CalendarQuerySpec(
                startTimeEpochMs = 1_700_000_000_000,
                endTimeEpochMs = 1_700_086_400_000,
                query = "standup",
                limit = 5
            ),
            SystemOperationToolFactory.TOOL_LOCATION_READ to LocationReadSpec(
                accuracy = LocationAccuracy.PRECISE,
                maxAgeSeconds = 30,
                includeAddress = true
            )
        )

        specs.forEach { (toolName, spec) ->
            val encoded = json.encodeToJsonElement(spec).jsonObject
            val parsed = SystemOperationToolFactory.parseArguments(toolName, encoded)
            assertTrue("parse failed for $toolName: ${parsed.exceptionOrNull()}", parsed.isSuccess)
            assertEquals("round-trip mismatch for $toolName", spec, parsed.getOrThrow())
        }
    }

    /** Defaults must land on the parameterless read tools — the model is allowed to pass `{}`. */
    @Test
    fun testReadToolsParseEmptyArguments() {
        mapOf(
            SystemOperationToolFactory.TOOL_TIME_READ to TimeReadSpec,
            SystemOperationToolFactory.TOOL_DEVICE_READ to DeviceReadSpec,
            SystemOperationToolFactory.TOOL_TIME_CALC to TimeCalcSpec(),
            SystemOperationToolFactory.TOOL_APP_LIST to AppListSpec(),
            SystemOperationToolFactory.TOOL_CALENDAR_QUERY to CalendarQuerySpec(),
            SystemOperationToolFactory.TOOL_LOCATION_READ to LocationReadSpec()
        ).forEach { (toolName, expected) ->
            val parsed = SystemOperationToolFactory.parseArguments(toolName, buildJsonObject { })
            assertEquals("$toolName should accept {}", expected, parsed.getOrThrow())
        }
    }

    /**
     * Out-of-range arguments must fail the *parse*, which the audit gate turns into a
     * "Invalid arguments" Deny the model can read and retry from. Silently clamping them
     * would hide the model's mistake from itself (P2).
     */
    @Test
    fun testOutOfRangeArgumentsFailTheParse() {
        val cases = listOf(
            SystemOperationToolFactory.TOOL_APP_LIST to buildJsonObject { put("limit", 9999) },
            SystemOperationToolFactory.TOOL_CALENDAR_QUERY to buildJsonObject { put("limit", 0) },
            SystemOperationToolFactory.TOOL_CALENDAR_QUERY to buildJsonObject {
                put("startTimeEpochMs", 2_000)
                put("endTimeEpochMs", 1_000)
            },
            SystemOperationToolFactory.TOOL_LOCATION_READ to buildJsonObject { put("maxAgeSeconds", -1) },
            SystemOperationToolFactory.TOOL_TIME_CALC to buildJsonObject { put("timeOfDay", "3pm") },
            SystemOperationToolFactory.TOOL_TIME_CALC to buildJsonObject { put("amount", 999_999) }
        )
        cases.forEach { (toolName, args) ->
            val result = SystemOperationToolFactory.parseArguments(toolName, args)
            assertTrue("$toolName with $args should fail", result.isFailure)
        }
    }

    /** The failure message is model-facing: it has to name the offending field. */
    @Test
    fun testParseFailureMessageIsActionable() {
        val message = SystemOperationToolFactory.parseArguments(
            SystemOperationToolFactory.TOOL_TIME_CALC,
            buildJsonObject { put("timeOfDay", "13pm") }
        ).exceptionOrNull()?.message.orEmpty()
        assertTrue("got '$message'", message.contains("timeOfDay"))
    }

    @Test
    fun testParseToleratesUnknownExtraKeys() {
        val args = buildJsonObject {
            put("uri", "https://example.com")
            put("suspiciousExtra", "ignored")
        }
        val parsed = SystemOperationToolFactory.parseArguments(SystemOperationToolFactory.TOOL_DEEPLINK_OPEN, args)
        assertEquals(DeepLinkSpec("https://example.com"), parsed.getOrThrow())
    }

    @Test
    fun testParseUnknownToolFails() {
        val result = SystemOperationToolFactory.parseArguments("af_malicious", buildJsonObject { })
        assertTrue(result.isFailure)
    }

    @Test
    fun testParseMissingRequiredFieldFails() {
        val result = SystemOperationToolFactory.parseArguments(
            SystemOperationToolFactory.TOOL_APP_OPEN,
            buildJsonObject { put("wrong", "x") }
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun testCalendarDefaultsOnParse() {
        val parsed = SystemOperationToolFactory.parseArguments(
            SystemOperationToolFactory.TOOL_CALENDAR_INSERT,
            buildJsonObject {
                put("title", "Lunch")
                put("startTimeEpochMs", 1_700_000_000_000)
                put("endTimeEpochMs", 1_700_000_900_000)
            }
        ).getOrThrow() as CalendarInsertSpec
        assertEquals(CalendarWriteMode.EDITOR_INTENT, parsed.mode)
        assertEquals(false, parsed.isAllDay)
    }
}
