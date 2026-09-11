package com.yangz3720.simplywork.infrastructure.systemops.tool

import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.AppOpenSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarWriteMode
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardWriteSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DeepLinkSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import com.yangz3720.simplywork.infrastructure.systemops.model.LaunchContext
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.OperationSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ShareTextSpec
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
        TOOL_SHARE_TEXT to { j, o -> j.decode(ShareTextSpec.serializer(), o) }
    )

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
                    "priority" to integerProp("NotificationCompat priority (-2..2), default 0"),
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

    private fun integerProp(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("format", "int64")
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
