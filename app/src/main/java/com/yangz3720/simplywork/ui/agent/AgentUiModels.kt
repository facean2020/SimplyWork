package com.yangz3720.simplywork.ui.agent

import com.yangz3720.simplywork.core.cloud.CloudFolder
import com.yangz3720.simplywork.core.cloud.OneDriveAccount
import com.yangz3720.simplywork.core.cloud.SyncState
import com.yangz3720.simplywork.core.agent.ContextBudgetPlan
import com.yangz3720.simplywork.core.session.Project
import com.yangz3720.simplywork.core.session.ProjectLogic
import com.yangz3720.simplywork.core.session.SessionRecord
import com.yangz3720.simplywork.core.session.TodoItem
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount

/**
 * UI message model -- the projection of session DAG nodes onto the UI (agent-core-design.md §2.1 -> UI).
 * One-to-one mapping (SystemPrompt(base) excepted); a tool call and its result are merged via [toolCallId] into
 * the same activity entry.
 */
data class UiMessage(
    val id: String,
    val kind: UiMessageKind,
    val text: String,
    val createdAt: Long,
    /** Business error styling: audit denial / failed node / error tool result. */
    val isError: Boolean = false,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    /** ToolResult content, joined into the call entry (collapsed by default). */
    val toolResult: String? = null,
    val toolResultIsError: Boolean = false,

    /** Branch info (UserPrompt only): the [branchIndex]-th branch at this position / out of [branchCount] branches. */
    val branchIndex: Int = 0,
    val branchCount: Int = 1,

    /**
     * v1.5: what this tool call did to workspace files (fs_write/fs_move/fs_delete).
     * Attached by [ConversationRenderer] keyed by toolCallId, rendered under the tool entry as a diff card.
     */
    val fileChange: FileChange? = null
)

enum class UiMessageKind {
    /** User message (right-aligned bubble). */
    User,

    /** Assistant body text (no bubble, pure typography). */
    Assistant,

    /** Tool activity timeline entry (the signature element: status dot + name + expandable args/result). */
    ToolActivity,

    /** User steer while a run is in flight (Steer). */
    Steer,

    /** Follow-up injected by the completeness guard (SystemPrompt kind=follow_up). */
    FollowUp,

    /** Episode summary produced by context compaction. */
    Summary,

    /** System-level note (the UI rendering of explicit anomalies such as a missing payload). */
    SystemNote
}

/** HITL approval request (the UI-side projection of AuditApprovalHandler, Q1). */
data class ApprovalRequest(
    val toolName: String,
    val argsJson: String
)

/**
 * Runtime permission request (the UI-side projection of `SystemPermissionRequester`, v1.2 / P4):
 * infrastructure only reports which permissions are missing and the rationale; what to show and when to show it is decided by the UI layer.
 */
data class PermissionRequest(
    val permissions: List<String>,
    val rationale: String
)

/**
 * One screen of the agent UI: conversation projection + run state + sessions.
 * MVI single state (architecture.md `AgentViewModel`).
 */
data class AgentUiState(
    val sessions: List<SessionRecord> = emptyList(),
    val currentSessionId: String? = null,
    /** Current session title; null = a new conversation not yet persisted. */
    val currentTitle: String? = null,
    val messages: List<UiMessage> = emptyList(),
    val running: Boolean = false,
    val iteration: Int = 0,
    /** Human-readable reason for the most recent Failed; when non-null the UI shows a retry entry point (Q2 outer-loop restart). */
    val runError: String? = null,
    val todos: List<TodoItem> = emptyList(),

    /** Provider account list (each keeps its own API key; the data source for the top-bar picker). */
    val providerAccounts: List<ProviderAccount> = emptyList(),
    /** Currently selected provider + model (shown in the conversation screen's top bar and switched live). */
    val activeProviderId: String? = null,
    val activeModel: String = "",

    /** Project list + current project (v1.3): session filtering + the default directory for the fs_* sandbox. */
    val projects: List<Project> = emptyList(),
    val currentProjectId: String = ProjectLogic.DEFAULT_PROJECT_ID,

    /** OneDrive (v1.4): account, configuration-ready flag and sync state. */
    val oneDriveAccount: OneDriveAccount? = null,
    val oneDriveConfigured: Boolean = false,
    val syncState: SyncState = SyncState.Idle,

    /** Current level of the cloud folder picker (the level stack is maintained by the sheet itself; this only holds what to render). */
    val cloudFolders: List<CloudFolder> = emptyList(),
    val cloudBusy: Boolean = false,
    val cloudError: String? = null,

    /** Project file manager (v1.5): current level's path, entries, busy and error. */
    val filePath: String = "",
    val fileEntries: List<AgentWorkspace.Entry> = emptyList(),
    val filesBusy: Boolean = false,
    val filesError: String? = null,

    /** File preview (null = closed). */
    val filePreview: FilePreview? = null,

    /**
     * Files **newly created** in the current session (the small card at the bottom): tapping a row jumps straight to that file's preview.
     * Only contains files that still exist right now -- anything deleted or moved away later does not linger on the card.
     */
    val newFiles: List<FileChange> = emptyList(),

    /**
     * v1.1: auto-approve non-destructive calls (a user toggle). Destructive calls are unaffected by it and always prompt.
     */
    val autoApproveNonDestructive: Boolean = true,

    /**
     * v1.1: the current model's context budget + where it came from (the settings page shows it faithfully, see `ContextBudgetPlan`).
     */
    val contextBudget: ContextBudgetPlan? = null,

    /**
     * v1.1: the context token count actually sent in the most recent request (= the promptTokens the model returned).
     * That is the real "current context usage"; a cumulative total only grows monotonically and cannot tell you whether compaction is due.
     */
    val contextPromptTokens: Int = 0,

    val modelConfigured: Boolean = true
)

/**
 * Contents of the file preview page (v1.5).
 *
 * [text] being null means **no content is displayed**, and the reason lives in [notice]: binary, read failure, etc.
 * Separating "the content is empty" from "there is no content to show" is deliberate -- the former renders a blank page, the latter must state a reason.
 */
data class FilePreview(
    val path: String,
    val markdown: Boolean = false,
    val text: String? = null,
    val notice: String? = null,
    val truncated: Boolean = false,
    val sizeBytes: Long = 0L
)