package com.yangz3720.simplywork.infrastructure.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yangz3720.simplywork.infrastructure.sandbox.engine.QuickJsSandbox
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxConfig
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsSandboxAcceptanceTest {

    private lateinit var sandbox: QuickJsSandbox

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = QuickJsSandbox(appContext)
    }

    @After
    fun tearDown() {
        sandbox.close()
    }

    @Test
    fun testBasicMathEvaluation() = runBlocking {
        val result = sandbox.execute(
            script = "1 + 1"
        )
        assertTrue(result is SandboxResult.Success)
        val success = result as SandboxResult.Success
        assertEquals("2", success.output)
    }

    @Test
    fun testContextVariableInjection() = runBlocking {
        val context = mapOf(
            "a" to 15,
            "b" to 27,
            "greeting" to "Hello World"
        )
        val result = sandbox.execute(
            script = "greeting + ': ' + (a + b)",
            context = context
        )
        assertTrue(result is SandboxResult.Success)
        val success = result as SandboxResult.Success
        assertEquals("Hello World: 42", success.output)
    }

    @Test
    fun testComplexJsonAndArrayManipulation() = runBlocking {
        val context = mapOf(
            "items" to listOf(1, 2, 3, 4, 5)
        )
        val result = sandbox.execute(
            script = """
                var filtered = items.filter(function(x) { return x > 2; });
                var mapped = filtered.map(function(x) { return x * 10; });
                mapped;
            """.trimIndent(),
            context = context
        )
        assertTrue(result is SandboxResult.Success)
        val success = result as SandboxResult.Success
        assertEquals("[30,40,50]", success.output)
    }

    @Test
    fun testTimeoutQuotaStopsInfiniteLoop() = runBlocking {
        val config = SandboxConfig(
            timeoutMs = 150L,
            maxMemoryBytes = 8 * 1024 * 1024L
        )
        // Outer safety net: if the sandbox's own quota fails again, fail loudly here
        // instead of hanging the whole instrumentation run.
        val result = kotlinx.coroutines.withTimeout(15_000L) {
            sandbox.execute(
                script = "while(true) {}",
                config = config
            )
        }
        assertTrue(
            "Expected timeout result for infinite loop, got: $result",
            result is SandboxResult.Timeout
        )
        val timeout = result as SandboxResult.Timeout
        assertEquals(150L, timeout.timeoutMs)

        // Verify sandbox isolate is restored and works for subsequent executions
        val recoveryResult = sandbox.execute("2 * 3")
        assertTrue(recoveryResult is SandboxResult.Success)
        assertEquals("6", (recoveryResult as SandboxResult.Success).output)
    }

    @Test
    fun testSyntaxErrorCapturedCleanly() = runBlocking {
        val result = sandbox.execute(
            script = "var a = ;"
        )
        assertTrue(result is SandboxResult.Error)
        val error = result as SandboxResult.Error
        assertTrue(error.message.isNotEmpty())
    }

    @Test
    fun testIsolateReset() = runBlocking {
        sandbox.execute("var globalState = 100;")
        val resetResult = sandbox.reset()
        assertTrue(resetResult.isSuccess)

        // After reset, previous state should not persist
        val checkResult = sandbox.execute("typeof globalState")
        assertTrue(checkResult is SandboxResult.Success)
        assertEquals("undefined", (checkResult as SandboxResult.Success).output)
    }
}
