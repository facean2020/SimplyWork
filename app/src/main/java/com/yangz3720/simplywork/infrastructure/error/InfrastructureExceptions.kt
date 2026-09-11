package com.yangz3720.simplywork.infrastructure.error

/**
 * Root exception hierarchy for all infrastructure operations.
 */
sealed class InfrastructureException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Exceptions arising during LLM invocation (Model Provider).
     */
    sealed class Model(message: String, cause: Throwable? = null) : InfrastructureException(message, cause) {
        class AuthenticationFailed(message: String) : Model("Authentication failed: $message")
        class RateLimitExceeded(val retryAfterMs: Long?, message: String) : Model("Rate limit exceeded: $message")
        class QuotaExceeded(message: String) : Model("API quota exceeded: $message")
        class ContextWindowExceeded(val maxTokens: Int?, message: String) : Model("Context window exceeded: $message")
        class ServerError(val statusCode: Int, message: String) : Model("Model server error ($statusCode): $message")
        class NetworkTimeout(message: String, cause: Throwable? = null) : Model("Network timeout: $message", cause)
        class InvalidResponse(message: String, cause: Throwable? = null) : Model("Invalid model response: $message", cause)
        class LocalEngineNotLoaded(message: String) : Model("Local engine not loaded: $message")
    }

    /**
     * Exceptions arising during Script Sandbox execution.
     */
    sealed class Sandbox(message: String, cause: Throwable? = null) : InfrastructureException(message, cause) {
        class MemoryExceeded(val maxBytes: Long) : Sandbox("Sandbox exceeded memory limit of $maxBytes bytes")
        class ExecutionTimeout(val timeoutMs: Long) : Sandbox("Sandbox timed out after $timeoutMs ms")
        class ScriptSyntaxError(val detail: String) : Sandbox("JavaScript syntax error: $detail")
        class EngineUnavailable(message: String, cause: Throwable? = null) : Sandbox("JavaScript engine unavailable: $message", cause)
        class ExecutionFailed(message: String, cause: Throwable? = null) : Sandbox("Sandbox execution failed: $message", cause)
    }

    /**
     * Exceptions arising during system meta-operations (systemops layer).
     * Throwing channel for infrastructure-internal errors bubbling up (§4.3); the
     * SystemOperationRouter catches these and normalizes them into
     * [SystemOperationResult.Failed][com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult]
     * before anything reaches tool-core.
     */
    sealed class SystemOperation(message: String, cause: Throwable? = null) : InfrastructureException(message, cause) {
        class IntentLaunchFailed(message: String, cause: Throwable? = null) : SystemOperation("Intent launch failed: $message", cause)
        class ClipboardOperationFailed(message: String, cause: Throwable? = null) : SystemOperation("Clipboard operation failed: $message", cause)
        class CalendarWriteFailed(message: String, cause: Throwable? = null) : SystemOperation("Calendar write failed: $message", cause)
        class NotificationPostFailed(message: String, cause: Throwable? = null) : SystemOperation("Notification post failed: $message", cause)
    }

    /**
     * Exceptions arising during session persistence (session-core: SQLite structure
     * store + JSONL payload store, agent-core-design.md §2 / §9).
     */
    sealed class Session(message: String, cause: Throwable? = null) : InfrastructureException(message, cause) {
        /** SQLite / JSONL read-write failure. */
        class StoreIo(message: String, cause: Throwable? = null) : Session("Session store I/O failed: $message", cause)

        /** A node row exists but its payload line is missing (P2: explicit, never silent-empty). */
        class PayloadMissing(val nodeId: String) : Session("Payload missing for node $nodeId")

        /** Payload encode/decode failure. */
        class SerializeFailed(message: String, cause: Throwable? = null) : Session("Session payload serialize failed: $message", cause)
    }
}
