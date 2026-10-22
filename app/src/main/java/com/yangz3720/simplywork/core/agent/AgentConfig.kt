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
    /**
     * Auto-approval (v1.1): USER_CONFIRMING tools pop a dialog asking the human every time by
     * default; once enabled, only **irreversible** calls (`RegisteredTool.destructive`,
     * currently just fs_delete) are stopped.
     *
     * Default false (most conservative, and identical to the old behaviour); the product layer
     * enables it by default, decided by `KeyringState`.
     */
    val autoApproveNonDestructive: Boolean = false,
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
            - Every user turn is prefixed with "[message time: <date> <weekday> <HH:mm> <UTC offset>]".
              That is when the user spoke — treat it as the baseline for "today/now", DST already
              resolved into the shown offset. It can be stale by the time you act, so still call
              sys_time_read before date-sensitive tool calls, and sys_time_calc instead of doing
              date arithmetic yourself — never invent an epoch timestamp.
              Use sys_location_read for location and sys_device_read for battery/network/storage.
            - Look up before you act: sys_app_list gives the real packageName that sys_app_open
              needs, and sys_calendar_query shows what is already scheduled before you add to it.
            - Tool results are explicit; when one reports a blocked status (permission,
              security, timeout), adapt or inform the user instead of retrying blindly.
              permission_required means a runtime permission is missing (the user was asked and
              did not grant it) — offer a permission-free alternative or stop, do not retry.
              security_blocked means a device restriction the user must change in system
              settings (e.g. Location switched off) — a permission request will not help.
            - Track multi-step work with the todo_write tool; keep it accurate so the
              completion guard can verify closure.
            - Waiting: when what you are waiting for happens OUTSIDE this app (another app
              writing a file, a network condition settling), use wait(seconds) to pause instead
              of re-reading state in a tight loop. Max 300s per call; longer asks are capped.
            - Scripts: js_run starts a JavaScript snippet in an isolated sandbox and returns a
              jobId immediately; js_await waits for it (with a max wait of your choosing). The
              sandbox is pure computation — no file, network or device access — so use the fs_*
              tools to touch the workspace, never a script. A finished script also wakes you
              with a js_job_finished message, so you can end your turn instead of waiting.
            - Self-operations: conversations are organized into projects. Each project owns
              its own sandbox workspace (fs_list/fs_read/fs_write/fs_mkdir operate inside the
              CURRENT project by default; fs_move/fs_delete need user confirmation). Read past
              conversations with self_session_read (mode=list or read), inspect your own config
              with self_config_read, and change it via self_config_update (model/provider,
              timeout, current project) — API keys can never be read or written, and config
              changes take effect for the NEXT run. Create projects with self_project_create
              before working on a new topic so its files and sessions stay together.
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

    /**
     * Incremental assistant text while a model turn is still streaming (v2 streaming UI).
     * Emitted per SSE delta; the UI throttles these for rendering.
     * [ModelTurn] still arrives once at the end with the assembled turn.
     */
    data class AssistantTextDelta(
        val sessionId: String,
        val iteration: Int,
        val delta: String
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
