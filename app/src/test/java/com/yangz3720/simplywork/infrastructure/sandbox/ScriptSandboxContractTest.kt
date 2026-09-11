package com.yangz3720.simplywork.infrastructure.sandbox

import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxConfig
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxResult
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import org.junit.Assert.*
import org.junit.Test

class ScriptSandboxContractTest {

    @Test
    fun testSandboxConfigDefaults() {
        val config = SandboxConfig.STRICT_DEFAULT
        assertEquals(8 * 1024 * 1024L, config.maxMemoryBytes)
        assertEquals(200L, config.timeoutMs)
        assertFalse(config.enableConsoleCapture)
    }

    @Test
    fun testSandboxResultHierarchy() {
        val success: SandboxResult = SandboxResult.Success(
            output = "42",
            executionTimeMs = 15L,
            consoleLogs = listOf("log 1")
        )
        assertTrue(success is SandboxResult.Success)
        assertEquals("42", (success as SandboxResult.Success).output)

        val timeout: SandboxResult = SandboxResult.Timeout(timeoutMs = 200L, executionTimeMs = 205L)
        assertTrue(timeout is SandboxResult.Timeout)

        val oom: SandboxResult = SandboxResult.MemoryLimitExceeded(maxMemoryBytes = 8388608L, executionTimeMs = 50L)
        assertTrue(oom is SandboxResult.MemoryLimitExceeded)

        val error: SandboxResult = SandboxResult.Error(message = "SyntaxError", stackTrace = "at line 1", executionTimeMs = 5L)
        assertTrue(error is SandboxResult.Error)
    }

    @Test
    fun testAgentLoggerCustomPrinter() {
        val logs = mutableListOf<String>()
        AgentLogger.isEnabled = true
        AgentLogger.customLogPrinter = { priority, tag, message, _ ->
            logs.add("[$priority][$tag] $message")
        }

        AgentLogger.d("TestTag") { "Debug message" }
        AgentLogger.i("TestTag") { "Info message" }

        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("Debug message"))
        assertTrue(logs[1].contains("Info message"))

        // Disable logging
        AgentLogger.isEnabled = false
        AgentLogger.d("TestTag") { "Should not be logged" }
        assertEquals(2, logs.size)

        AgentLogger.customLogPrinter = null
    }
}
