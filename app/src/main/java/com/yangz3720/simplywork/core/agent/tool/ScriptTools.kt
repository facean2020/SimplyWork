package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.core.agent.ScriptJobOutcome
import com.yangz3720.simplywork.core.agent.ScriptJobRegistry
import com.yangz3720.simplywork.core.agent.ScriptJobStart
import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxResult
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Sandbox script tool family (v1.6): `js_run` starts an async job, `js_await` waits for it (with a settable maximum wait).
 *
 * Why async rather than "block on the call": the loop is serial, so waiting synchronously for a script either leaves the user seeing no progress at all
 * or, when the script is on the long side, forces an interruption. Async decouples the two — the job runs on its own; the loop can choose to
 * **actively wait** (`js_await`, with a maximum wait, and on timeout go do something else), or **wrap up for now**,
 * letting [ScriptJobRegistry.onFinished] call it back once the job finishes (in this project that means pushing one
 * FollowUp onto the message queue, namely `js_job_finished`).
 *
 * The sandbox is pure computation: no host object is injected, the script gets no file, network or Android API, so the
 * danger level is SAFE; the script itself is just a string in memory and changes no persistent state.
 */
object ScriptTools {

    const val RUN_TOOL_NAME = "js_run"
    const val AWAIT_TOOL_NAME = "js_await"

    /** Script timeout: 10s by default. The sandbox's historical default was 200ms (pure glue functions), far too short for a job. */
    const val DEFAULT_SCRIPT_TIMEOUT_MS = 10_000L
    const val MIN_SCRIPT_TIMEOUT_MS = 100L

    /** Upper bound for the script timeout: anything longer should not be run in on-device QuickJS. */
    const val MAX_SCRIPT_TIMEOUT_MS = 60_000L

    /** Default maximum wait for `js_await`: 60s. */
    const val DEFAULT_MAX_WAIT_SECONDS = 60.0

    /** Wait ceiling for a single `js_await`: 600s. */
    const val MAX_WAIT_SECONDS = 600.0

    @Serializable
    data class RunInput(
        val script: String,
        val timeoutMs: Long? = null,
        /** A short name for this job; it is carried in the completion notice and is easier to recognise than a bare id. */
        val label: String? = null
    )

    @Serializable
    data class AwaitInput(
        /** Double on purpose: `0`, `30` and `30.5` must all be accepted; 0 = just take a look, do not wait. */
        val jobId: String,
        val maxWaitSeconds: Double? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun registeredTools(jobs: ScriptJobRegistry): List<RegisteredTool> =
        listOf(runTool(jobs), awaitTool(jobs))

    // ----- js_run -----

    private fun runTool(jobs: ScriptJobRegistry): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = RUN_TOOL_NAME,
            description = "Start a JavaScript snippet in the isolated sandbox and return immediately " +
                "with a jobId. Use it for data glue work (parsing, reshaping, computing) that is " +
                "bigger than a couple of lines. The script is pure: it has no file, network or " +
                "device access, and only sees variables you pass it — it cannot touch the " +
                "workspace. The result's LAST expression is the returned value. " +
                "After starting, either call $AWAIT_TOOL_NAME to wait for it, or end your turn " +
                "and you will be woken with a js_job_finished message when it completes.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("script", buildJsonObject {
                        put("type", "string")
                        put("description", "JavaScript to evaluate. The last expression is the result.")
                    })
                    put("timeoutMs", buildJsonObject {
                        put("type", "integer")
                        put("description", "Kill the script after this many milliseconds " +
                            "($MIN_SCRIPT_TIMEOUT_MS–$MAX_SCRIPT_TIMEOUT_MS, default $DEFAULT_SCRIPT_TIMEOUT_MS).")
                    })
                    put("label", buildJsonObject {
                        put("type", "string")
                        put("description", "Short name for this job, used in the completion notice.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("script"))))
            }
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw -> runCatching { json.decodeFromJsonElement(RunInput.serializer(), raw) } },
        runner = { parsed, context ->
            val input = parsed as? RunInput
                ?: return@RegisteredTool errorObject("$RUN_TOOL_NAME arguments did not parse")
            if (input.script.isBlank()) {
                return@RegisteredTool errorObject("script must not be blank")
            }
            val timeout = (input.timeoutMs ?: DEFAULT_SCRIPT_TIMEOUT_MS)
                .coerceIn(MIN_SCRIPT_TIMEOUT_MS, MAX_SCRIPT_TIMEOUT_MS)
            when (val started = jobs.start(context.sessionId, input.script, timeout)) {
                is ScriptJobStart.Started -> buildJsonObject {
                    put("status", "started")
                    put("jobId", started.jobId)
                    input.label?.takeIf { it.isNotBlank() }?.let { put("label", it) }
                    put("timeoutMs", timeout)
                    put(
                        "note",
                        "Running in the background. Call $AWAIT_TOOL_NAME to wait for it, or end " +
                            "your turn — a js_job_finished message will wake you when it completes."
                    )
                }

                is ScriptJobStart.Rejected -> errorObject(started.reason)
            }
        }
    )

    // ----- js_await -----

    private fun awaitTool(jobs: ScriptJobRegistry): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = AWAIT_TOOL_NAME,
            description = "Wait for a script started by $RUN_TOOL_NAME to finish, then get its " +
                "result. Blocks this run for at most maxWaitSeconds (default " +
                "${DEFAULT_MAX_WAIT_SECONDS.toInt()}); if it is still running when that elapses " +
                "you get status=still_running and the job KEEPS RUNNING — you can await it again " +
                "or go do something else. Pass 0 to just check without waiting. Use it when you " +
                "need the result now; otherwise let the js_job_finished message come to you.",
            parametersSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("jobId", buildJsonObject {
                        put("type", "string")
                        put("description", "The jobId returned by $RUN_TOOL_NAME.")
                    })
                    put("maxWaitSeconds", buildJsonObject {
                        put("type", "number")
                        put("description", "Maximum seconds to wait (0–${MAX_WAIT_SECONDS.toInt()}, " +
                            "default ${DEFAULT_MAX_WAIT_SECONDS.toInt()}). 0 = do not wait.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("jobId"))))
            }
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw -> runCatching { json.decodeFromJsonElement(AwaitInput.serializer(), raw) } },
        runner = { parsed, _ ->
            val input = parsed as? AwaitInput
                ?: return@RegisteredTool errorObject("$AWAIT_TOOL_NAME arguments did not parse")
            if (input.jobId.isBlank()) {
                return@RegisteredTool errorObject("jobId must not be blank")
            }
            // Non-positive values (including 0 passed explicitly by the model) all mean "just take a look, do not wait"; non-finite values are already rejected at JSON decode time.
            val maxWait = (input.maxWaitSeconds ?: DEFAULT_MAX_WAIT_SECONDS)
                .takeIf { it > 0 }
                ?.coerceAtMost(MAX_WAIT_SECONDS)
                ?: 0.0
            when (val outcome = jobs.await(input.jobId, (maxWait * 1000).toLong())) {
                is ScriptJobOutcome.Finished -> buildJsonObject {
                    put("jobId", outcome.jobId)
                    outcome.result.toJson().forEach { (key, value) -> put(key, value) }
                }

                is ScriptJobOutcome.Pending -> buildJsonObject {
                    put("status", "still_running")
                    put("jobId", outcome.jobId)
                    put("waitedMs", outcome.waitedMs)
                    put(
                        "note",
                        "The job is still running — it was NOT cancelled. Await it again later, or " +
                            "end your turn and wait for the js_job_finished message."
                    )
                }

                is ScriptJobOutcome.Unknown -> errorObject(
                    "unknown jobId '${outcome.jobId}' — it may have been mistyped, evicted, or lost " +
                        "with the process (script jobs are not persisted)"
                )
            }
        }
    )

    /**
     * Body of the job completion notice. The wiring layer pushes it onto the message queue so the loop reads it in one of its turns.
     */
    fun finishNotice(jobId: String, result: SandboxResult): String =
        "Script job $jobId finished. ${result.toNotice()} Read the full result with " +
            "$AWAIT_TOOL_NAME(jobId=\"$jobId\")."

    private fun SandboxResult.toNotice(): String = when (this) {
        is SandboxResult.Success -> "status=success, output=$output"
        is SandboxResult.Error -> "status=error, message=$message"
        is SandboxResult.Timeout -> "status=timeout after ${timeoutMs}ms"
        is SandboxResult.MemoryLimitExceeded -> "status=memory_limit_exceeded (${maxMemoryBytes} bytes)"
        is SandboxResult.EngineUnavailable -> "status=engine_unavailable, message=$message"
    }

    /** The five outcomes are unified into one explicit table: the model does not have to guess what "no output" means. */
    private fun SandboxResult.toJson(): JsonObject = when (this) {
        is SandboxResult.Success -> buildJsonObject {
            put("status", "success")
            put("output", output)
            put("executionTimeMs", executionTimeMs)
        }

        is SandboxResult.Error -> buildJsonObject {
            put("status", "error")
            put("message", message)
            put("executionTimeMs", executionTimeMs)
        }

        is SandboxResult.Timeout -> buildJsonObject {
            put("status", "timeout")
            put("timeoutMs", timeoutMs)
            put("executionTimeMs", executionTimeMs)
            put("note", "the script was killed when it exceeded its timeout")
        }

        is SandboxResult.MemoryLimitExceeded -> buildJsonObject {
            put("status", "memory_limit_exceeded")
            put("maxMemoryBytes", maxMemoryBytes)
            put("executionTimeMs", executionTimeMs)
            put("note", "reduce the amount of data the script holds at once")
        }

        is SandboxResult.EngineUnavailable -> buildJsonObject {
            put("status", "engine_unavailable")
            put("message", message)
            put("executionTimeMs", executionTimeMs)
            put("note", "the JS engine service could not be reached")
        }
    }

    private fun errorObject(message: String): JsonObject = buildJsonObject {
        put("status", "invalid_arguments")
        put("reason", message)
    }
}
