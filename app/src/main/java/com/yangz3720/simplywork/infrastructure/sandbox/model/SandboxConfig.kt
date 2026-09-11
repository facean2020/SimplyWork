package com.yangz3720.simplywork.infrastructure.sandbox.model

/**
 * Execution limits and quotas for ScriptSandbox.
 */
data class SandboxConfig(
    val maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val enableConsoleCapture: Boolean = false
) {
    companion object {
        /** 8MB strict memory boundary for data glue functions */
        const val DEFAULT_MAX_MEMORY_BYTES = 8 * 1024 * 1024L

        /** 200ms hard quota for glue functions */
        const val DEFAULT_TIMEOUT_MS = 200L

        val STRICT_DEFAULT = SandboxConfig()
    }
}

/**
 * Outcome of executing a JavaScript script within the sandbox.
 */
sealed interface SandboxResult {

    /**
     * Successfully executed with returned value formatted as String/JSON.
     */
    data class Success(
        val output: String,
        val executionTimeMs: Long,
        val consoleLogs: List<String> = emptyList()
    ) : SandboxResult

    /**
     * Script syntax or runtime evaluation error.
     */
    data class Error(
        val message: String,
        val stackTrace: String? = null,
        val executionTimeMs: Long
    ) : SandboxResult

    /**
     * Execution aborted due to exceeding [SandboxConfig.timeoutMs].
     */
    data class Timeout(
        val timeoutMs: Long,
        val executionTimeMs: Long
    ) : SandboxResult

    /**
     * Execution aborted due to exceeding [SandboxConfig.maxMemoryBytes].
     */
    data class MemoryLimitExceeded(
        val maxMemoryBytes: Long,
        val executionTimeMs: Long
    ) : SandboxResult

    /**
     * The JS engine (AndroidX JavaScriptEngine service) could not be bound or the
     * isolate could not be created — platform-level failure, not a script problem
     * (P2: explicit, never silent).
     */
    data class EngineUnavailable(
        val message: String,
        val executionTimeMs: Long
    ) : SandboxResult
}
