package com.yangz3720.simplywork.ui.agent

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 破坏性删除的统一确认弹窗（项目与会话共用）。措辞交给调用方 —— 两处"删掉的是什么、
 * 还有哪些东西会跟着没"差别很大，弹窗只负责"必须先确认"这一件事。
 *
 * [onConfirm] 为 null 时退化为**告知**弹窗（只有一个"知道了"）：用于"默认项目不可删除"
 * 这类点了也没用的情况 —— 静默不响应比说清楚更让人困惑。
 */
@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    body: String,
    onConfirm: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            if (onConfirm == null) {
                TextButton(onClick = onDismiss) {
                    Text("知道了", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                TextButton(onClick = onConfirm) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = if (onConfirm == null) {
            null
        } else {
            {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}
