package com.yangz3720.simplywork.core.agent

/**
 * Loop and memory configuration (agent-core-design.md §5–§8 defaults).
 */
data class AgentConfig(
    /** Working memory: max rounds kept uncompressed (§5.1). */
    val maxWorkingRounds: Int = 12,
    /** Working memory: token budget for the kept rounds (§5.1). */
    val workingTokenBudget: Int = 12_000,
    /** Compaction triggers when the assembled-context estimate exceeds this (§7.1). */
    val compactionTriggerTokens: Int = 24_000,
    /** Safety valve: hard cap on loop iterations per run (§6). */
    val maxLoopIterations: Int = 200,
    /** Safety valve: consecutive completion-guard follow-ups before forced stop (§6). */
    val maxGuardInterventions: Int = 3,
    /** Per-turn LLM timeout (§6; P5). */
    val llmTimeoutMs: Long = 120_000L,
    /** Post-tool gate truncation budget (§8.4). */
    val maxToolResultChars: Int = 8_000,
    /** Base system prompt injected once per session and used in every request. */
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    init {
        require(maxWorkingRounds >= 1) { "maxWorkingRounds must be >= 1" }
        require(maxGuardInterventions >= 1) { "maxGuardInterventions must be >= 1" }
    }

    companion object {
        val DEFAULT_SYSTEM_PROMPT: String = """
            You are SimplyWork, a lightweight personal assistant agent on Android.
            You accomplish the user's intent by reasoning step by step and, when needed,
            calling the provided tools (system meta-operations, todo list management).

            Rules:
            - Prefer tool calls over guessing: read state before acting on it.
            - Tool results are explicit; when one reports a blocked status (permission,
              security, timeout), adapt or inform the user instead of retrying blindly.
            - Track multi-step work with the todo_write tool; keep it accurate so the
              completion guard can verify closure.
            - When everything is done, answer concisely in the user's language and stop.
        """.trimIndent()
    }
}

/**
 * Events emitted by the agent loop — the only observable surface for the UI layer
 * (agent-core-design.md §6).
 */
sealed interface AgentStepEvent {
    data class LoopStarted(val sessionId: String) : AgentStepEvent

    /** Inbound messages (user prompt / steer) persisted as nodes after a queue drain. */
    data class InboundPersisted(val count: Int) : AgentStepEvent

    data class ModelTurn(
        val iteration: Int,
        val text: String,
        val toolCallCount: Int,
        val promptTokens: Int,
        val completionTokens: Int
    ) : AgentStepEvent

    data class ToolStarted(val toolName: String, val toolCallId: String) : AgentStepEvent
    data class ToolCompleted(val toolName: String, val toolCallId: String, val isError: Boolean) : AgentStepEvent
    data class ToolRejected(val toolName: String, val toolCallId: String, val reason: String) : AgentStepEvent

    data class ContextCompacted(val summaryNodeId: String, val savedTokens: Int) : AgentStepEvent
    data class CompactionSkipped(val reason: String) : AgentStepEvent

    data class FollowUpInjected(val text: String, val reason: String) : AgentStepEvent

    data class Completed(
        val sessionId: String,
        val finalText: String,
        val reason: String = COMPLETED_NATURALLY
    ) : AgentStepEvent

    data class Failed(val sessionId: String, val cause: Throwable) : AgentStepEvent

    companion object {
        const val COMPLETED_NATURALLY = "completed"
        const val COMPLETED_GUARD_EXHAUSTED = "guard_exhausted"
    }
}
