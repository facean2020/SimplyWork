package com.yangz3720.simplywork.core.agent.tool

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `wait` tool: pure pause. It runs on virtual time and never really waits 5 minutes.
 */
class WaitToolTest {

    private val tool = WaitTool.registeredTool()

    private fun context() = ToolExecutionContext(sessionId = "s1")

    private fun args(seconds: Double, reason: String? = null): JsonObject = buildJsonObject {
        put("seconds", seconds)
        reason?.let { put("reason", it) }
    }

    private fun parse(raw: JsonObject) = tool.parse(raw).getOrThrow()

    @Test
    fun `waits exactly the requested time and reports it`() = runTest {
        val before = testScheduler.currentTime
        val result = tool.execute(parse(args(30.0, "waiting for the export to land")), context())
        val elapsed = testScheduler.currentTime - before

        assertEquals(30_000L, elapsed)
        assertEquals("success", result["status"]?.jsonPrimitive?.content)
        assertEquals(30.0, result["waitedSeconds"]?.jsonPrimitive?.double ?: 0.0, 0.001)
        assertTrue(result["reason"]?.jsonPrimitive?.content.orEmpty().contains("export"))
        // when nothing was clamped, no clamped field may appear -- an extra field is one more thing for the model to misread
        assertFalse(result.containsKey("clamped"))
    }

    @Test
    fun `an over-long wait is capped and says so`() = runTest {
        val before = testScheduler.currentTime
        val result = tool.execute(parse(args(3_600.0, "sleep an hour")), context())

        // really waiting an hour amounts to a hang: clamp to the cap and state both "what you asked for" and "what was actually waited" faithfully
        assertEquals(WaitTool.MAX_WAIT_SECONDS.toLong() * 1000, testScheduler.currentTime - before)
        assertEquals(WaitTool.MAX_WAIT_SECONDS, result["waitedSeconds"]?.jsonPrimitive?.double ?: 0.0, 0.001)
        assertEquals(3_600.0, result["requestedSeconds"]?.jsonPrimitive?.double ?: 0.0, 0.001)
        assertEquals("true", result["clamped"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a fractional wait works`() = runTest {
        val before = testScheduler.currentTime
        tool.execute(parse(args(0.5)), context())

        assertEquals(500L, testScheduler.currentTime - before)
    }

    @Test
    fun `non-positive waits are rejected instead of silently passing`() = runTest {
        listOf(0.0, -1.0).forEach { bad ->
            val result = tool.execute(parse(args(bad)), context())
            assertEquals(
                "seconds=$bad should be rejected",
                "invalid_arguments",
                result["status"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `a wait that overflows to infinity is rejected, not coerced`() = runTest {
        // `1e999` is legal JSON syntax, but a Double cannot hold it -> it decodes to Infinity and kotlinx rejects it outright.
        // the outcome is the explicit "arguments did not parse" failure, rather than coerceAtMost turning it into an absurd wait duration.
        val overflow = Json.parseToJsonElement("""{"seconds":1e999}""").jsonObject

        val parsed = tool.parse(overflow)
        assertTrue("parser should reject non-finite seconds", parsed.isFailure)
        val result = tool.execute(parsed.getOrNull(), context())
        assertEquals("invalid_arguments", result["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unparsable arguments return an explicit error rather than throwing`() = runTest {
        val result = tool.execute(parsed = null, context = context())

        assertEquals("invalid_arguments", result["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a wait is cancellable - stopping the run must not leave it hanging`() = runTest {
        val job = launch { tool.execute(parse(args(240.0)), context()) }
        advanceTimeBy(1_000)

        assertFalse(job.isCompleted)

        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `the tool is safe and carries the cap in its description`() {
        val definition = tool.definition

        assertEquals(WaitTool.TOOL_NAME, definition.name)
        // the cap has to be in the description: the model cannot see the implementation, only this text
        assertTrue(definition.description.contains(WaitTool.MAX_WAIT_SECONDS.toInt().toString()))
    }
}
