package com.yangz3720.simplywork.ui.agent

import com.yangz3720.simplywork.core.cloud.CloudFolder
import com.yangz3720.simplywork.core.cloud.OneDriveAccount
import com.yangz3720.simplywork.core.cloud.SyncState
import com.yangz3720.simplywork.core.session.Project
import com.yangz3720.simplywork.core.session.ProjectLogic
import com.yangz3720.simplywork.core.session.SessionRecord
import com.yangz3720.simplywork.core.session.TodoItem
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount

/**
 * UI 消息模型 — 会话 DAG 节点在界面上的投影（agent-core-design.md §2.1 → UI）。
 * 一对一映射（SystemPrompt(base) 除外）；工具调用与其结果通过 [toolCallId] 归并到
 * 同一条活动条目。
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

    /** 分支信息（UserPrompt 专用）：同一位置的第 [branchIndex] 个分支 / 共 [branchCount] 个。 */
    val branchIndex: Int = 0,
    val branchCount: Int = 1,

    /**
     * v1.5：这次工具调用对工作区文件做了什么（fs_write/fs_move/fs_delete）。
     * 由 [ConversationRenderer] 按 toolCallId 挂上，工具条目下渲染成 diff 卡片。
     */
    val fileChange: FileChange? = null
)

enum class UiMessageKind {
    /** 用户消息（右对齐气泡）。 */
    User,

    /** 助手正文（无气泡纯排版）。 */
    Assistant,

    /** 工具活动时间线条目（签名元素：状态点 + 名称 + 可展开参数/结果）。 */
    ToolActivity,

    /** 运行中用户转向（Steer）。 */
    Steer,

    /** 完成度守卫注入的 follow-up（SystemPrompt kind=follow_up）。 */
    FollowUp,

    /** 上下文压缩产出的 Episode 摘要。 */
    Summary,

    /** 系统级备注（payload 缺失等显式异常的 UI 呈现）。 */
    SystemNote
}

/** HITL 审批请求（AuditApprovalHandler 的 UI 侧投影，Q1）。 */
data class ApprovalRequest(
    val toolName: String,
    val argsJson: String
)

/**
 * 运行时权限申请请求（`SystemPermissionRequester` 的 UI 侧投影，v1.2 / P4）：
 * 基础设施只报告缺哪些权限与理由，弹什么、何时弹由 UI 层决定。
 */
data class PermissionRequest(
    val permissions: List<String>,
    val rationale: String
)

/**
 * One screen of the agent UI: conversation projection + run state + sessions.
 * MVI 单一状态（architecture.md `AgentViewModel`）。
 */
data class AgentUiState(
    val sessions: List<SessionRecord> = emptyList(),
    val currentSessionId: String? = null,
    /** 当前会话标题；null = 尚未落库的新对话。 */
    val currentTitle: String? = null,
    val messages: List<UiMessage> = emptyList(),
    val running: Boolean = false,
    val iteration: Int = 0,
    /** 最近一次 Failed 的可读原因；非空时 UI 显示重试入口（Q2 循环外层重启）。 */
    val runError: String? = null,
    val todos: List<TodoItem> = emptyList(),

    /** 服务商账号列表（各存各的密钥；顶栏选择器数据源）。 */
    val providerAccounts: List<ProviderAccount> = emptyList(),
    /** 当前选中的服务商 + 模型（对话屏顶栏显示与动态切换）。 */
    val activeProviderId: String? = null,
    val activeModel: String = "",

    /** 项目列表 + 当前项目（v1.3）：会话过滤 + fs_* 沙箱默认目录。 */
    val projects: List<Project> = emptyList(),
    val currentProjectId: String = ProjectLogic.DEFAULT_PROJECT_ID,

    /** OneDrive（v1.4）：账号、配置就绪标志与同步状态。 */
    val oneDriveAccount: OneDriveAccount? = null,
    val oneDriveConfigured: Boolean = false,
    val syncState: SyncState = SyncState.Idle,

    /** 云端文件夹选择器的当前层（层级栈由弹层自己维护，这里只放要渲染的内容）。 */
    val cloudFolders: List<CloudFolder> = emptyList(),
    val cloudBusy: Boolean = false,
    val cloudError: String? = null,

    /** 项目文件管理器（v1.5）：当前层路径、条目、忙碌与错误。 */
    val filePath: String = "",
    val fileEntries: List<AgentWorkspace.Entry> = emptyList(),
    val filesBusy: Boolean = false,
    val filesError: String? = null,

    /** 文件预览（null = 关闭）。 */
    val filePreview: FilePreview? = null,

    /**
     * 当前会话**新建**的文件（底部小卡片）：点一行即跳该文件的预览。
     * 只含此刻仍存在的文件 —— 后来被删掉/移走的不会留在卡片里。
     */
    val newFiles: List<FileChange> = emptyList(),

    val modelConfigured: Boolean = true
)

/**
 * 文件预览页的内容（v1.5）。
 *
 * [text] 为 null 表示**不显示内容**，原因在 [notice]：二进制、读取失败等。
 * 区分"内容为空"和"没有内容可显示"是刻意的 —— 前者渲染空白页，后者必须给出理由。
 */
data class FilePreview(
    val path: String,
    val markdown: Boolean = false,
    val text: String? = null,
    val notice: String? = null,
    val truncated: Boolean = false,
    val sizeBytes: Long = 0L
)