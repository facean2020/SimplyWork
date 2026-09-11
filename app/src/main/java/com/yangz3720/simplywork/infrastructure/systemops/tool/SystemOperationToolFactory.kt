package com.yangz3720.simplywork.infrastructure.systemops.tool

import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.AppListSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.AppOpenSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarQuerySpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarWriteMode
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardWriteSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DeepLinkSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import com.yangz3720.simplywork.infrastructure.systemops.model.DeviceReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import com.yangz3720.simplywork.infrastructure.systemops.model.LocationAccuracy
import com.yangz3720.simplywork.infrastructure.systemops.model.LocationReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.OperationSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ShareTextSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeUnit
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeCalcSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeCalculation
import com.yangz3720.simplywork.infrastructure.systemops.model.TimeReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.Weekday
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.reflect.KClass

/**
 * One system tool as registered in the (future) tool-core registry: the LLM-facing
 * [ToolDefinition] plus the metadata tool-core needs for audit (P3: danger level travels
 * with the registration) and dispatch ([specKind]).
 */
data class SystemToolEntry(
    val definition: ToolDefinition,
    val dangerLevel: DangerLevel,
    val specKind: KClass<out OperationSpec>
)

/**
 * Converges [OperationSpec]s into closed `sys_*` tools (§6.1 of appfunction-design.md).
 * Tool names form a dedicated namespace, isolated from any future dynamic tool sources.
 */
object SystemOperationToolFactory {

    const val TOOL_DEEPLINK_OPEN = "sys_deeplink_open"
    const val TOOL_APP_OPEN = "sys_app_open"
    const val TOOL_CLIPBOARD_READ = "sys_clipboard_read"
    const val TOOL_CLIPBOARD_WRITE = "sys_clipboard_write"
    const val TOOL_CALENDAR_INSERT = "sys_calendar_insert"
    const val TOOL_NOTIFY_POST = "sys_notify_post"
    const val TOOL_SHARE_TEXT = "sys_share_text"

    // v1.2 read-side tools.
    const val TOOL_TIME_READ = "sys_time_read"
    const val TOOL_TIME_CALC = "sys_time_calc"
    const val TOOL_DEVICE_READ = "sys_device_read"
    const val TOOL_APP_LIST = "sys_app_list"
    const val TOOL_CALENDAR_QUERY = "sys_calendar_query"
    const val TOOL_LOCATION_READ = "sys_location_read"

    /** Parser-enforced ceilings: a model-invented huge limit is rejected, never silently clamped. */
    private const val MAX_APP_LIMIT = 100
    private const val MAX_CALENDAR_EVENTS = 40
    private const val MAX_LOCATION_AGE_SECONDS = 24 * 60 * 60
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Ceiling on an explicit query window. An unbounded window asks CalendarProvider2 to expand
     * occurrences across the whole supported range synchronously: the call can only ever come
     * back as `timeout` while the provider keeps churning. Bounding it here is the same rule as
     * the other ceilings — reject at parse time, do not clamp in secret.
     */
    private const val MAX_CALENDAR_SPAN_MS = 92L * DAY_MS

    /** 2100-01-01. Rejects obviously-wrong epochs before they can overflow span arithmetic. */
    private const val SANE_MAX_EPOCH_MS = 4_102_444_800_000L

    private val json = Json { ignoreUnknownKeys = true }

    fun toolEntries(): List<SystemToolEntry> = ENTRIES

    fun toolNames(): List<String> = ENTRIES.map { it.definition.name }

    /**
     * Parses model-emitted tool arguments back into the typed spec the router dispatches on.
     * Unknown extra keys are tolerated ([json]); malformed required fields fail the [Result].
     */
    fun parseArguments(toolName: String, argumentsJson: JsonObject): Result<OperationSpec> {
        val parser = PARSERS[toolName]
            ?: return Result.failure(IllegalArgumentException("Unknown system tool: $toolName"))
        return runCatching { parser(json, argumentsJson) }
    }

    // ----- parsers -----

    private val PARSERS: Map<String, (Json, JsonObject) -> OperationSpec> = mapOf(
        TOOL_DEEPLINK_OPEN to { j, o -> j.decode(DeepLinkSpec.serializer(), o) },
        TOOL_APP_OPEN to { j, o -> j.decode(AppOpenSpec.serializer(), o) },
        // Parameterless operation: arguments are irrelevant, object is a singleton.
        TOOL_CLIPBOARD_READ to { _, _ -> ClipboardReadSpec },
        TOOL_CLIPBOARD_WRITE to { j, o -> j.decode(ClipboardWriteSpec.serializer(), o) },
        TOOL_CALENDAR_INSERT to { j, o -> j.decode(CalendarInsertSpec.serializer(), o) },
        TOOL_NOTIFY_POST to { j, o -> j.decode(NotificationPostSpec.serializer(), o) },
        TOOL_SHARE_TEXT to { j, o -> j.decode(ShareTextSpec.serializer(), o) },
        TOOL_TIME_READ to { _, _ -> TimeReadSpec },
        TOOL_TIME_CALC to { j, o ->
            j.decode(TimeCalcSpec.serializer(), o).also { spec ->
                TimeCalculation.validate(spec)?.let { throw IllegalArgumentException(it) }
            }
        },
        TOOL_DEVICE_READ to { _, _ -> DeviceReadSpec },
        TOOL_APP_LIST to { j, o ->
            j.decode(AppListSpec.serializer(), o).also {
                guard(it.limit in 1..MAX_APP_LIMIT, "limit must be between 1 and $MAX_APP_LIMIT")
            }
        },
        TOOL_CALENDAR_QUERY to { j, o ->
            j.decode(CalendarQuerySpec.serializer(), o).also {
                guard(it.limit in 1..MAX_CALENDAR_EVENTS, "limit must be between 1 and $MAX_CALENDAR_EVENTS")
                guard(
                    it.startTimeEpochMs == null || it.startTimeEpochMs in 0..SANE_MAX_EPOCH_MS,
                    "startTimeEpochMs is not a plausible epoch milliseconds value"
                )
                guard(
                    it.endTimeEpochMs == null || it.endTimeEpochMs in 0..SANE_MAX_EPOCH_MS,
                    "endTimeEpochMs is not a plausible epoch milliseconds value"
                )
                guard(
                    it.startTimeEpochMs == null || it.endTimeEpochMs == null || it.startTimeEpochMs < it.endTimeEpochMs,
                    "startTimeEpochMs must be earlier than endTimeEpochMs"
                )
                val start = it.startTimeEpochMs
                val end = it.endTimeEpochMs
                guard(
                    start == null || end == null || end - start <= MAX_CALENDAR_SPAN_MS,
                    "query window must span at most ${MAX_CALENDAR_SPAN_MS / DAY_MS} days"
                )
            }
        },
        TOOL_LOCATION_READ to { j, o ->
            j.decode(LocationReadSpec.serializer(), o).also {
                guard(
                    it.maxAgeSeconds in 0..MAX_LOCATION_AGE_SECONDS,
                    "maxAgeSeconds must be between 0 and $MAX_LOCATION_AGE_SECONDS"
                )
            }
        }
    )

    /**
     * Throws to fail the parse: the audit gate turns this into a `Deny("Invalid arguments: …")`
     * that the model reads and self-corrects from (P2), which is why bounds live here rather
     * than being silently clamped at execution time.
     */
    private fun guard(condition: Boolean, message: String) {
        if (!condition) throw IllegalArgumentException(message)
    }

    private fun <T> Json.decode(serializer: KSerializer<T>, element: JsonObject): T =
        decodeFromJsonElement(serializer, element)

    // ----- tool catalog -----

    private val ENTRIES: List<SystemToolEntry> = listOf(
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_DEEPLINK_OPEN,
                description = "Open a deep link / URI (https or an app-specific scheme) via the system. " +
                    "When background launch is restricted, the link is delivered as a tappable notification instead.",
                parametersSchema = objectSchema(
                    "uri" to stringProp("The URI to open, e.g. 'https://example.com' or an app scheme link"),
                    "launchContext" to enumProp(
                        LaunchContext.entries.map { it.name },
                        "Background launch policy (C4): AUTO (default) launches now when foreground or SAW-exempt, " +
                            "else via notification; FOREGROUND_NOW only launches when the app is focused; " +
                            "FROM_OVERLAY_SERVICE requires the SAW exemption; VIA_NOTIFICATION_FALLBACK always uses a notification."
                    ),
                    required = listOf("uri")
                )
            ),
            dangerLevel = DangerLevel.USER_CONFIRMING,
            specKind = DeepLinkSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_APP_OPEN,
                description = "Open an installed app by package name. Unknown package → TargetNotFound.",
                parametersSchema = objectSchema(
                    "packageName" to stringProp("Fully qualified package name, e.g. 'com.example.app'"),
                    required = listOf("packageName")
                )
            ),
            dangerLevel = DangerLevel.USER_CONFIRMING,
            specKind = AppOpenSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_CLIPBOARD_READ,
                description = "Read the current clipboard text. Only works while the app is in foreground focus; " +
                    "otherwise returns SecurityBlocked (Android restriction) — treat it as blocked, not empty.",
                parametersSchema = objectSchema()
            ),
            dangerLevel = DangerLevel.SAFE,
            specKind = ClipboardReadSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_CLIPBOARD_WRITE,
                description = "Write text to the system clipboard (foreground focus required).",
                parametersSchema = objectSchema(
                    "label" to stringProp("User-visible label describing the clipboard content"),
                    "text" to stringProp("The text to place on the clipboard"),
                    required = listOf("label", "text")
                )
            ),
            dangerLevel = DangerLevel.USER_VISIBLE,
            specKind = ClipboardWriteSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_CALENDAR_INSERT,
                description = "Insert a calendar event. Default mode EDITOR_INTENT opens the calendar app's editor " +
                    "for the user to confirm (no permission needed); DIRECT_WRITE writes the provider directly " +
                    "(requires calendar permissions, may return PermissionRequired).",
                parametersSchema = objectSchema(
                    "title" to stringProp("Event title"),
                    "description" to stringProp("Optional event description"),
                    "location" to stringProp("Optional event location"),
                    "startTimeEpochMs" to integerProp("Event start time in epoch milliseconds"),
                    "endTimeEpochMs" to integerProp("Event end time in epoch milliseconds (all-day: exclusive, snapped to next local midnight)"),
                    "isAllDay" to booleanProp("Whether this is an all-day event (times snap to local midnights)"),
                    "mode" to enumProp(
                        CalendarWriteMode.entries.map { it.name },
                        "EDITOR_INTENT (default) asks the user to confirm in the calendar app; DIRECT_WRITE writes without confirmation"
                    ),
                    required = listOf("title", "startTimeEpochMs", "endTimeEpochMs")
                )
            ),
            // Resolved per spec instance at audit time (EDITOR_INTENT=USER_VISIBLE, DIRECT_WRITE=USER_CONFIRMING);
            // the entry-level level reflects the default mode.
            dangerLevel = DangerLevel.USER_VISIBLE,
            specKind = CalendarInsertSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_NOTIFY_POST,
                description = "Post a notification to the user. Returns PermissionRequired when POST_NOTIFICATIONS is not granted.",
                parametersSchema = objectSchema(
                    "channelId" to stringProp("Notification channel id (created on demand)"),
                    "title" to stringProp("Notification title"),
                    "message" to stringProp("Notification body text"),
                    "priority" to integerProp("NotificationCompat priority (-2..2), default 0", format = "int32"),
                    required = listOf("channelId", "title", "message")
                )
            ),
            dangerLevel = DangerLevel.USER_VISIBLE,
            specKind = NotificationPostSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_SHARE_TEXT,
                description = "Open the system share sheet with the given text content.",
                parametersSchema = objectSchema(
                    "title" to stringProp("Chooser sheet title (blank to omit)"),
                    "content" to stringProp("The text content to share"),
                    required = listOf("title", "content")
                )
            ),
            dangerLevel = DangerLevel.USER_VISIBLE,
            specKind = ShareTextSpec::class
        ),
        // ----- v1.2 read-side tools -----
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_TIME_READ,
                description = "Read the device clock: current date, time, weekday, timezone and epoch " +
                    "milliseconds. No parameters. You have no built-in sense of time — call this before " +
                    "answering any 'today'/'now' question and before building epoch timestamps for other tools.",
                parametersSchema = objectSchema()
            ),
            dangerLevel = DangerLevel.SAFE,
            specKind = TimeReadSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_TIME_CALC,
                description = "Turn a relative expression ('next Friday 3pm', 'in 3 days', 'tomorrow 09:00') " +
                    "into an absolute epochMs. Steps in order: shift base by amount+unit, then advance strictly " +
                    "forward to weekday, then overwrite the clock with timeOfDay. Use this instead of doing date " +
                    "arithmetic yourself.",
                parametersSchema = objectSchema(
                    "amount" to integerProp("Signed offset applied to the base, e.g. 3 or -2. Default 0", format = "int32"),
                    "unit" to enumProp(
                        TimeUnit.entries.map { it.name },
                        "Unit for amount (default DAY). MONTH/YEAR clamp the day-of-month (Jan 31 + 1 month = Feb 28)"
                    ),
                    "weekday" to enumProp(
                        Weekday.entries.map { it.name },
                        "Advance strictly PAST the current day to this weekday (so 'FRIDAY' on a Friday means next Friday). Omit for 'this Friday'."
                    ),
                    "timeOfDay" to stringProp("Set the local clock to 24-hour 'HH:mm' after the offset, e.g. '15:00'"),
                    "baseEpochMs" to integerProp("Anchor instant; defaults to the current device time"),
                    required = emptyList()
                )
            ),
            dangerLevel = DangerLevel.SAFE,
            specKind = TimeCalcSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_DEVICE_READ,
                description = "Read device state: battery level/charging/status, network connectivity and " +
                    "transport, storage and memory headroom, uptime, manufacturer/model, Android version, " +
                    "locale and timezone. No parameters.",
                parametersSchema = objectSchema()
            ),
            dangerLevel = DangerLevel.SAFE,
            specKind = DeviceReadSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_APP_LIST,
                description = "List installed apps as {packageName, label}. Call this before sys_app_open — " +
                    "app_open needs an exact package name and guessing one returns target_not_found.",
                parametersSchema = objectSchema(
                    "query" to stringProp("Case-insensitive filter matched against the display label or package name"),
                    "limit" to integerProp("Maximum entries to return (1..$MAX_APP_LIMIT), default 40", format = "int32"),
                    "launchableOnly" to booleanProp("Default true = only apps with a launcher entry; false = every installed package")
                )
            ),
            dangerLevel = DangerLevel.SAFE,
            specKind = AppListSpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_CALENDAR_QUERY,
                description = "Read calendar events in a time window (default: the next 7 days from now), " +
                    "expanding recurring appointments into real occurrences. Returns epochMs plus local ISO " +
                    "times. Needs READ_CALENDAR. Check the 'truncated' flag before calling a window complete, " +
                    "and read the permission_required rationale: it distinguishes 'user denied' from 'app is in " +
                    "the background so no dialog could be shown' — retrying the same call fixes neither.",
                parametersSchema = objectSchema(
                    "startTimeEpochMs" to integerProp("Window start; defaults to the current time"),
                    "endTimeEpochMs" to integerProp("Window end; defaults to startTimeEpochMs + 7 days"),
                    "query" to stringProp("Optional case-insensitive substring filter on the event title"),
                    "limit" to integerProp("Maximum events (1..$MAX_CALENDAR_EVENTS), default 20", format = "int32")
                )
            ),
            // Reads the user's private schedule straight out of the provider, so it passes the
            // HITL gate — unlike calendar INSERT in EDITOR_INTENT mode, which the calendar app confirms.
            dangerLevel = DangerLevel.USER_CONFIRMING,
            specKind = CalendarQuerySpec::class
        ),
        SystemToolEntry(
            ToolDefinition(
                name = TOOL_LOCATION_READ,
                description = "Read the device location (latitude/longitude + accuracy and age). " +
                    "Needs the location runtime permission AND device Location switched on; a " +
                    "security_blocked result means Location is off on the device, not that permission is missing. " +
                    "Returns the newest available fix — when no fresh fix arrives in time it hands back a cached " +
                    "one with isFromCache=true and its age, so raise maxAgeSeconds rather than retrying. " +
                    "A cold GPS fix can take longer than this call's whole budget, and nothing can be located " +
                    "while the app is backgrounded. Prefer APPROXIMATE unless the task needs street-level precision.",
                parametersSchema = objectSchema(
                    "accuracy" to enumProp(
                        LocationAccuracy.entries.map { it.name },
                        "APPROXIMATE (default) prefers network/passive providers; PRECISE prefers GPS. Both fall " +
                            "back to whichever providers are enabled, and if the user granted approximate-only, " +
                            "PRECISE still succeeds on a coarse fix and reports precisionDegraded."
                    ),
                    "maxAgeSeconds" to integerProp(
                        "How stale a cached fix may be before asking a provider for a fresh one (default 120). " +
                            "Set it high to take the cache and skip the wait entirely.",
                        format = "int32"
                    ),
                    "includeAddress" to booleanProp("Reverse-geocode to a human address line. Costs an extra lookup inside this call's time budget and is unavailable on devices without a geocoder backend — the result then carries addressUnavailable.")
                )
            ),
            dangerLevel = DangerLevel.USER_CONFIRMING,
            specKind = LocationReadSpec::class
        )
    )

    // ----- JSON Schema helpers -----

    private fun objectSchema(
        vararg properties: Pair<String, JsonObject>,
        required: List<String> = emptyList()
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { properties.forEach { (name, schema) -> put(name, schema) } })
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }

    private fun stringProp(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    /**
     * `format` is part of the contract with the model: an int64 advertisement on an `Int` field
     * invites a value that fails kotlinx decoding, which costs a round of `audit_denied` instead
     * of the actionable message our own guards produce.
     */
    private fun integerProp(description: String, format: String = "int64"): JsonObject = buildJsonObject {
        put("type", "integer")
        put("format", format)
        put("description", description)
    }

    private fun booleanProp(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun enumProp(values: List<String>, description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        put("description", description)
    }
}
