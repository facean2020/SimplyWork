package com.yangz3720.simplywork.core.session

import kotlinx.serialization.Serializable

/**
 * Node type of the session DAG (agent-core-design.md §3).
 * The exact set is user-mandated: UserPrompt | SystemPrompt | ToolCall | ToolResult | Steer | Summary.
 */
@Serializable
enum class SessionNodeType {
    /** Initial or queued user prompt. Payload = [com.yangz3720.simplywork.infrastructure.model.model.Message.User]. */
    UserPrompt,

    /** Injected system prompt. Payload = {"kind":"base"|"follow_up","text":"..."} */
    SystemPrompt,

    /**
     * A full assistant turn: text and/or tool calls. Named after its primary role in the
     * loop (triggering tool calls); a plain final answer is also stored here with an
     * empty toolCalls list. Payload = [com.yangz3720.simplywork.infrastructure.model.model.Message.Assistant] JSON.
     */
    ToolCall,

    /** Result fed back for one tool call id. Payload = [com.yangz3720.simplywork.infrastructure.model.model.Message.Tool] JSON. */
    ToolResult,

    /** Mid-run user steering instruction. Payload = {"text","atLoop"}; assembled as a User message. */
    Steer,

    /** Episode summary produced by the ContextCompactor. Payload = {"text","coveredNodeIds","coveredTokenCount","strategy"} */
    Summary
}

/**
 * Lifecycle status of a node record (agent-core-design.md §3.2).
 */
@Serializable
enum class SessionNodeStatus {
    /** Recorded but not finalized (e.g. ToolCall whose execution has not completed). */
    pending,

    /** Finalized; a legitimate conversation record (business errors live in the payload). */
    success,

    /** The operation this node represents did not produce a normal result (audit denial / crash). */
    failed,

    /** Superseded (resume cleanup, future /tree pruning). */
    abandoned
}

/**
 * One node of the session DAG. The payload itself lives in the JSONL payload store
 * (agent-core-design.md §2.1); [payloadOffset]/[payloadLength] locate its line there.
 */
data class SessionNode(
    val id: String,
    val sessionId: String,
    val parentId: String?,
    val nodeType: SessionNodeType,
    val tokenCount: Int,
    val status: SessionNodeStatus,
    val createdAt: Long,
    val payloadOffset: Long = -1L,
    val payloadLength: Int = -1
)

/** Row of the `sessions` table. */
data class SessionRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val activeLeafId: String? = null,
    /**
     * Owning project (v1.3 项目工作空间). `null` = pre-v1.3 row, rendered as the default
     * project ("default") by the UI and the self-session-read tool — never a missing owner.
     */
    val projectId: String? = null
)

/**
 * Payload kinds for [SessionNodeType.SystemPrompt] nodes.
 */
enum class SystemPromptKind { base, follow_up }
