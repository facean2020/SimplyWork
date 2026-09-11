package com.yangz3720.simplywork.infrastructure.systemops.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OperationSpecDangerLevelTest {

    /** §6.2 acceptance: CalendarInsertSpec switches danger level by write mode. */
    @Test
    fun testCalendarInsertSpecSwitchesDangerLevelByMode() {
        val editorIntent = CalendarInsertSpec(
            title = "Standup",
            startTimeEpochMs = 0L,
            endTimeEpochMs = 1L,
            mode = CalendarWriteMode.EDITOR_INTENT
        )
        val directWrite = editorIntent.copy(mode = CalendarWriteMode.DIRECT_WRITE)

        assertEquals(DangerLevel.USER_VISIBLE, editorIntent.dangerLevel)
        assertEquals(DangerLevel.USER_CONFIRMING, directWrite.dangerLevel)
    }

    @Test
    fun testStaticDangerLevels() {
        assertEquals(DangerLevel.SAFE, ClipboardReadSpec.dangerLevel)
        assertEquals(DangerLevel.USER_VISIBLE, ClipboardWriteSpec("l", "t").dangerLevel)
        assertEquals(DangerLevel.USER_VISIBLE, NotificationPostSpec("ch", "t", "m").dangerLevel)
        assertEquals(DangerLevel.USER_VISIBLE, ShareTextSpec("t", "c").dangerLevel)
        assertEquals(DangerLevel.USER_CONFIRMING, DeepLinkSpec("https://example.com").dangerLevel)
        assertEquals(DangerLevel.USER_CONFIRMING, AppOpenSpec("com.example").dangerLevel)
    }

    @Test
    fun testCalendarDefaultsToEditorIntent() {
        val spec = CalendarInsertSpec(
            title = "Lunch",
            startTimeEpochMs = 0L,
            endTimeEpochMs = 1L
        )
        assertEquals(CalendarWriteMode.EDITOR_INTENT, spec.mode)
        assertEquals(DangerLevel.USER_VISIBLE, spec.dangerLevel)
    }
}
