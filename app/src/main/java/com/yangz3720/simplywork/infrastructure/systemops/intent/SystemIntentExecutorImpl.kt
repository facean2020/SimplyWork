package com.yangz3720.simplywork.infrastructure.systemops.intent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.systemops.SystemCapabilityProbe
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarWriteMode
import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult.PermissionRequired
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult.SecurityBlocked
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult.Success
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult.TargetNotFound
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

private const val TAG = "SystemOps"

/** P2 regression anchor: the exact reason string for background clipboard read (C3). */
internal const val BACKGROUND_CLIPBOARD_READ_MSG = "Background clipboard read restricted"
internal const val BACKGROUND_CLIPBOARD_WRITE_MSG = "Background clipboard write restricted"

private const val LAUNCH_FALLBACK_CHANNEL_ID = "systemops_launch_fallback"

/**
 * Android implementation of the Intent main path.
 *
 * [isAppForeground] is injectable so instrumented tests can pin the focus state; the
 * production default samples [ActivityManager]-based process importance (see [defaultForegroundCheck]).
 */
class SystemIntentExecutorImpl(
    private val context: Context,
    private val probe: SystemCapabilityProbe,
    private val isAppForeground: () -> Boolean = { defaultForegroundCheck(context) }
) : SystemIntentExecutor {

    private val packageManager get() = context.packageManager

    override suspend fun launchDeepLink(
        uri: String,
        launchContext: LaunchContext
    ): SystemOperationResult {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) == null) return TargetNotFound(uri)
        return dispatchLaunch(intent, launchContext, fallbackText = uri)
    }

    override suspend fun openApp(packageName: String): SystemOperationResult {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return TargetNotFound(packageName)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return dispatchLaunch(launchIntent, LaunchContext.AUTO, fallbackText = packageName)
    }

    override suspend fun readClipboard(): SystemOperationResult {
        if (!isAppForeground()) return SecurityBlocked(BACKGROUND_CLIPBOARD_READ_MSG)
        return try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // NB: when our own foreground gate passes but the system still withholds the
            // clipboard, it simply reads back null — reported as isEmpty=true (edge case,
            // see C3 notes in appfunction-design.md §2).
            val text = clipboardManager.primaryClip
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
            Success(
                buildJsonObject {
                    put("text", text?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("isEmpty", text.isNullOrEmpty())
                }
            )
        } catch (e: Exception) {
            throw InfrastructureException.SystemOperation.ClipboardOperationFailed(
                e.message ?: "unknown error",
                e
            )
        }
    }

    override suspend fun writeClipboard(label: String, text: String): SystemOperationResult {
        if (!isAppForeground()) return SecurityBlocked(BACKGROUND_CLIPBOARD_WRITE_MSG)
        return try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
            Success()
        } catch (e: Exception) {
            throw InfrastructureException.SystemOperation.ClipboardOperationFailed(
                e.message ?: "unknown error",
                e
            )
        }
    }

    override suspend fun insertCalendarEvent(event: CalendarInsertSpec): SystemOperationResult =
        when (event.mode) {
            CalendarWriteMode.EDITOR_INTENT -> insertViaEditor(event)
            CalendarWriteMode.DIRECT_WRITE -> insertDirect(event)
        }

    override suspend fun postNotification(spec: NotificationPostSpec): SystemOperationResult {
        probe.snapshot().notificationsPermissionGranted.takeIf { it }?.let { Unit }
            ?: return PermissionRequired(
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                rationale = "Posting a notification requires POST_NOTIFICATIONS; grant it and retry."
            )
        return try {
            ensureNotificationChannel(spec.channelId)
            val id = (spec.channelId + spec.title + spec.message).hashCode()
            val notification = NotificationCompat.Builder(context, spec.channelId)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(spec.title)
                .setContentText(spec.message)
                .setPriority(spec.priority)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
            Success(buildJsonObject { put("notificationId", id) })
        } catch (e: SecurityException) {
            // Permission was revoked between the probe and notify() — report, never swallow (P2).
            PermissionRequired(
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                rationale = "System denied notification post: ${e.message}"
            )
        } catch (e: Exception) {
            throw InfrastructureException.SystemOperation.NotificationPostFailed(
                e.message ?: "unknown error",
                e
            )
        }
    }

    override suspend fun shareText(title: String, content: String): SystemOperationResult {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, content)
        val chooser = Intent.createChooser(send, title.ifBlank { null })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return dispatchLaunch(chooser, LaunchContext.AUTO, fallbackText = content)
    }

    // ----- launch plumbing (C4) -----

    private fun dispatchLaunch(
        intent: Intent,
        launchContext: LaunchContext,
        fallbackText: String
    ): SystemOperationResult {
        val decision = LaunchPolicy.decide(
            launchContext = launchContext,
            isForeground = isAppForeground(),
            canDrawOverlays = probe.snapshot().canLaunchActivityFromBackground
        )
        AgentLogger.d(TAG) { "launch decision=$decision intent=${intent.action}" }
        return when (decision) {
            is LaunchDecision.StartActivityNow -> startActivity(intent)
            is LaunchDecision.NotifyFallback ->
                postLaunchNotification(fallbackTitle = appLabel(), fallbackText = fallbackText, launchIntent = intent)
            is LaunchDecision.Blocked -> SecurityBlocked(decision.reason)
        }
    }

    private fun startActivity(intent: Intent): SystemOperationResult = try {
        context.startActivity(intent)
        Success(
            buildJsonObject {
                put("launched", true)
                put("component", intent.component?.toString() ?: "resolved-by-system")
            }
        )
    } catch (e: android.content.ActivityNotFoundException) {
        TargetNotFound(intent.data?.toString() ?: intent.toString())
    } catch (e: SecurityException) {
        throw InfrastructureException.SystemOperation.IntentLaunchFailed("system denied activity start", e)
    }

    /**
     * C4-compliant degradation: hand the launch intent to the user via a tappable
     * notification. The user's tap is a user-initiated launch, so no BAL exemption is needed.
     */
    private fun postLaunchNotification(
        fallbackTitle: String,
        fallbackText: String,
        launchIntent: Intent
    ): SystemOperationResult {
        if (!probe.snapshot().notificationsPermissionGranted) {
            return PermissionRequired(
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                rationale = "Background launch is restricted (C4); the notification fallback needs POST_NOTIFICATIONS. Grant it and retry."
            )
        }
        return try {
            ensureNotificationChannel(LAUNCH_FALLBACK_CHANNEL_ID)
            val requestCode = launchIntent.hashCode()
            val pendingIntent = PendingIntent.getActivity(
                context, requestCode, launchIntent, PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, LAUNCH_FALLBACK_CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(fallbackTitle)
                .setContentText(fallbackText)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(requestCode, notification)
            Success(
                buildJsonObject {
                    put("launched", false)
                    put("deliveredVia", "notification")
                    put("notificationId", requestCode)
                }
            )
        } catch (e: SecurityException) {
            PermissionRequired(
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                rationale = "System denied notification post: ${e.message}"
            )
        } catch (e: Exception) {
            throw InfrastructureException.SystemOperation.NotificationPostFailed(
                e.message ?: "unknown error",
                e
            )
        }
    }

    private fun ensureNotificationChannel(channelId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) != null) return
        manager.createNotificationChannel(
            // Placeholder name/description until the UI layer owns channel curation.
            NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    // ----- calendar -----

    private fun insertViaEditor(event: CalendarInsertSpec): SystemOperationResult {
        val (beginTime, endTime) = snappedTimes(event)
        val intent = Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endTime)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, event.isAllDay)
            .putExtra(android.provider.CalendarContract.Events.TITLE, event.title)
            .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, event.description)
            .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, event.location)
        if (intent.resolveActivity(packageManager) == null) return TargetNotFound("calendar editor")
        return startActivity(intent)
    }

    private fun insertDirect(event: CalendarInsertSpec): SystemOperationResult {
        val required = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            return PermissionRequired(
                permissions = missing,
                rationale = "Direct calendar write needs calendar permissions; alternatively retry in EDITOR_INTENT mode which needs none."
            )
        }
        return try {
            val calendarId = firstWritableCalendarId()
                ?: return SystemOperationResult.Failed("No writable local calendar found on this device")
            val (beginTime, endTime) = snappedTimes(event)
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.CALENDAR_ID, calendarId)
                put(android.provider.CalendarContract.Events.TITLE, event.title)
                event.description?.let { put(android.provider.CalendarContract.Events.DESCRIPTION, it) }
                event.location?.let { put(android.provider.CalendarContract.Events.EVENT_LOCATION, it) }
                put(android.provider.CalendarContract.Events.DTSTART, beginTime)
                put(android.provider.CalendarContract.Events.DTEND, endTime)
                put(android.provider.CalendarContract.Events.ALL_DAY, if (event.isAllDay) 1 else 0)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(android.provider.CalendarContract.Events.EVENT_END_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(
                android.provider.CalendarContract.Events.CONTENT_URI, values
            ) ?: return SystemOperationResult.Failed("Calendar provider rejected the insert (null Uri)")
            Success(buildJsonObject { put("eventUri", uri.toString()) })
        } catch (e: Exception) {
            throw InfrastructureException.SystemOperation.CalendarWriteFailed(
                e.message ?: "unknown error",
                e
            )
        }
    }

    private fun firstWritableCalendarId(): Long? {
        context.contentResolver.query(
            android.provider.CalendarContract.Calendars.CONTENT_URI,
            arrayOf(android.provider.CalendarContract.Calendars._ID),
            "${android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null
        )?.use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        return null
    }

    /** All-day events snap to local midnights (end exclusive = next day's midnight), matching provider semantics. */
    private fun snappedTimes(event: CalendarInsertSpec): Pair<Long, Long> {
        if (!event.isAllDay) return event.startTimeEpochMs to event.endTimeEpochMs
        val zone = ZoneId.systemDefault()
        val begin = Instant.ofEpochMilli(event.startTimeEpochMs)
            .atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = Instant.ofEpochMilli(event.endTimeEpochMs)
            .atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return begin to end
    }

    // ----- helpers -----

    private fun appLabel(): String = context.applicationInfo.loadLabel(packageManager).toString()
}

/** Production foreground signal: our process importance (≈ focused window). Injectable for tests. */
private fun defaultForegroundCheck(context: Context): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val processName = context.applicationInfo.processName
    return activityManager.runningAppProcesses?.any {
        it.processName == processName &&
            it.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } == true
}
