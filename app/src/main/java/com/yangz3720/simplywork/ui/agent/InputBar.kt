package com.yangz3720.simplywork.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yangz3720.simplywork.core.session.TodoItem
import com.yangz3720.simplywork.core.session.TodoStatus

/**
 * 底部输入区：运行状态条 / 失败重试条（Q2）/ 编辑历史提问条 → 待办入口 → 输入框。
 * 运行中输入即 Steering（design §6 节点 B：转向在下一步循环生效）。
 */
@Composable
fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    running: Boolean,
    iteration: Int,
    runError: String?,
    todos: List<TodoItem>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onShowTodos: () -> Unit,
    editing: UiMessage? = null,
    onCancelEdit: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val openTodos = todos.count { it.isOpen }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // 异形屏（大 R 角）额外余量：输入条抬离手势区，视觉不贴边。
            .padding(bottom = 6.dp)
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (running) {
            RunStatusRow(iteration = iteration, onStop = onStop)
        }
        if (runError != null && !running) {
            ErrorBanner(text = runError, onRetry = onRetry)
        }
        if (editing != null) {
            EditBanner(onCancel = onCancelEdit)
        }
        if (openTodos > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "待办 $openTodos 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = onShowTodos) {
                    Text("查看", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = when {
                            editing != null -> "修改你的提问…"
                            running -> "运行中…输入将作为转向指令"
                            else -> "描述你的目标"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                shape = RoundedCornerShape(22.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions.Default,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(Modifier.size(8.dp))
            IconButton(
                onClick = { onSend(draft); onDraftChange("") },
                enabled = draft.isNotBlank()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 运行状态条：步骤指示 + 停止。 */
@Composable
private fun RunStatusRow(iteration: Int, onStop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = if (iteration > 0) "第 $iteration 步 · 运行中" else "思考中…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onStop) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(2.dp))
            Text("停止", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 失败横幅：显式错误 + 重试（Q2 循环外层重启）。 */
@Composable
private fun ErrorBanner(text: String, onRetry: () -> Unit) {    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.07f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text("重试", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 编辑历史提问横幅：显式声明分支语义（发送后旧对话保留为新分支旁支）。 */
@Composable
private fun EditBanner(onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "正在编辑历史提问 · 发送后将从该处开启新分支",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) {
                Text("取消", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ----- 空态 -----
/**
 * 空态：欢迎语 + 示例（不做接入提示——配置入口在顶栏设置齿轮）。
 */
@Composable
fun EmptyState(
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "今天，\n要做什么？",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = "描述一个目标，我来安排步骤、调用系统工具并逐项完成。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(20.dp))
        SuggestionRow("把「买牛奶」记进待办", onSuggestion)
        SuggestionRow("发一条通知：该喝水了", onSuggestion)
        SuggestionRow("打开设置看看日历权限", onSuggestion)
    }
}

@Composable
private fun SuggestionRow(text: String, onSelect: (String) -> Unit) {
    TextButton(onClick = { onSelect(text) }) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ----- Q1：HITL 审批弹窗 -----

@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("需要你的确认", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    text = "助手请求执行会直接作用于系统的操作：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = request.toolName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = request.argsJson.ifBlank { "{}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text("允许", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("拒绝", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

// ----- 待办面板（State Memory 投影） -----

@Composable
fun TodoSheet(todos: List<TodoItem>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Text("待办", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.size(10.dp))
        if (todos.isEmpty()) {
            Text(
                text = "当前会话还没有待办。对助手说「把……记进待办」即可。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            todos.forEach { todo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(status = todo.status)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = todo.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (todo.status == TodoStatus.done || todo.status == TodoStatus.cancelled)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onBackground,
                        textDecoration = if (todo.status == TodoStatus.done) TextDecoration.LineThrough else null
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = todo.status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun StatusDot(status: TodoStatus) {
    val color = when (status) {
        TodoStatus.in_progress -> MaterialTheme.colorScheme.primary
        TodoStatus.done -> MaterialTheme.colorScheme.onSurfaceVariant
        TodoStatus.cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
        TodoStatus.pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .then(
                if (status == TodoStatus.in_progress) Modifier.background(color, RoundedCornerShape(4.dp))
                else Modifier
                    .size(8.dp)
                    .background(color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            )
    )
}