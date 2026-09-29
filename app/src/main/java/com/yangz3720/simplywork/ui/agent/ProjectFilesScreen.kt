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
 * Full-screen list row height. Same discipline as OneDriveSheet (fixed row height + the whole row tappable): the touch target is never smaller than 48dp.
 */
private val ENTRY_ROW_HEIGHT = 48.dp

/**
 * The "project" file manager (v1.5): **read-only** browsing of the current project's sandbox directory.
 *
 * Read-only is deliberate: the only entry point that changes workspace files is the agent's fs_* tools, so "file contents" and the "change
 * log" are naturally from one source -- this screen will never show one part changed by the App and another by the agent, two contradictory truths.
 * Tapping a file opens the preview (Markdown rendering in [FilePreviewSheet]); no rename/delete/edit.
 *
 * The scroll structure follows the lesson learned from OneDriveSheet: **only the list scrolls** ([LazyColumn] owns the remaining height),
 * with no outer verticalScroll -- nested scrolling in the same direction crashes.
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

/** Current-level path + go up one level (no back button at the root: a button that cannot move is just noise). */
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

/** One entry row: directories are tappable into the next level, files open a preview, and the size sits on the right (there is no such thing as a directory's "size"). */
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

/** File size: directories show no size, so this is only ever used on file rows. */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
}
