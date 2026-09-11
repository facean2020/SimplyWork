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

/** Background launch strategy (C4): encodes the foreground / overlay / notification launching contexts. */
@Serializable
enum class LaunchContext { AUTO, FOREGROUND_NOW, FROM_OVERLAY_SERVICE, VIA_NOTIFICATION_FALLBACK }

/** Calendar write mode: default is the editor Intent (user confirms + no permission); direct write needs runtime permissions + audit. */
@Serializable
enum class CalendarWriteMode { EDITOR_INTENT, DIRECT_WRITE }
