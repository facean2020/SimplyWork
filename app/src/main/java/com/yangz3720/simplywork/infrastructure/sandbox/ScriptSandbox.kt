package com.yangz3720.simplywork.infrastructure.sandbox

import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxConfig
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxResult
import java.io.Closeable

/**
 * Isolated memory script execution sandbox for data glue, JSON processing, and pure functions.
 */
interface ScriptSandbox : Closeable {

    /** Returns whether the underlying engine isolate is active and ready */
    val isInitialized: Boolean

    /**
     * Executes a JavaScript script/expression with optional pre-injected variables.
     * Enforces strict timeout and memory quotas.
     *
     * @param script The JavaScript code snippet to execute.
     * @param context Key-value bindings made available as global/local variables in the script.
     * @param config Execution limits (timeout, memory heap size).
     * @return [SandboxResult] indicating success, error, timeout, or OOM.
     */
    suspend fun execute(
        script: String,
        context: Map<String, Any?> = emptyMap(),
        config: SandboxConfig = SandboxConfig.STRICT_DEFAULT
    ): SandboxResult

    /**
     * Resets or recreates the execution isolate to clear any leaked state or memory.
     */
    suspend fun reset(): Result<Unit>
}
