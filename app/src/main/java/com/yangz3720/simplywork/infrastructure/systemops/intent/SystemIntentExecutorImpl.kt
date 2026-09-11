package com.yangz3720.simplywork.infrastructure.systemops.intent

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.systemops.SystemCapabilityProbe
import com.yangz3720.simplywork.infrastructure.systemops.SystemPermissionRequester
import com.yangz3720.simplywork.infrastructure.systemops.model.AppListSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarQuerySpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarWriteMode
import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import com.yangz3720.simplywork.infrastructure.systemops.model.LocationAccuracy
import com.yangz3720.simplywork.infrastructure.systemops.model.LocationReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult.PermissionRequired
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult.SecurityBlocked
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult.Success
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult.TargetNotFound
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeCalcSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeCalculation
import com.yangz3720.simplywork.infrastructure.systemops.model.TimePoint
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.resume
import kotlin.time.TimeSource

private const val TAG = "SystemOps"

/** P2 regression anchor: the exact reason string for background clipboard read (C3). */
internal const val BACKGROUND_CLIPBOARD_READ_MSG = "Background clipboard read restricted"
internal const val BACKGROUND_CLIPBOARD_WRITE_MSG = "Background clipboard write restricted"

private const val LAUNCH_FALLBACK_CHANNEL_ID = "systemops_launch_fallback"

/** `sys_calendar_query` default horizon when the model gives no window. */
private const val DEFAULT_CALENDAR_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Total wall-clock budget for one `sys_location_read`.
 *
 * The router wraps every operation in a *single* `withTimeout(DEFAULT_OP_TIMEOUT_MS)`, so the
 * permission prompt, the provider waits and the optional geocode all share one deadline. They
 * must therefore be derived from the time already spent, not summed as independent constants:
 * fixed slices that add up near the ceiling discard a fully successful answer as `Timeout`,
 * which in the field is indistinguishable from a random flake.
 */
private const val LOCATION_CALL_BUDGET_MS = 9_000L

/** Cap on waiting for a human to read and answer a permission dialog. */
private const val PERMISSION_WAIT_BUDGET_MS = 5_000L

/** Slice held back for the optional reverse geocode at the tail of a location call. */
private const val GEOCODE_BUDGET_MS = 2_000L

/** Network/passive providers answer fast; each gets this per attempt, GPS gets what is left. */
private const val QUICK_PROVIDER_SLICE_MS = 1_200L

/** Below this much remaining budget, stop chasing a fresh fix and return the cached one. */
private const val MIN_FRESH_ATTEMPT_MS = 400L

/** CalendarContract "deleted" column (see usage in queryCalendarEvents for why it is a literal). */
private const val CALENDAR_DELETED_COLUMN = "deleted"

/**
 * Android implementation of the Intent main path.
 *
 * [isAppForeground] is injectable so instrumented tests can pin the focus state; the
 * production default samples [ActivityManager]-based process importance (see [defaultForegroundCheck]).
 *
 * [permissionRequester] is the P4 grant seam used by the v1.2 read tools; the default never
 * prompts, so unit tests and any non-UI host keep the pre-v1.2 "report PermissionRequired" behaviour.
 */
class SystemIntentExecutorImpl(
    private val context: Context,
    private val probe: SystemCapabilityProbe,
    private val isAppForeground: () -> Boolean = { defaultForegroundCheck(context) },
    private val permissionRequester: SystemPermissionRequester = SystemPermissionRequester.None
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

    // ----- read-side operations (v1.2) -----

    override suspend fun readCurrentTime(): SystemOperationResult {
        val now = TimePoint.of(System.currentTimeMillis())
        return Success(timePointJson(now))
    }

    override suspend fun calculateTime(spec: TimeCalcSpec): SystemOperationResult {
        val shift = TimeCalculation.resolve(spec, System.currentTimeMillis())
            .getOrElse { return SystemOperationResult.Failed(it.message ?: "invalid time arguments") }
        val base = shift.base
        return Success(
            buildJsonObject {
                put("epochMs", shift.target.epochMs)
                put("epochSeconds", shift.target.epochMs / 1000)
                put("localIso", shift.target.localIso)
                put("localDate", shift.target.localDate)
                put("localTime", shift.target.localTime)
                put("weekday", shift.target.weekday)
                put("timeZoneId", shift.target.timeZoneId)
                put("utcOffsetMinutes", shift.target.utcOffsetMinutes)
                put("deltaMs", shift.deltaMs)
                put("baseLocalIso", base.localIso)
                put("baseWeekday", base.weekday)
            }
        )
    }

    override suspend fun readDeviceInfo(): SystemOperationResult = withContext(Dispatchers.IO) {
        val battery = stickyBatteryIntent()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val powerSave = powerSaveMode()
        val storage = storageSnapshot()

        Success(
            buildJsonObject {
                // Unknown battery state is reported as null, never as `false`: asserting
                // "not charging" on a device whose sticky intent we could not read is exactly
                // the silent-false P2 forbids.
                put("batteryAvailable", battery != null)
                put("batteryPercent", percent?.let { JsonPrimitive(it) } ?: JsonNull)
                put("batteryCharging", plugged?.let { JsonPrimitive(it != 0) } ?: JsonNull)
                put("batteryStatus", batteryStatusName(status))
                put("powerSaveMode", powerSave?.let { JsonPrimitive(it) } ?: JsonNull)
                put("network", networkSnapshot())
                put("storage", storage)
                put("memory", memorySnapshot())
                put("uptimeMs", SystemClock.elapsedRealtime())
                put("device", buildJsonObject {
                    put("manufacturer", Build.MANUFACTURER)
                    put("brand", Build.BRAND)
                    put("model", Build.MODEL)
                    put("androidRelease", Build.VERSION.RELEASE)
                    put("sdkInt", Build.VERSION.SDK_INT)
                })
                put("locale", Locale.getDefault().toLanguageTag())
                put("timeZone", ZoneId.systemDefault().id)
            }
        )
    }

    override suspend fun listInstalledApps(spec: AppListSpec): SystemOperationResult =
        withContext(Dispatchers.IO) {
            val needle = spec.query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val inventory = try {
                if (spec.launchableOnly) {
                    packageManager
                        .queryIntentActivities(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                            PackageManager.ResolveInfoFlags.of(0L)
                        )
                        // distinctBy keeps the FIRST launcher entry per package. Label inflation
                        // is per-package, so de-duplicating first avoids paying for activity
                        // aliases — and unlike associate{}, the primary launcher label survives.
                        .distinctBy { it.activityInfo.packageName }
                        .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
                } else {
                    packageManager
                        .getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                        .map { info ->
                            info.packageName to (info.applicationInfo
                                ?.loadLabel(packageManager)?.toString() ?: info.packageName)
                        }
                }
            } catch (e: Exception) {
                throw InfrastructureException.SystemOperation.AppInventoryFailed(
                    e.message ?: "unknown error", e
                )
            }
            val matching = inventory
                .filter { needle == null || it.first.lowercase().contains(needle) || it.second.lowercase().contains(needle) }
                .sortedBy { it.second.lowercase() }
            val page = matching.take(spec.limit)
            Success(
                buildJsonObject {
                    put("totalMatching", matching.size)
                    put("returned", page.size)
                    put("truncated", matching.size > page.size)
                    putJsonArray("apps") {
                        page.forEach { (pkg, label) ->
                            add(buildJsonObject {
                                put("packageName", pkg)
                                put("label", label)
                            })
                        }
                    }
                }
            )
        }

    override suspend fun queryCalendarEvents(spec: CalendarQuerySpec): SystemOperationResult {
        val rationale = "Reading your calendar needs READ_CALENDAR. If you deny it, the agent can " +
            "still add events in EDITOR_INTENT mode, which asks you inside the calendar app and needs no permission."
        ensurePermissions(listOf(Manifest.permission.READ_CALENDAR), rationale)?.let { return it }

        return withContext(Dispatchers.IO) {
            val start = spec.startTimeEpochMs ?: System.currentTimeMillis()
            // Guard the defaulting arithmetic too: a start near Long.MAX_VALUE would wrap the
            // addition into a negative end and return an inverted, silently-empty window.
            val end = spec.endTimeEpochMs ?: start.saturatingPlus(DEFAULT_CALENDAR_WINDOW_MS)
            val needle = spec.query?.trim()?.takeIf { it.isNotEmpty() }
            val events = ArrayList<kotlinx.serialization.json.JsonObject>(spec.limit)
            var sawMore = false
            try {
                // Instances (not Events) so recurring appointments expand into real occurrences.
                val uri = android.provider.CalendarContract.Instances.CONTENT_URI
                    .buildUpon()
                    .appendPath(start.toString())
                    .appendPath(end.toString())
                    .build()
                val selection = buildString {
                    // "deleted" is CalendarContract.SyncColumns.DELETED. That interface is
                    // protected in CalendarContract and Kotlin does not resolve the constant
                    // through the Instances/Events aliases, so the column name is spelled out.
                    append("$CALENDAR_DELETED_COLUMN = 0")
                    if (needle != null) {
                        append(" AND ${android.provider.CalendarContract.Instances.TITLE} LIKE ?")
                    }
                }
                val args = if (needle != null) arrayOf("%$needle%") else emptyArray()
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.CalendarContract.Instances._ID,
                        android.provider.CalendarContract.Instances.CALENDAR_ID,
                        android.provider.CalendarContract.Instances.TITLE,
                        android.provider.CalendarContract.Instances.ALL_DAY,
                        android.provider.CalendarContract.Instances.BEGIN,
                        android.provider.CalendarContract.Instances.END,
                        android.provider.CalendarContract.Instances.EVENT_LOCATION,
                        android.provider.CalendarContract.Instances.DESCRIPTION
                    ),
                    selection,
                    args,
                    // No `LIMIT` here: CalendarProvider2's strict sort-order validation rejects
                    // anything trailing ASC/DESC, and capping in the loop is what lets us report
                    // `truncated` honestly.
                    "${android.provider.CalendarContract.Instances.BEGIN} ASC"
                )?.use { cursor ->
                    val titleIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.BEGIN)
                    val endIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.END)
                    val allDayIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.ALL_DAY)
                    val locationIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.EVENT_LOCATION)
                    val calendarIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.CALENDAR_ID)
                    while (cursor.moveToNext()) {
                        if (events.size >= spec.limit) {
                            sawMore = true
                            break
                        }
                        val begin = cursor.getLong(beginIdx)
                        val eventEnd = cursor.getLong(endIdx)
                        val beginPoint = TimePoint.of(begin)
                        events.add(buildJsonObject {
                            put("title", cursor.getString(titleIdx) ?: "(untitled)")
                            put("startTimeEpochMs", begin)
                            put("endTimeEpochMs", eventEnd)
                            // All-day rows are stored against UTC midnight, so the local ISO
                            // rendering can land on the neighbouring day; epochMs is authoritative.
                            put("startLocalIso", beginPoint.localIso)
                            put("endLocalIso", TimePoint.of(eventEnd).localIso)
                            put("weekday", beginPoint.weekday)
                            put("allDay", cursor.getInt(allDayIdx) == 1)
                            cursor.getString(locationIdx)?.takeIf { it.isNotBlank() }?.let { put("location", it) }
                            put("calendarId", cursor.getLong(calendarIdx))
                        })
                    }
                }
            } catch (e: SecurityException) {
                // Revoked between the check and the query — report, never swallow (P2).
                return@withContext PermissionRequired(
                    permissions = listOf(Manifest.permission.READ_CALENDAR),
                    rationale = "Calendar provider denied the query: ${e.message}"
                )
            } catch (e: Exception) {
                throw InfrastructureException.SystemOperation.CalendarQueryFailed(
                    e.message ?: "unknown error", e
                )
            }
            Success(
                buildJsonObject {
                    put("windowStartIso", TimePoint.of(start).localIso)
                    put("windowEndIso", TimePoint.of(end).localIso)
                    put("count", events.size)
                    // Without this the model reports a partial schedule as complete: the default
                    // limit of 20 is well under what a busy month can hold (P2).
                    put("truncated", sawMore)
                    putJsonArray("events") { events.forEach { add(it) } }
                }
            )
        }
    }

    override suspend fun readLocation(spec: LocationReadSpec): SystemOperationResult {
        val started = TimeSource.Monotonic.markNow()
        val required = if (spec.accuracy == LocationAccuracy.PRECISE) {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val rationale = "Locating this device needs ${required.joinToString()}."
        // "any" not "all": Android 12+ lets the user pick approximate-only, which still yields a
        // legitimate coarse fix. Demanding FINE here would fail a call that has usable data, and
        // the output reports precisionDegraded when precision was not honored.
        ensureAnyPermission(required, rationale, remainingOf(LOCATION_CALL_BUDGET_MS, started))
            ?.let { return it }
        return withContext(Dispatchers.IO) { locate(spec, remainingOf(LOCATION_CALL_BUDGET_MS, started)) }
    }

    /** Milliseconds still available out of [budget], given what [started] has already consumed. */
    private fun remainingOf(budget: Long, started: kotlin.time.TimeMark): Long =
        (budget - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)

    private suspend fun locate(spec: LocationReadSpec, budgetMs: Long): SystemOperationResult {
        val entered = TimeSource.Monotonic.markNow()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return SystemOperationResult.Failed("This device has no location service")
        val enabled = try {
            manager.allProviders.filter { manager.isProviderEnabled(it) }
        } catch (e: Exception) {
            throw InfrastructureException.SystemOperation.LocationReadFailed(
                e.message ?: "unknown error", e
            )
        }
        if (enabled.isEmpty()) {
            return SecurityBlocked(
                "Device location is switched off — no GPS or network provider is enabled. " +
                    "Ask the user to turn on Location in system settings, then retry. Requesting a " +
                    "permission will not help: this is the device-wide Location toggle."
            )
        }
        // Order the enabled providers by how well they match the requested precision.
        val preferred = (
            if (spec.accuracy == LocationAccuracy.PRECISE) {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            } else {
                listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            }
            ).filter(enabled::contains)
        if (preferred.isEmpty()) {
            return SecurityBlocked(
                "No enabled location provider can satisfy ${spec.accuracy}; enabled providers: $enabled"
            )
        }

        // Location.getTime() is wall-clock epoch ms, so staleness must be measured against
        // System.currentTimeMillis() — elapsedRealtime() (since boot) would be nonsense here.
        val maxAgeMs = spec.maxAgeSeconds * 1000L
        val cached = freshestCachedFix(manager, preferred)
        val geocodeReserve = if (spec.includeAddress) GEOCODE_BUDGET_MS else 0L
        var remaining = budgetMs - geocodeReserve
        var fresh: Location? = null
        var subscribeError: String? = null

        if (cached == null || cached.ageMs > maxAgeMs) {
            // Walk the ordered providers instead of only the first: asking GPS alone indoors
            // burns the whole budget for nothing when the already-enabled network provider
            // would have answered in a few hundred milliseconds.
            val queue = ArrayDeque(preferred)
            while (fresh == null && queue.isNotEmpty() && remaining >= MIN_FRESH_ATTEMPT_MS) {
                val provider = queue.removeFirst()
                val slice = if (provider == LocationManager.GPS_PROVIDER || queue.isEmpty()) remaining
                else minOf(QUICK_PROVIDER_SLICE_MS, remaining)
                val attempt = awaitFreshFix(manager, provider, slice)
                fresh = attempt.location
                subscribeError = attempt.cause ?: subscribeError
                remaining -= slice
            }
        }

        // Newest wins, not "whatever arrived last": a fresh delivery from PASSIVE can be older
        // than the cached fix it just replayed.
        val fix = listOfNotNull(fresh, cached?.location).maxByOrNull { it.time }
            ?: return SystemOperationResult.Failed(
                "No position fix available (${subscribeError ?: "no cached fix and none produced within ${budgetMs}ms"}). " +
                    "A cold GPS fix indoors can take tens of seconds; retry with accuracy=APPROXIMATE, " +
                    "raise maxAgeSeconds to accept a cached fix, or ask the user for their city."
            )

        val fineGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        // Resolved before the JSON builder: geocoding is a suspend call and may hit the network.
        // It gets whatever of the reserved slice the provider walk did not spend.
        val address = if (spec.includeAddress) {
            geocode(fix, minOf(GEOCODE_BUDGET_MS, budgetMs - entered.elapsedNow().inWholeMilliseconds))
        } else {
            null
        }
        return Success(
            buildJsonObject {
                put("latitude", fix.latitude)
                put("longitude", fix.longitude)
                put("accuracyMeters", fix.accuracy.toDouble())
                put("provider", fix.provider ?: "unknown")
                put("timestampEpochMs", fix.time)
                put("fixAgeSeconds", ((System.currentTimeMillis() - fix.time) / 1000).coerceAtLeast(0L))
                put("isFromCache", fresh == null)
                // Android 12+ lets the user grant approximate-only; report it instead of
                // letting the model assume the precision it asked for.
                put("fineLocationGranted", fineGranted)
                if (spec.accuracy == LocationAccuracy.PRECISE && !fineGranted) {
                    put("precisionDegraded", "user granted approximate location only")
                }
                if (fix.hasAltitude()) put("altitudeMeters", fix.altitude)
                if (fix.hasBearing()) put("bearingDegrees", fix.bearing.toDouble())
                if (fix.hasSpeed()) put("speedMetersPerSecond", fix.speed.toDouble())
                address?.let { geo ->
                    geo.line?.let { put("address", it) }
                    geo.country?.let { put("country", it) }
                    geo.unavailable?.let { put("addressUnavailable", it) }
                }
            }
        )
    }

    /** Either a fix, or the reason the provider could not be asked at all. */
    private data class FixAttempt(val location: Location?, val cause: String?)

    /**
     * Reverse geocode via the framework [Geocoder].
     *
     * Deliberately a soft field, never a failed call: a device without a geocoder backend
     * (common on Google-less ROMs) still has a perfectly valid fix, and failing the whole
     * tool for an optional label would throw that away.
     */
    private suspend fun geocode(location: Location, budgetMs: Long): GeocodedAddress {
        if (budgetMs < MIN_FRESH_ATTEMPT_MS) {
            return GeocodedAddress(null, null, "skipped: no time budget left in this location call")
        }
        // The backend's own words are kept: "no geocode provider" and "no match for these
        // coordinates" are different problems for the model to act on (P2).
        // Geocoder.isPresent() is deliberately not consulted — it is deprecated at API 34 and
        // the listener already reports a missing provider through onError.
        var failure: String? = null
        val addresses = withTimeoutOrNull(budgetMs) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                try {
                    Geocoder(context, Locale.getDefault()).getFromLocation(
                        location.latitude,
                        location.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(result: List<Address>) {
                                if (continuation.isActive) continuation.resume(result)
                            }

                            override fun onError(errorMessage: String?) {
                                failure = errorMessage
                                if (continuation.isActive) continuation.resume(emptyList())
                            }
                        }
                    )
                } catch (e: Exception) {
                    failure = e.message
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            }
        } ?: return GeocodedAddress(null, null, "geocode timed out after ${budgetMs}ms")

        val match = addresses.firstOrNull()
            ?: return GeocodedAddress(null, null, failure ?: "geocoder returned no match")
        val parts = listOfNotNull(match.adminArea, match.locality, match.subLocality, match.thoroughfare, match.subThoroughfare)
        val line = parts.joinToString(", ").ifBlank { match.getAddressLine(0).orEmpty() }
        return if (line.isBlank()) {
            GeocodedAddress(null, null, "geocoder returned an empty address")
        } else {
            GeocodedAddress(line, match.countryName?.takeIf { it.isNotBlank() }, null)
        }
    }

    /** Address label + country, or why they are unavailable. */
    private data class GeocodedAddress(val line: String?, val country: String?, val unavailable: String?)

    private fun freshestCachedFix(manager: LocationManager, providers: List<String>): CachedFix? {
        val now = System.currentTimeMillis()
        val best = providers.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null // provider-level gate raced with a revocation; other providers may still answer
            }
        }.maxByOrNull { it.time } ?: return null
        // A device with a skewed clock can report a fix from the future; a negative age would
        // make a bogus fix look permanently fresh and suppress every attempt to get a real one.
        return CachedFix(best, (now - best.time).coerceAtLeast(0L))
    }

    /**
     * One update from [provider], or null when none arrives within [timeoutMs].
     *
     * The listener is unregistered on *every* exit path: on delivery, on provider disablement,
     * on cancellation, and on a failed subscribe. `minTime = 0` means a leaked subscription is a
     * continuous GPS draw for the life of the process, which is exactly what a one-shot tool
     * call must never leave behind.
     */
    private suspend fun awaitFreshFix(
        manager: LocationManager,
        provider: String,
        timeoutMs: Long
    ): FixAttempt {
        if (timeoutMs < MIN_FRESH_ATTEMPT_MS) return FixAttempt(null, "no time budget for a fresh fix")
        var subscribeError: String? = null
        val location = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(fix: Location) {
                        safeRemoveUpdates(manager, this)
                        if (continuation.isActive) continuation.resume(fix)
                    }

                    // Without this, switching Location off mid-wait costs the full slice
                    // instead of falling through to the next provider immediately.
                    override fun onProviderDisabled(disabled: String) {
                        safeRemoveUpdates(manager, this)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { safeRemoveUpdates(manager, listener) }
                try {
                    // Main looper: this body runs on Dispatchers.IO, which has no Looper of its own.
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    // suspendCancellableCoroutine runs this block even when the parent job is
                    // already cancelled — in that case invokeOnCancellation fired *before* the
                    // listener existed, so the subscription we just made has no other cleanup
                    // path. Re-check after subscribing.
                    if (!continuation.isActive) safeRemoveUpdates(manager, listener)
                } catch (e: Exception) {
                    subscribeError = "$provider: ${e.message ?: e.javaClass.simpleName}"
                    safeRemoveUpdates(manager, listener)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        return FixAttempt(location, subscribeError)
    }

    private fun safeRemoveUpdates(manager: LocationManager, listener: LocationListener) {
        try {
            manager.removeUpdates(listener)
        } catch (_: SecurityException) {
            // Permission revoked mid-flight: the system has already stopped delivery.
        }
    }

    // ----- permission seam (P4) -----

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** How a grant attempt ended. Collapsing these loses the difference the model must act on (P2). */
    private enum class GrantAttempt {
        /** The user answered the dialog (in either direction) — re-check the permission. */
        Answered,

        /** The dialog was shown but nobody answered inside the wait budget. */
        NotAnswered,

        /**
         * We did not ask at all: the grant dialog is itself an activity start, and Android
         * silently drops background activity starts (the same platform fact C4 records for our
         * own launches). Asking then would be a guaranteed stall with no dialog on screen.
         */
        CannotAsk
    }

    private suspend fun attemptGrant(permissions: List<String>, rationale: String, waitBudgetMs: Long): GrantAttempt {
        if (waitBudgetMs < MIN_FRESH_ATTEMPT_MS || !isAppForeground()) return GrantAttempt.CannotAsk
        // A `false` answer is still an answer; only a null (timeout) means "never decided".
        val answered = withTimeoutOrNull(waitBudgetMs) { permissionRequester.request(permissions, rationale) }
        return if (answered == null) GrantAttempt.NotAnswered else GrantAttempt.Answered
    }

    private fun ungrantable(missing: List<String>, rationale: String, attempt: GrantAttempt, waitBudgetMs: Long) =
        when (attempt) {
            GrantAttempt.CannotAsk -> PermissionRequired(
                missing,
                "$rationale SimplyWork is not in the foreground, so Android will not show the grant " +
                    "dialog (background activity starts are dropped). Bring the app to the front and retry — " +
                    "repeating this call as-is will fail the same way."
            )

            GrantAttempt.NotAnswered -> PermissionRequired(
                missing,
                "$rationale The grant dialog was shown but went unanswered within ${waitBudgetMs}ms; " +
                    "nothing was granted or denied. Ask the user, or retry once the app is in view."
            )

            GrantAttempt.Answered -> PermissionRequired(missing, rationale)
        }

    /**
     * @return null when every [permissions] is granted, otherwise the result to hand back.
     * Asks the injected [permissionRequester] once before giving up (P4: the UI performs the
     * grant, we only report), then re-checks — "user tapped Allow" is not the same as granted.
     */
    private suspend fun ensurePermissions(
        permissions: List<String>,
        rationale: String,
        waitBudgetMs: Long = PERMISSION_WAIT_BUDGET_MS
    ): SystemOperationResult? {
        if (permissions.all(::isGranted)) return null
        val attempt = attemptGrant(permissions, rationale, waitBudgetMs)
        val stillMissing = permissions.filterNot(::isGranted)
        return if (stillMissing.isEmpty()) null else ungrantable(stillMissing, rationale, attempt, waitBudgetMs)
    }

    /**
     * Variant for capabilities that degrade instead of failing: proceeds as soon as *any* of
     * [permissions] is held. Only reports PermissionRequired when none of them came through.
     */
    private suspend fun ensureAnyPermission(
        permissions: List<String>,
        rationale: String,
        waitBudgetMs: Long = PERMISSION_WAIT_BUDGET_MS
    ): SystemOperationResult? {
        if (permissions.any(::isGranted)) return null
        val attempt = attemptGrant(permissions, rationale, waitBudgetMs)
        return if (permissions.any(::isGranted)) null
        else ungrantable(permissions, rationale, attempt, waitBudgetMs)
    }

    // ----- device snapshot helpers -----

    /** Sticky-intent read: no exported receiver is registered, so no runtime permission needed. */
    private fun stickyBatteryIntent(): Intent? = try {
        ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    } catch (_: Exception) {
        null
    }

    private fun powerSaveMode(): Boolean? = try {
        (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode
    } catch (_: Exception) {
        null
    }

    private fun networkSnapshot() = buildJsonObject {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        val transports = capabilities?.let { caps ->
            listOfNotNull(
                "wifi".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                "cellular".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                "ethernet".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
                "vpn".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
                "bluetooth".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) }
            )
        }.orEmpty()
        put("connected", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
        put("transports", JsonArray(transports.map { JsonPrimitive(it) }))
        put(
            "metered",
            capabilities?.let { JsonPrimitive(!it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) }
                ?: JsonNull
        )
    }

    private fun storageSnapshot() = buildJsonObject {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        put("totalBytes", stat.totalBytes)
        put("availableBytes", stat.availableBytes)
        put(
            "availablePercent",
            if (stat.totalBytes > 0) JsonPrimitive(stat.availableBytes * 100 / stat.totalBytes) else JsonNull
        )
    }

    private fun memorySnapshot() = buildJsonObject {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = manager?.let { ActivityManager.MemoryInfo().also { m -> manager.getMemoryInfo(m) } }
        put("totalBytes", info?.let { JsonPrimitive(it.totalMem) } ?: JsonNull)
        put("availableBytes", info?.let { JsonPrimitive(it.availMem) } ?: JsonNull)
        put("lowMemory", info?.let { JsonPrimitive(it.lowMemory) } ?: JsonNull)
    }

    private fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "notCharging"
        else -> "unknown"
    }

    private fun timePointJson(point: TimePoint) = buildJsonObject {
        put("epochMs", point.epochMs)
        put("epochSeconds", point.epochMs / 1000)
        put("localIso", point.localIso)
        put("localDate", point.localDate)
        put("localTime", point.localTime)
        put("weekday", point.weekday)
        put("timeZoneId", point.timeZoneId)
        put("utcOffsetMinutes", point.utcOffsetMinutes)
    }

    /** A last-known fix plus how long ago (wall clock) the device saw it. */
    private data class CachedFix(val location: Location, val ageMs: Long)

    // ----- helpers -----

    private fun appLabel(): String = context.applicationInfo.loadLabel(packageManager).toString()
}

/** Addition that stops at [Long.MAX_VALUE] instead of wrapping into a negative timestamp. */
private fun Long.saturatingPlus(deltaMs: Long): Long {
    val result = this + deltaMs
    return if ((deltaMs.xor(result).and(this.xor(result))) < 0) {
        if (deltaMs < 0) Long.MIN_VALUE else Long.MAX_VALUE
    } else {
        result
    }
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
