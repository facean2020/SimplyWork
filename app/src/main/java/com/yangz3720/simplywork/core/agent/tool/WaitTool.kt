package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Built-in `wait` tool (v1.6): suspends the loop in place for a while, then continues.
 *
 * Why a tool that "does nothing" is needed: an agent constantly hits "what I want to do is not due
 * yet" -- waiting for a file another app writes, waiting for a network condition to change, waiting
 * for an operation just fired off to take effect. Without it the model can only re-read the state
 * immediately, find it unchanged, re-read again (burning turns and context), or simply end the turn
 * and bluff the user.
 *
 * Two deliberate designs:
 * - **Capped**: past the cap we clamp it and return `requestedSeconds` / `clamped` along with the
 *   result. If the model wants an hour, we cannot really stop the loop for an hour -- that is
 *   indistinguishable from a hang. Clamping and telling the truth about it is what gives the model
 *   a chance to change its mind.
 * - **Cancellable**: `delay` rather than a blocking sleep. When the user presses "Stop", the
 *   cancellation propagates along the coroutine to here and the wait ends at once
 *   (`AgentLoopCoordinator` only lets `CancellationException` through, see its line 252).
 *
 * It does not go through `SystemOperationRouter`: the Router's 10s timeout is meant for `sys_*`, and
 * going through it would turn a long wait directly into `SystemOperationResult.Timeout`.
 */
object WaitTool {

    const val TOOL_NAME = "wait"

    /** Cap on a single wait (seconds). Anything longer should use an async script job + `js_await`, not idle waiting. */
    const val MAX_WAIT_SECONDS = 300.0

    @Serializable
    data class WaitInput(
        /** Double rather than Int: the model occasionally sends `30.5` or `1.0`, and an Int would simply fail to parse. */
        val seconds: Double,
        val reason: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun definition(): ToolDefinition = ToolDefinition(
        name = TOOL_NAME,
        description = "Pause this run for a number of seconds, then continue with the next step. " +
            "Use it when the thing you are waiting for happens outside this app (another app " +
            "writing a file, a network condition settling) and re-reading state immediately " +
            "would just show the same thing. Maximum ${MAX_WAIT_SECONDS.toInt()} seconds per " +
            "call; longer requests are capped and reported. Prefer js_run + js_await when you " +
            "are waiting on a script instead of on the outside world.",
        parametersSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("seconds", buildJsonObject {
                    put("type", "number")
                    put("description", "How long to pause, in seconds (max ${MAX_WAIT_SECONDS.toInt()}).")
                })
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "One short sentence on what you are waiting for (shown to the user).")
                })
            })
            put("required", JsonArray(listOf(JsonPrimitive("seconds"))))
        }
    )

    /** Pure waiting: touches neither the session nor storage, so registering it needs no dependency at all. */
    fun registeredTool(): RegisteredTool = RegisteredTool(
        definition = definition(),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw -> runCatching { json.decodeFromJsonElement(WaitInput.serializer(), raw) } },
        runner = { parsed, _ ->
            val input = parsed as? WaitInput
                ?: return@RegisteredTool errorObject("wait arguments did not parse")
            await(input)
        }
    )

    private suspend fun await(input: WaitInput): JsonObject {
        // Only non-positive values need guarding: non-finite values like NaN / Infinity are already
        // rejected by kotlinx during JSON decoding (`allowSpecialFloatingPointValues` is off by
        // default), so they never reach here.
        if (input.seconds <= 0) {
            return errorObject("seconds must be a positive number (got ${input.seconds})")
        }
        val waited = input.seconds.coerceAtMost(MAX_WAIT_SECONDS)
        delay((waited * 1000).toLong())
        return buildJsonObject {
            put("status", "success")
            put("waitedSeconds", waited)
            // If we clamped, say so faithfully: the model believing it waited an hour when it
            // actually waited 5 minutes is a fact that would mislead everything downstream.
            if (waited < input.seconds) {
                put("requestedSeconds", input.seconds)
                put("clamped", true)
                put("note", "requested wait exceeded the ${MAX_WAIT_SECONDS.toInt()}s cap and was truncated")
            }
            input.reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
        }
    }

    private fun errorObject(message: String): JsonObject = buildJsonObject {
        put("status", "invalid_arguments")
        put("reason", message)
    }
}
