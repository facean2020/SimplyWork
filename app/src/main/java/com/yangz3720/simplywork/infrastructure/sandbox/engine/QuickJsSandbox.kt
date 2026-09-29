package com.yangz3720.simplywork.infrastructure.sandbox.engine

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox as AndroidXJsSandbox
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.sandbox.ScriptSandbox
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxConfig
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxResult
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lightweight, process-isolated JavaScript Sandbox powered by AndroidX JavaScriptEngine (QuickJS).
 * Enforces strict memory quotas (8MB) and timeouts (200ms) to ensure safety for LLM-generated glue code.
 */
class QuickJsSandbox(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : ScriptSandbox {

    private val tag = "QuickJsSandbox"
    private var currentIsolate: JavaScriptIsolate? = null
    private val initMutex = Mutex()

    /**
     * Execution mutex (v1.6): this class has only one shared isolate, and the way a [execute] timeout is
     * handled is `isolate.close()` + rebuild -- i.e. **killing the entire isolate**. That means two concurrent
     * `execute` calls murder each other: as soon as either one times out, the script the other one is running
     * gets taken down with it (and its future never returns normally again). With the lock, concurrent calls become queueing execution, and the timeout semantics are safe again.
     *
     * Lock order is always execMutex -> initMutex; the isolate rebuild inside [execute] goes through the private
     * [recreateIsolate] instead of the public [reset], otherwise it would deadlock on the same non-reentrant lock.
     */
    private val execMutex = Mutex()

    override val isInitialized: Boolean get() = currentIsolate != null

    private suspend fun ensureInitialized(config: SandboxConfig) {
        if (currentIsolate != null) return
        initMutex.withLock {
            if (currentIsolate != null) return
            var lastFailure: Exception? = null
            repeat(INIT_ATTEMPTS) { attempt ->
                if (currentIsolate != null) return@repeat
                try {
                    // Process-level shared binding (AndroidX JavaScriptSandbox is designed
                    // as one connection per process — rebinding after close() races its
                    // async teardown and throws "Binding to already bound service").
                    val sandbox = bindSharedSandbox(context)
                    val startupParams = IsolateStartupParameters()
                    if (sandbox.isFeatureSupported(AndroidXJsSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                        startupParams.maxHeapSizeBytes = config.maxMemoryBytes
                    }
                    currentIsolate = sandbox.createIsolate(startupParams)
                    AgentLogger.d(tag) { "JavaScript Isolate initialized with maxHeap=${config.maxMemoryBytes} bytes" }
                } catch (e: Exception) {
                    lastFailure = e
                    runCatching { currentIsolate?.close() }
                    currentIsolate = null
                    // The shared connection itself may be dead (service killed) — drop it
                    // so the next attempt binds fresh.
                    invalidateSharedSandbox()
                    delay(300L * (attempt + 1))
                }
            }
            if (currentIsolate == null) {
                throw InfrastructureException.Sandbox.EngineUnavailable(
                    lastFailure?.message ?: "JS sandbox service did not connect"
                )
            }
        }
    }

    /**
     * Executes while holding [execMutex]: only one script runs at any moment (see [execMutex]).
     */
    override suspend fun execute(
        script: String,
        context: Map<String, Any?>,
        config: SandboxConfig
    ): SandboxResult = execMutex.withLock { withContext(dispatcher) { runIsolated(script, context, config) } }

    private suspend fun runIsolated(
        script: String,
        context: Map<String, Any?>,
        config: SandboxConfig
    ): SandboxResult {
        val startTime = System.currentTimeMillis()
        AgentLogger.d(tag) { "Executing script (timeout=${config.timeoutMs}ms, heap=${config.maxMemoryBytes}B):\n$script" }

        return try {
            ensureInitialized(config)
            val isolate = checkNotNull(currentIsolate) { "JavaScript Isolate not available" }

            val preamble = buildContextPreamble(context)
            // eval semantics: the script is a sequence of expressions whose LAST value is
            // the result (glue-code convention). Wrapping in an IIFE without return made
            // every result `undefined` on device. The script string is passed as a JSON-
            // escaped JS string literal to eval().
            val scriptLiteral = formatValueForJs(script)
            val wrappedScript = """
                (function() {
                    $preamble
                    var __evalResult = eval($scriptLiteral);
                    if (__evalResult === undefined) return "undefined";
                    if (__evalResult === null) return "null";
                    return typeof __evalResult === 'object' ? JSON.stringify(__evalResult) : String(__evalResult);
                })();
            """.trimIndent()

            // runInterruptible: a blocking Future.get() must be interruptible, otherwise
            // coroutine cancellation (the timeout below) can never break it — the very
            // bug that hung the first on-device acceptance run for 15 minutes.
            val rawResult = withTimeoutOrNull(config.timeoutMs) {
                val evalFuture = isolate.evaluateJavaScriptAsync(wrappedScript)
                runInterruptible(Dispatchers.IO) { evalFuture.get() }
            }

            val elapsed = System.currentTimeMillis() - startTime

            if (rawResult == null) {
                AgentLogger.w(tag) { "Script execution exceeded timeout of ${config.timeoutMs}ms. Cancelling isolate..." }
                // The runaway script is still executing inside the QuickJS isolate —
                // close() terminates it; the isolate is dead afterwards, so rebuild it.
                runCatching { isolate.close() }
                recreateIsolate()
                return SandboxResult.Timeout(timeoutMs = config.timeoutMs, executionTimeMs = elapsed)
            }

            AgentLogger.d(tag) { "Script completed in ${elapsed}ms: $rawResult" }
            SandboxResult.Success(
                output = rawResult,
                executionTimeMs = elapsed
            )
        } catch (e: TimeoutCancellationException) {
            val elapsed = System.currentTimeMillis() - startTime
            AgentLogger.w(tag) { "Coroutine timeout after ${elapsed}ms. Cancelling isolate..." }
            currentIsolate?.let { runCatching { it.close() } }
            recreateIsolate()
            SandboxResult.Timeout(timeoutMs = config.timeoutMs, executionTimeMs = elapsed)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            val message = e.message.orEmpty()
            AgentLogger.e(tag, e) { "Script execution failed after ${elapsed}ms: $message" }

            if (message.contains("MemoryLimitExceeded", ignoreCase = true) ||
                message.contains("heap", ignoreCase = true) ||
                message.contains("out of memory", ignoreCase = true)
            ) {
                recreateIsolate()
                SandboxResult.MemoryLimitExceeded(maxMemoryBytes = config.maxMemoryBytes, executionTimeMs = elapsed)
            } else {
                SandboxResult.Error(
                    message = message.ifEmpty { e.javaClass.simpleName },
                    stackTrace = e.stackTraceToString(),
                    executionTimeMs = elapsed
                )
            }
        }
    }

    /**
     * Rebuilds the isolate. The public entry point takes [execMutex] first, so it can't pull the isolate out
     * from under a script that is executing; the inside of [execute] (already holding the lock) uses the private [recreateIsolate].
     */
    override suspend fun reset(): Result<Unit> = execMutex.withLock { recreateIsolate() }

    private suspend fun recreateIsolate(): Result<Unit> = initMutex.withLock {
        runCatching {
            AgentLogger.d(tag) { "Resetting JavaScript isolate..." }
            currentIsolate?.close()
            currentIsolate = null
            val sandbox = bindSharedSandbox(context)
            val startupParams = IsolateStartupParameters().apply {
                if (sandbox.isFeatureSupported(AndroidXJsSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                    maxHeapSizeBytes = SandboxConfig.DEFAULT_MAX_MEMORY_BYTES
                }
            }
            currentIsolate = sandbox.createIsolate(startupParams)
            AgentLogger.d(tag) { "JavaScript isolate recreated." }
        }
    }

    override fun close() {
        runCatching {
            // Only this wrapper's isolate dies — the process-level shared sandbox
            // connection stays alive (one binding per process by design).
            currentIsolate?.close()
            currentIsolate = null
            AgentLogger.d(tag) { "QuickJsSandbox closed (isolate released, shared engine kept)." }
        }
    }

    // ----- shared engine binding (process-level singleton) -----

    private suspend fun bindSharedSandbox(context: Context): AndroidXJsSandbox =
        sharedSandboxMutex.withLock {
            sharedSandbox?.let { return it }
            val sandboxFuture = AndroidXJsSandbox.createConnectedInstanceAsync(context)
            // Interruptible + bounded: on some OEM builds (MIUI observed) the
            // isolated JS service process can be killed by ActivityManager
            // before the binding completes — the future never completes, and a
            // plain Future.get() would hang the caller forever.
            val sandbox = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                runInterruptible(Dispatchers.IO) { sandboxFuture.get() }
            } ?: throw IllegalStateException("Timed out after ${INIT_TIMEOUT_MS}ms binding to the JS sandbox service")
            sharedSandbox = sandbox
            sandbox
        }

    private fun invalidateSharedSandbox() {
        sharedSandbox?.let { runCatching { it.close() } }
        sharedSandbox = null
    }

    private fun buildContextPreamble(context: Map<String, Any?>): String {
        if (context.isEmpty()) return ""
        val sb = StringBuilder()
        for ((key, value) in context) {
            val jsValue = formatValueForJs(value)
            sb.append("var ").append(key).append(" = ").append(jsValue).append(";\n")
        }
        return sb.toString()
    }

    private fun formatValueForJs(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> {
            val entries = value.entries.joinToString(",") { (k, v) ->
                "\"${k.toString()}\": ${formatValueForJs(v)}"
            }
            "{$entries}"
        }
        is Collection<*> -> {
            val items = value.joinToString(",") { formatValueForJs(it) }
            "[$items]"
        }
        else -> "\"${value.toString().replace("\"", "\\\"")}\""
    }

    private companion object {
        /** Service binding timeout: the JS sandbox process can be cold-started. */
        const val INIT_TIMEOUT_MS = 5_000L

        /** Retry the binding — OEM process managers (MIUI observed) may kill the isolated service during the first bind race. */
        const val INIT_ATTEMPTS = 2

        /** Process-level shared engine: one binding per process (AndroidX design constraint). */
        private val sharedSandboxMutex = Mutex()

        @Volatile
        private var sharedSandbox: AndroidXJsSandbox? = null
    }
}
