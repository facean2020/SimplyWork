package com.yangz3720.simplywork.infrastructure.systemops.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * v1.2 read side. The split is deliberate: clock / device / package inventory are
     * side-effect-free reads of non-personal state (SAFE), while calendar contents and live
     * coordinates reach private user data and must clear the HITL gate on every call.
     */
    @Test
    fun testReadSideDangerLevelsFollowDataSensitivity() {
        assertEquals(DangerLevel.SAFE, TimeReadSpec.dangerLevel)
        assertEquals(DangerLevel.SAFE, TimeCalcSpec().dangerLevel)
        assertEquals(DangerLevel.SAFE, DeviceReadSpec.dangerLevel)
        assertEquals(DangerLevel.SAFE, AppListSpec().dangerLevel)
        assertEquals(DangerLevel.USER_CONFIRMING, CalendarQuerySpec().dangerLevel)
        assertEquals(DangerLevel.USER_CONFIRMING, LocationReadSpec().dangerLevel)
        // Precision must not lower the bar.
        assertEquals(
            DangerLevel.USER_CONFIRMING,
            LocationReadSpec(accuracy = LocationAccuracy.PRECISE).dangerLevel
        )
    }

    /**
     * Reading the schedule is more sensitive than writing one through the calendar app's own
     * editor: the editor never exposes the user's existing events to us.
     */
    @Test
    fun testCalendarQueryIsMoreRestrictedThanEditorIntentInsert() {
        val insertViaEditor = CalendarInsertSpec(
            title = "Lunch", startTimeEpochMs = 0L, endTimeEpochMs = 1L
        )
        assertEquals(DangerLevel.USER_VISIBLE, insertViaEditor.dangerLevel)
        assertEquals(DangerLevel.USER_CONFIRMING, CalendarQuerySpec().dangerLevel)
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

    /** Defaults are what the model gets when it passes `{}`; they must be the conservative choice. */
    @Test
    fun testReadToolDefaultsAreConservative() {
        assertEquals(LocationAccuracy.APPROXIMATE, LocationReadSpec().accuracy)
        assertFalse("address lookup must be opt-in", LocationReadSpec().includeAddress)
        assertEquals(0, TimeCalcSpec().amount)
        assertEquals(null, TimeCalcSpec().baseEpochMs)
        assertEquals(true, AppListSpec().launchableOnly)
    }
}
