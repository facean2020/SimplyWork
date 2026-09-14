package com.yangz3720.simplywork.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 消息流（签名元素）：工具活动时间线 —— 调用/结果渲染为带状态点的左侧活动条目，
 * 助手正文无气泡纯排版，用户消息保留右对齐气泡形成方向对比。
 */
@Composable
fun ConversationList(
    messages: List<UiMessage>,
    modifier: Modifier = Modifier,
    onEditUser: (UiMessage) -> Unit = {},
    onSwitchBranch: (UiMessage, Int) -> Unit = { _, _ -> }
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // 新内容到达时贴底滚动。
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages) { message ->
            MessageItem(
                message = message,
                expanded = expanded[message.toolCallId ?: message.id] == true,
                onToggleExpand = {
                    val key = message.toolCallId ?: message.id
                    expanded[key] = !(expanded[key] ?: false)
                },
                onEditUser = onEditUser,
                onSwitchBranch = onSwitchBranch
            )
        }
    }
}

@Composable
private fun MessageItem(
    message: UiMessage,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onEditUser: (UiMessage) -> Unit,
    onSwitchBranch: (UiMessage, Int) -> Unit
) {
    when (message.kind) {
        UiMessageKind.User -> UserBubble(message, onEditUser, onSwitchBranch)
        UiMessageKind.Assistant -> AssistantText(message)
        UiMessageKind.ToolActivity -> ToolActivityItem(message, expanded, onToggleExpand)
        UiMessageKind.Steer -> CenterPill(prefix = "转向", text = message.text)
        UiMessageKind.FollowUp -> CenterPill(prefix = "守卫跟进", text = message.text)
        UiMessageKind.Summary -> SummaryItem(message)
        UiMessageKind.SystemNote -> ErrorNote(message)
    }
}

// ----- 用户：右对齐气泡（长按编辑 · 分支切换） -----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: UiMessage,
    onEditUser: (UiMessage) -> Unit,
    onSwitchBranch: (UiMessage, Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onEditUser(message) }
                )
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
        // 分支导航（VSCode-Copilot 式）：编辑重发后同位置出现多个提问分支。
        if (message.branchCount > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onSwitchBranch(message, -1) },
                    enabled = message.branchIndex > 0,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "上一个分支",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${message.branchIndex + 1}/${message.branchCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = { onSwitchBranch(message, 1) },
                    enabled = message.branchIndex < message.branchCount - 1,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = "下一个分支",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ----- 助手：无气泡纯排版（Markdown 渲染） -----

/**
 * 标题字号：库默认把 h1..h6 映射到 displayLarge/Medium/Small（手机上 36~57sp，过大）。
 * 这里压到适合聊天的梯度，仍保留大中小可辨（正文 bodyLarge=16sp）。
 */
private fun chatHeading(base: TextStyle, size: androidx.compose.ui.unit.TextUnit): TextStyle =
    base.copy(fontSize = size, fontWeight = FontWeight.Bold)

@Composable
private fun AssistantText(message: UiMessage) {
    if (message.isError) {
        // 业务错误保持纯文本 + 错误色。
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        )
    } else {
        val clipboard = LocalClipboardManager.current
        var copied by remember(message.id) { mutableStateOf(false) }
        LaunchedEffect(copied) {
            if (copied) {
                delay(1600)
                copied = false
            }
        }
        val body = MaterialTheme.typography.bodyLarge
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            // Markdown 渲染（mikepenz multiplatform-markdown-renderer，M3 主题对齐）。
            Markdown(
                content = message.text,
                colors = markdownColor(text = MaterialTheme.colorScheme.onBackground),
                typography = markdownTypography(
                    text = body,
                    paragraph = body,
                    h1 = chatHeading(body, 24.sp),
                    h2 = chatHeading(body, 20.sp),
                    h3 = chatHeading(body, 18.sp),
                    h4 = chatHeading(body, 16.sp),
                    h5 = chatHeading(body, 15.sp),
                    h6 = chatHeading(body, 14.sp)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(2.dp))
            // 复制整条回复（无图标依赖：material-icons-core 不含 ContentCopy）。
            Text(
                text = if (copied) "已复制" else "复制",
                style = MaterialTheme.typography.labelMedium,
                color = if (copied) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(message.text))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

// ----- 工具活动时间线条目（签名元素） -----

@Composable
private fun ToolActivityItem(message: UiMessage, expanded: Boolean, onToggleExpand: () -> Unit) {
    val hasDetail = !message.toolArgs.isNullOrBlank() || !message.toolResult.isNullOrBlank()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasDetail) Modifier.clickable { onToggleExpand() } else Modifier)
                .padding(vertical = 2.dp)
        ) {
            // 状态点：进行中（空心）/ 完成（墨青）/ 失败（赭红）
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .then(
                        when {
                            message.toolResultIsError ->
                                Modifier.background(MaterialTheme.colorScheme.error, CircleShape)
                            message.toolResult != null ->
                                Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                            else ->
                                Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    CircleShape
                                )
                        }
                    )
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = message.toolName ?: "未知调用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (message.toolResultIsError) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "失败",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.weight(1f))
            if (hasDetail) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded && hasDetail) {
            Column(modifier = Modifier.padding(start = 18.dp, top = 6.dp)) {
                message.toolArgs?.takeIf { it.isNotBlank() }?.let { args ->
                    Text(
                        text = "参数",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = args,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                message.toolResult?.takeIf { it.isNotBlank() }?.let { result ->
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = result,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.toolResultIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // v1.5：这次调用对文件做了什么（Codex 式 diff 卡片）。与"结果"展开项不同层级：
        // 结果回答"工具说了什么"，卡片回答"文件现在变成什么样"。
        message.fileChange?.let { change ->
            Spacer(Modifier.size(6.dp))
            Column(modifier = Modifier.padding(start = 18.dp)) { ToolChangeCard(change) }
        }
    }
}

// ----- 转向 / 守卫跟进：居中小条 -----

@Composable
private fun CenterPill(prefix: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = "$prefix · $text",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

// ----- 摘要（Episode 压缩产物） -----

@Composable
private fun SummaryItem(message: UiMessage) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "上下文已压缩",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ErrorNote(message: UiMessage) {
    Text(
        text = message.text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
    )
}