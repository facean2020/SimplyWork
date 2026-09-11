package com.yangz3720.simplywork.infrastructure.systemops.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Machine + human views of one instant, as handed back to the model.
 *
 * The epoch is what the model feeds into `sys_calendar_insert`; the local ISO string and
 * weekday name are what it needs to phrase an answer to a person.
 */
data class TimePoint(
    val epochMs: Long,
    val localIso: String,
    val localDate: String,
    val localTime: String,
    val weekday: String,
    val timeZoneId: String,
    val utcOffsetMinutes: Int
) {
    companion object {
        fun of(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): TimePoint {
            val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
            return TimePoint(
                epochMs = epochMs,
                localIso = zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                localDate = zdt.toLocalDate().toString(),
                localTime = zdt.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                weekday = zdt.dayOfWeek.name,
                timeZoneId = zone.id,
                utcOffsetMinutes = zdt.offset.totalSeconds / 60
            )
        }
    }
}

/** Base + resolved pair, so the model can see what "next Friday" was measured from. */
data class TimeShift(val base: TimePoint, val target: TimePoint, val deltaMs: Long)

/**
 * Pure relative-time arithmetic behind `sys_time_calc` (v1.2).
 *
 * Lives in `model` rather than the executor so it is JVM-unit-testable: the project has no
 * Robolectric, and date math is the one part of the new tool surface with real bug potential
 * (DST, month overflow, weekday snapping).
 */
object TimeCalculation {

    /** Guard against a model-emitted absurd offset blowing up into year 10000+. */
    const val MAX_ABS_AMOUNT = 10_000

    /** Models habitually write "9:05"; ISO would demand "09:05", so the short form is accepted. */
    private val SHORT_TIME = Regex("^\\d:\\d{2}$")

    /**
     * @return a validation error message, or null when [spec] is executable. Called from the
     * tool parser so a bad `timeOfDay` becomes an audit-gate Deny the model can self-correct
     * from (P2), instead of a vague runtime failure.
     */
    fun validate(spec: TimeCalcSpec): String? = when {
        spec.amount > MAX_ABS_AMOUNT || spec.amount < -MAX_ABS_AMOUNT ->
            "amount must be between -$MAX_ABS_AMOUNT and $MAX_ABS_AMOUNT (got ${spec.amount})"
        spec.timeOfDay != null && parseTime(spec.timeOfDay) == null ->
            "timeOfDay must be 24-hour 'HH:mm' (got '${spec.timeOfDay}')"
        else -> null
    }

    /** Resolve [spec] against [nowEpochMs]; fails only if [validate] was skipped by the caller. */
    fun resolve(spec: TimeCalcSpec, nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Result<TimeShift> {
        validate(spec)?.let { return Result.failure(IllegalArgumentException(it)) }

        val baseMs = spec.baseEpochMs ?: nowEpochMs
        var zdt = Instant.ofEpochMilli(baseMs).atZone(zone)

        if (spec.amount != 0) {
            zdt = zdt.plus(spec.amount.toLong(), spec.unit.chronoUnit())
        }
        // Strictly forward: `next(FRIDAY)` never returns the current day, which is what
        // "下周五" means. "This Friday" is expressible as amount/unit alone.
        spec.weekday?.let { target ->
            zdt = zdt.with(TemporalAdjusters.next(target.dayOfWeek()))
        }
        spec.timeOfDay?.let { raw ->
            val time = parseTime(raw.trim()) ?: return Result.failure(
                IllegalArgumentException("timeOfDay must be 24-hour 'HH:mm' (got '$raw')")
            )
            zdt = zdt.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
        }

        val targetMs = zdt.toInstant().toEpochMilli()
        return Result.success(
            TimeShift(
                base = TimePoint.of(baseMs, zone),
                target = TimePoint.of(targetMs, zone),
                deltaMs = targetMs - baseMs
            )
        )
    }

    /**
     * Single source of truth for the `timeOfDay` grammar, shared by [validate] and [resolve] —
     * two independent checks here would drift apart the first time the grammar changes.
     */
    private fun parseTime(raw: String): LocalTime? {
        val trimmed = raw.trim()
        val candidate = if (SHORT_TIME.matches(trimmed)) "0$trimmed" else trimmed
        return try {
            LocalTime.parse(candidate)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun TimeUnit.chronoUnit(): ChronoUnit = when (this) {
        TimeUnit.SECOND -> ChronoUnit.SECONDS
        TimeUnit.MINUTE -> ChronoUnit.MINUTES
        TimeUnit.HOUR -> ChronoUnit.HOURS
        TimeUnit.DAY -> ChronoUnit.DAYS
        TimeUnit.WEEK -> ChronoUnit.WEEKS
        TimeUnit.MONTH -> ChronoUnit.MONTHS
        TimeUnit.YEAR -> ChronoUnit.YEARS
    }

    private fun Weekday.dayOfWeek(): DayOfWeek = when (this) {
        Weekday.MONDAY -> DayOfWeek.MONDAY
        Weekday.TUESDAY -> DayOfWeek.TUESDAY
        Weekday.WEDNESDAY -> DayOfWeek.WEDNESDAY
        Weekday.THURSDAY -> DayOfWeek.THURSDAY
        Weekday.FRIDAY -> DayOfWeek.FRIDAY
        Weekday.SATURDAY -> DayOfWeek.SATURDAY
        Weekday.SUNDAY -> DayOfWeek.SUNDAY
    }
}
