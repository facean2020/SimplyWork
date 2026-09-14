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
 * 底部「新增文件」小卡片的行高。与 OneDriveSheet 同一条纪律：行高写死、列表上限取整数倍，
 * 列表才要么整行显示、要么整行滚走（估出来的行高会切半行，那是真踩过的坑）。
 */
private val FILE_ROW_HEIGHT = 40.dp

/**
 * Codex 式改动卡片（v1.5）：贴在触发这次改动的工具条目下方。
 *
 * 折叠态只占一行，回答"改了哪个文件、动了多少行"；点开才铺差异行。摘要里的 `+N −M` 是
 * 真实算出来的行数 —— 算不出来（差异区过大、基线被截断）时**不显示数字**，而不是拿
 * 整篇新内容冒充新增行。
 */
@Composable
fun ToolChangeCard(change: FileChange) {
    // 按 toolCallId 记住展开态：列表复用条目时不会把别的卡片的展开态串过来。
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

/** `+新增 −删除`；两侧都为 0（写了同样的内容）时不渲染任何数字。 */
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

/** 一行差异：前缀字符 + 等宽正文。单行不折行 —— 折行会让 diff 的行号感彻底消失。 */
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
 * 会话底部的「新增文件」卡片（v1.5，Codex 等 work agent 的收尾卡片）：只列 agent 真正
 * **新建**的文件，点一行跳到该文件的预览页。
 *
 * 它和工具条目下的 diff 卡片分工不同：diff 回答"改了什么"，这张卡片回答"现在有什么"，
 * 所以它是新增文件的**跳板**，不是改动日志（日志已经贴在各自的位置上了）。
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
