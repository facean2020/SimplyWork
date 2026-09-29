package com.yangz3720.simplywork.infrastructure.systemops.model

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure date math behind `sys_time_calc`. Pinned to a fixed zone and fixed base instants —
 * these must not drift with the machine running the build.
 */
class TimeCalculationTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-09-09 is a Wednesday; 2026-09-11 is the Friday of the same week. */
    private val wednesdayMorning: Long = atLocal("2026-09-09T10:00:00")
    private val fridayMorning: Long = atLocal("2026-09-11T10:00:00")

    private fun atLocal(iso: String): Long =
        LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    private fun resolve(spec: TimeCalcSpec): TimeShift =
        TimeCalculation.resolve(spec, nowEpochMs = wednesdayMorning, zone = zone).getOrThrow()

    private fun localDateOf(epochMs: Long): String =
        TimePoint.of(epochMs, zone).localDate

    @Test
    fun testPlainDayOffset() {
        val shift = resolve(TimeCalcSpec(amount = 3, unit = TimeUnit.DAY))
        assertEquals("2026-09-12", localDateOf(shift.target.epochMs))
        assertEquals(3L * 24 * 60 * 60 * 1000, shift.deltaMs)
    }

    @Test
    fun testNegativeOffsetGoesBackwards() {
        val shift = resolve(TimeCalcSpec(amount = -2, unit = TimeUnit.DAY))
        assertEquals("2026-09-07", localDateOf(shift.target.epochMs))
        assertTrue(shift.deltaMs < 0)
    }

    /** The Chinese phrase for "next Friday" from Wednesday lands on the Friday two days later. */
    @Test
    fun testWeekdaySnapMovesForwardToNextOccurrence() {
        val shift = resolve(TimeCalcSpec(weekday = Weekday.FRIDAY))
        assertEquals("2026-09-11", localDateOf(shift.target.epochMs))
        assertEquals("FRIDAY", TimePoint.of(shift.target.epochMs, zone).weekday)
    }

    /**
     * The snap is *strictly* forward: asking for FRIDAY while already on Friday must land on
     * next week, which is what the Chinese "next Friday" phrase means. "This Friday" is expressible without the field.
     */
    @Test
    fun testWeekdaySnapNeverReturnsTheSameDay() {
        val spec = TimeCalcSpec(weekday = Weekday.FRIDAY, baseEpochMs = fridayMorning)
        val shift = TimeCalculation.resolve(spec, nowEpochMs = wednesdayMorning, zone = zone).getOrThrow()
        assertEquals("2026-09-18", localDateOf(shift.target.epochMs))
    }

    @Test
    fun testTimeOfDayIsAppliedAfterTheOffset() {
        val shift = resolve(TimeCalcSpec(weekday = Weekday.FRIDAY, timeOfDay = "15:30"))
        assertEquals("2026-09-11", localDateOf(shift.target.epochMs))
        assertEquals("15:30:00", TimePoint.of(shift.target.epochMs, zone).localTime)
    }

    @Test
    fun testTimeOfDayAcceptsSingleDigitHourAndWhitespace() {
        val shift = resolve(TimeCalcSpec(timeOfDay = " 9:05"))
        assertEquals("09:05:00", TimePoint.of(shift.target.epochMs, zone).localTime)
    }

    /** Calendar-field shifts clamp rather than overflowing into the next month. */
    @Test
    fun testMonthShiftClampsDayOfMonth() {
        val jan31 = atLocal("2026-01-31T08:00:00")
        val shift = TimeCalculation.resolve(
            TimeCalcSpec(amount = 1, unit = TimeUnit.MONTH, baseEpochMs = jan31),
            nowEpochMs = jan31,
            zone = zone
        ).getOrThrow()
        assertEquals("2026-02-28", localDateOf(shift.target.epochMs))
    }

    @Test
    fun testYearShiftClampsAcrossLeapYear() {
        val leapDay = atLocal("2024-02-29T12:00:00")
        val shift = TimeCalculation.resolve(
            TimeCalcSpec(amount = 1, unit = TimeUnit.YEAR, baseEpochMs = leapDay),
            nowEpochMs = leapDay,
            zone = zone
        ).getOrThrow()
        assertEquals("2025-02-28", localDateOf(shift.target.epochMs))
    }

    /** baseEpochMs wins over "now" so the model can chain results across calls. */
    @Test
    fun testExplicitBaseOverridesNow() {
        val shift = resolve(TimeCalcSpec(amount = 1, unit = TimeUnit.DAY, baseEpochMs = fridayMorning))
        assertEquals("2026-09-12", localDateOf(shift.target.epochMs))
        assertEquals("2026-09-11", shift.base.localDate)
    }

    @Test
    fun testZeroOffsetReturnsTheBaseUnchanged() {
        val shift = resolve(TimeCalcSpec())
        assertEquals(wednesdayMorning, shift.target.epochMs)
        assertEquals(0L, shift.deltaMs)
    }

    // ----- validation -----

    @Test
    fun testValidSpecsPassValidation() {
        assertNull(TimeCalculation.validate(TimeCalcSpec()))
        assertNull(TimeCalculation.validate(TimeCalcSpec(amount = -5, unit = TimeUnit.HOUR, timeOfDay = "23:59")))
    }

    @Test
    fun testRejectsNon24HourTimeOfDay() {
        listOf("3pm", "15:70", "24:00", "15", "half past three", "").forEach { bad ->
            val message = TimeCalculation.validate(TimeCalcSpec(timeOfDay = bad))
            assertTrue("'$bad' should be rejected", message != null && message.contains("timeOfDay"))
        }
    }

    @Test
    fun testRejectsAbsurdAmounts() {
        val message = TimeCalculation.validate(TimeCalcSpec(amount = TimeCalculation.MAX_ABS_AMOUNT + 1))
        assertTrue(message != null && message.contains("amount"))
        assertTrue(
            TimeCalculation.validate(TimeCalcSpec(amount = -TimeCalculation.MAX_ABS_AMOUNT)) == null
        )
    }

    @Test
    fun testResolveFailsRatherThanGuessingOnInvalidInput() {
        val result = TimeCalculation.resolve(
            TimeCalcSpec(timeOfDay = "25:00"),
            nowEpochMs = wednesdayMorning,
            zone = zone
        )
        assertTrue(result.isFailure)
    }
}
