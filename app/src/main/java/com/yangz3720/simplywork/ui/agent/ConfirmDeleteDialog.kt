package com.yangz3720.simplywork.ui.agent

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The shared confirmation dialog for destructive deletion (shared by projects and sessions). The wording is left to the caller -- what gets deleted and
 * what else disappears along with it differs a lot between the two call sites, so the dialog only owns one thing: "you must confirm first".
 *
 * When [onConfirm] is null it degrades into a **notice** dialog (only a "got it" button): for cases like "the default project cannot be
 * deleted" where tapping would do nothing anyway -- being silently unresponsive is more confusing than spelling it out.
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
