package com.yangz3720.simplywork.core.workspace

import kotlinx.serialization.Serializable

/**
 * 改动类型（v1.5 改动卡片的状态词）。UI 只负责把枚举翻成中文，判定逻辑不在 UI。
 */
@Serializable
enum class FileChangeKind { Created, Updated, Appended, Moved, Deleted }

/**
 * 一次工作区改动（v1.5）：对话流内联 diff 卡片与底部「新增文件」小卡片的唯一数据源。
 *
 * 它是**事实**不是推测：[addedLines]/[removedLines] 由写入前后两次真实内容算出，算不出来
 * （基线被读侧上限截断、或差异区过大）时是 `null` —— 卡片就只报路径不报行数，绝不用
 * 整篇新内容冒充"新增行"。agent 走 fs_* 写入，云端同步走 [AgentWorkspace.writeBytes]，
 * 后者**不**记录改动：镜像不是"agent 改了你的文件"。
 */
@Serializable
data class FileChange(
    /** 触发这次改动的工具调用 id：对话流据此把卡片贴到对应条目下。 */
    val toolCallId: String? = null,
    val sessionId: String,
    val projectId: String,
    /** 相对项目根的工作区路径。 */
    val path: String,
    val kind: FileChangeKind,
    /** 重命名后的路径（仅 [FileChangeKind.Moved]）。 */
    val toPath: String? = null,
    val addedLines: Int? = null,
    val removedLines: Int? = null,
    val diff: List<TextDiff.Line> = emptyList(),
    /** [diff] 之后省略掉的行数。 */
    val omittedLines: Int = 0,
    val atMs: Long = 0L
)
