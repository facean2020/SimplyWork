package com.yangz3720.simplywork.ui.agent

import com.yangz3720.simplywork.core.session.SessionRecord
import com.yangz3720.simplywork.core.session.TodoItem
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
    val branchCount: Int = 1
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

    val modelConfigured: Boolean = true
)