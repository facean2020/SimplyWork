package com.yangz3720.simplywork.infrastructure.systemops.intent

import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardWriteSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DeepLinkSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ShareTextSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult

/**
 * Intent main path executor (v1.1: the ONLY execution path).
 *
 * Contract highlights:
 * - C3: background clipboard read/write returns [SystemOperationResult.SecurityBlocked],
 *   NEVER a silent empty string — this distinction decides whether the agent can self-heal (P2).
 * - C4: activity launches from the background go through [LaunchPolicy]; when launch is not
 *   possible the operation degrades to a tappable notification, reported in the result
 *   output (`deliveredVia = notification`), never silently.
 * - C5: notification posting without POST_NOTIFICATIONS returns [SystemOperationResult.PermissionRequired]
 *   (P4: request UX is owned by the UI layer).
 * - All methods are suspend; cross-process calls are subject to the router-level timeout (P5).
 */
interface SystemIntentExecutor {

    /** Launch a deep link. See [LaunchContext] for the background launch strategy (C4). */
    suspend fun launchDeepLink(
        uri: String,
        launchContext: LaunchContext = LaunchContext.AUTO
    ): SystemOperationResult

    /** Open an app by package name; no launchable activity → [SystemOperationResult.TargetNotFound]. */
    suspend fun openApp(packageName: String): SystemOperationResult

    /**
     * Read the clipboard (C3). Without foreground focus returns
     * [SystemOperationResult.SecurityBlocked]("Background clipboard read restricted") —
     * never an empty string masquerading as "clipboard is empty".
     */
    suspend fun readClipboard(): SystemOperationResult

    /** Write the clipboard; Android 10+ applies the same foreground-focus restriction as reads. */
    suspend fun writeClipboard(label: String, text: String): SystemOperationResult

    /**
     * Insert a calendar event. EDITOR_INTENT (default) opens the calendar editor for the
     * user to confirm — no permission needed. DIRECT_WRITE requires calendar runtime
     * permissions, missing ones → [SystemOperationResult.PermissionRequired] (P4).
     */
    suspend fun insertCalendarEvent(event: CalendarInsertSpec): SystemOperationResult

    /** Post a notification; missing permission → [SystemOperationResult.PermissionRequired] (C5). */
    suspend fun postNotification(spec: NotificationPostSpec): SystemOperationResult

    /** Open the system share sheet for text content (follows the same C4 launch policy). */
    suspend fun shareText(title: String, content: String): SystemOperationResult
}
