package com.yangz3720.simplywork.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.core.workspace.FileChangeKind
import com.yangz3720.simplywork.core.workspace.TextDiff

/**
 * Row height of the small "new files" card at the bottom. Same discipline as OneDriveSheet: the row height is fixed
 * and the list cap is an integer multiple of it, so the list shows whole rows or scrolls whole rows away (an estimated row height cuts rows in half -- a trap we really did hit).
 */
private val FILE_ROW_HEIGHT = 40.dp

/**
 * Codex-style change card (v1.5): attached below the tool entry that triggered this change.
 *
 * Collapsed it is one line, answering "which file changed, how many lines moved"; only when opened does it lay out the
 * diff lines. The `+N −M` in the summary is a really computed line count -- when it cannot be computed (diff region too
 * large, baseline truncated) we **render no numbers**, rather than passing off the whole new content as added lines.
 */
@Composable
fun ToolChangeCard(change: FileChange) {
    // Remember expansion state by toolCallId: when the list reuses entries, another card's expansion state does not leak in.
    var expanded by remember(change.toolCallId, change.path) { mutableStateOf(false) }
    val hasDiff = change.diff.isNotEmpty()
    val path = change.toPath?.let { "${change.path} → $it" } ?: change.path

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasDiff) Modifier.clickable { expanded = !expanded } else Modifier)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = kindLabel(change.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ChangeCounts(change)
                if (hasDiff) {
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起改动" else "展开改动",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            AnimatedVisibility(visible = expanded && hasDiff) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    change.diff.forEach { line -> DiffLine(line) }
                    if (change.omittedLines > 0) {
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = "省略 ${change.omittedLines} 行",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** `+added −removed`; when both sides are 0 (the same content was written) no number is rendered at all. */
@Composable
private fun ChangeCounts(change: FileChange) {
    val added = change.addedLines
    val removed = change.removedLines
    if (added == null || removed == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                text = "+$added",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (removed > 0) {
            if (added > 0) Spacer(Modifier.size(6.dp))
            Text(
                text = "−$removed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** One diff line: prefix character + monospace body. Single line, no wrapping -- wrapping would destroy the diff's sense of line numbers entirely. */
@Composable
private fun DiffLine(line: TextDiff.Line) {
    val color = when (line.kind) {
        TextDiff.LineKind.Added -> MaterialTheme.colorScheme.primary
        TextDiff.LineKind.Removed -> MaterialTheme.colorScheme.error
        TextDiff.LineKind.Context -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "${line.prefix}${line.text}",
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The "new files" card at the bottom of the session (v1.5, the wrap-up card of work agents like Codex): lists only the
 * files the agent actually **created**; tapping a row jumps to that file's preview page.
 *
 * It divides labour differently from the diff card under a tool entry: the diff answers "what changed", this card answers "what now exists",
 * so it is a **springboard** into new files, not a change log (the log is already attached at each of its own positions).
 */
@Composable
fun NewFilesCard(files: List<FileChange>, onOpen: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "新增 ${files.size} 个文件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.size(2.dp))
            LazyColumn(modifier = Modifier.heightIn(max = FILE_ROW_HEIGHT * 3)) {
                items(files, key = { it.path }) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FILE_ROW_HEIGHT)
                            .clickable { onOpen(file.path) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = file.path,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun kindLabel(kind: FileChangeKind): String = when (kind) {
    FileChangeKind.Created -> "新建"
    FileChangeKind.Updated -> "更新"
    FileChangeKind.Appended -> "追加"
    FileChangeKind.Moved -> "重命名"
    FileChangeKind.Deleted -> "删除"
}
