package com.yangz3720.simplywork.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import java.util.Locale

/**
 * 全屏列表行高。与 OneDriveSheet 同一条纪律（固定行高 + 整行可点）：触控区域不小于 48dp。
 */
private val ENTRY_ROW_HEIGHT = 48.dp

/**
 * 「项目」文件管理器（v1.5）：**只读**浏览当前项目的沙箱目录。
 *
 * 只读是刻意的：改动工作区文件的唯一入口是 agent 的 fs_* 工具，所以"文件内容"和"改动
 * 记录"天然同源 —— 这里不会出现一处 App 改的、一处 agent 改的，两份互相矛盾的真相。
 * 点文件进预览（Markdown 渲染见 [FilePreviewSheet]），不做重命名/删除/编辑。
 *
 * 滚动结构沿用 OneDriveSheet 的教训：**只有列表滚**（[LazyColumn] 独占剩余高度），
 * 外层不套 verticalScroll —— 同向嵌套滚动会崩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFilesScreen(
    projectName: String,
    path: String,
    entries: List<AgentWorkspace.Entry>,
    busy: Boolean,
    error: String?,
    onOpenDir: (String) -> Unit,
    onUp: () -> Unit,
    onOpenFile: (String) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("项目文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = projectName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxWidth()) {
            PathRow(path = path, onUp = onUp)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    error != null -> item { ErrorRow(message = error, onDismiss = onDismissError) }
                    busy -> item { Hint("读取中…") }
                    entries.isEmpty() -> item { Hint("这里还没有文件") }
                    else -> items(entries, key = { it.name }) { entry ->
                        EntryRow(
                            entry = entry,
                            onOpen = {
                                val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                                if (entry.isDirectory) onOpenDir(child) else onOpenFile(child)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 当前层路径 + 上一层（根目录时不给返回键：一个点不动的按钮只是噪音）。 */
@Composable
private fun PathRow(path: String, onUp: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ENTRY_ROW_HEIGHT)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (path.isNotEmpty()) {
            IconButton(onClick = onUp, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "上一层",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text = path.ifEmpty { "根目录" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 一行条目：目录可点进下一层，文件可点开预览，右侧是大小（目录没有"大小"这回事）。 */
@Composable
private fun EntryRow(entry: AgentWorkspace.Entry, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ENTRY_ROW_HEIGHT)
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (entry.isDirectory) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = formatFileSize(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
    )
}

@Composable
private fun ErrorRow(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) { Text("知道了") }
    }
}

/** 文件大小：目录不显示大小，所以这里只在文件行用得上。 */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
}
