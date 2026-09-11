package com.yangz3720.simplywork.infrastructure.systemops.tool

import com.yangz3720.simplywork.infrastructure.systemops.model.AppOpenSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarWriteMode
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardWriteSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DeepLinkSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.OperationSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ShareTextSpec
import kotlinx.serialization.json.Json
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
    fun testCatalogExposesSevenClosedTools() {
        val entries = SystemOperationToolFactory.toolEntries()
        assertEquals(
            listOf(
                SystemOperationToolFactory.TOOL_DEEPLINK_OPEN,
                SystemOperationToolFactory.TOOL_APP_OPEN,
                SystemOperationToolFactory.TOOL_CLIPBOARD_READ,
                SystemOperationToolFactory.TOOL_CLIPBOARD_WRITE,
                SystemOperationToolFactory.TOOL_CALENDAR_INSERT,
                SystemOperationToolFactory.TOOL_NOTIFY_POST,
                SystemOperationToolFactory.TOOL_SHARE_TEXT
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
            SystemOperationToolFactory.TOOL_SHARE_TEXT to ShareTextSpec("title", "content")
        )

        specs.forEach { (toolName, spec) ->
            val encoded = json.encodeToJsonElement(spec).jsonObject
            val parsed = SystemOperationToolFactory.parseArguments(toolName, encoded)
            assertTrue("parse failed for $toolName: ${parsed.exceptionOrNull()}", parsed.isSuccess)
            assertEquals("round-trip mismatch for $toolName", spec, parsed.getOrThrow())
        }
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
