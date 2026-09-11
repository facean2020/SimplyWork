package com.yangz3720.simplywork.infrastructure.systemops.model

import kotlinx.serialization.Serializable

/**
 * Danger level: the single source for tool-core audit policy mapping (P3).
 * v1.1: HIGH_RISK was removed together with the deferred Automation path
 * (it will be reintroduced with shell / global-gesture capabilities).
 */
enum class DangerLevel {
    /** Side-effect free reads: foreground clipboard read, query-like operations. */
    SAFE,

    /** User-perceivable low-risk writes: post notification, write clipboard. */
    USER_VISIBLE,

    /** Reaches user data or third-party apps: calendar direct write, deep link / app launch. */
    USER_CONFIRMING
}

/**
 * Base type of operation specs: tool-core derives [ToolDefinition][com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition]
 * from specs, and audit reads [dangerLevel] from them.
 */
@Serializable
sealed interface OperationSpec {
    val dangerLevel: DangerLevel
}

@Serializable
data class DeepLinkSpec(
    val uri: String,
    val launchContext: LaunchContext = LaunchContext.AUTO
) : OperationSpec {
    override val dangerLevel = DangerLevel.USER_CONFIRMING
}

@Serializable
data class AppOpenSpec(val packageName: String) : OperationSpec {
    override val dangerLevel = DangerLevel.USER_CONFIRMING
}

@Serializable
data object ClipboardReadSpec : OperationSpec {
    override val dangerLevel = DangerLevel.SAFE
}

@Serializable
data class ClipboardWriteSpec(val label: String, val text: String) : OperationSpec {
    override val dangerLevel = DangerLevel.USER_VISIBLE
}

@Serializable
data class CalendarInsertSpec(
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val isAllDay: Boolean = false,
    val mode: CalendarWriteMode = CalendarWriteMode.EDITOR_INTENT
) : OperationSpec {
    // EDITOR_INTENT hands confirmation to the user = natural HITL, so USER_VISIBLE;
    // only direct provider write is elevated.
    override val dangerLevel: DangerLevel
        get() = if (mode == CalendarWriteMode.DIRECT_WRITE) DangerLevel.USER_CONFIRMING else DangerLevel.USER_VISIBLE
}

@Serializable
data class NotificationPostSpec(
    val channelId: String,
    val title: String,
    val message: String,
    val priority: Int = 0
) : OperationSpec {
    override val dangerLevel = DangerLevel.USER_VISIBLE
}

@Serializable
data class ShareTextSpec(val title: String, val content: String) : OperationSpec {
    override val dangerLevel = DangerLevel.USER_VISIBLE
}

// ----- read-side operations (v1.2) -----

/**
 * Device clock readout. The model has no wall clock of its own: `sys_calendar_insert`
 * takes epoch-ms arguments, so without this tool every scheduled event is a guess.
 */
@Serializable
data object TimeReadSpec : OperationSpec {
    override val dangerLevel = DangerLevel.SAFE
}

/** Calendar field to shift by in [TimeCalcSpec]. Month/year shifts clamp the day-of-month. */
@Serializable
enum class TimeUnit { SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

@Serializable
enum class Weekday { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

/**
 * Resolve a relative date/time ("下周五下午3点") against the device clock.
 *
 * Order of operations is fixed and documented on the tool: shift by [amount]/[unit] →
 * advance strictly past the current day to [weekday] → overwrite the clock with [timeOfDay].
 */
@Serializable
data class TimeCalcSpec(
    val amount: Int = 0,
    val unit: TimeUnit = TimeUnit.DAY,
    val weekday: Weekday? = null,
    val timeOfDay: String? = null,
    val baseEpochMs: Long? = null
) : OperationSpec {
    override val dangerLevel = DangerLevel.SAFE
}

/** Battery / network / storage / memory / build readout. */
@Serializable
data object DeviceReadSpec : OperationSpec {
    override val dangerLevel = DangerLevel.SAFE
}

/**
 * Installed-app catalog — feeds `sys_app_open`, which otherwise requires the model to
 * guess a package name. Package lists are device metadata, not user content → SAFE.
 */
@Serializable
data class AppListSpec(
    val query: String? = null,
    val limit: Int = 40,
    val launchableOnly: Boolean = true
) : OperationSpec {
    override val dangerLevel = DangerLevel.SAFE
}

/**
 * Read events out of the calendar provider. Unlike [CalendarInsertSpec] in EDITOR_INTENT
 * (which delegates confirmation to the calendar app and needs no permission), this reads
 * user data directly → USER_CONFIRMING + READ_CALENDAR.
 */
@Serializable
data class CalendarQuerySpec(
    val startTimeEpochMs: Long? = null,
    val endTimeEpochMs: Long? = null,
    val query: String? = null,
    val limit: Int = 20
) : OperationSpec {
    override val dangerLevel = DangerLevel.USER_CONFIRMING
}

/** APPROXIMATE = network/passive (coarse); PRECISE additionally asks the GPS provider. */
@Serializable
enum class LocationAccuracy { APPROXIMATE, PRECISE }

/**
 * Device location. Reaches user data and needs a runtime permission → USER_CONFIRMING,
 * so every call passes the HITL gate.
 */
@Serializable
data class LocationReadSpec(
    val accuracy: LocationAccuracy = LocationAccuracy.APPROXIMATE,
    val maxAgeSeconds: Int = 120,
    val includeAddress: Boolean = false
) : OperationSpec {
    override val dangerLevel = DangerLevel.USER_CONFIRMING
}

/** Background launch strategy (C4): encodes the foreground / overlay / notification launching contexts. */
@Serializable
enum class LaunchContext { AUTO, FOREGROUND_NOW, FROM_OVERLAY_SERVICE, VIA_NOTIFICATION_FALLBACK }

/** Calendar write mode: default is the editor Intent (user confirms + no permission); direct write needs runtime permissions + audit. */
@Serializable
enum class CalendarWriteMode { EDITOR_INTENT, DIRECT_WRITE }
