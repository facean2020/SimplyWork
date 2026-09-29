package com.yangz3720.simplywork.ui.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yangz3720.simplywork.core.cloud.CloudFolder
import com.yangz3720.simplywork.core.cloud.OneDriveAccount
import com.yangz3720.simplywork.core.cloud.SyncState
import com.yangz3720.simplywork.core.session.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The fixed Graph item id of the cloud-drive root; the root level is browse-only and cannot be bound. */
private const val ROOT_ID = "root"

/**
 * Panel height cap (with little content the panel tightens to its content; this is only an upper bound).
 */
private val PANEL_MAX_HEIGHT = 640.dp

/**
 * The **fixed** row height of one folder row. It is the unit of measure for [LIST_MAX_HEIGHT]: the row height is
 * hard-coded, the list cap is an integer multiple of it, and the two share a single source of truth, so the list never
 * cuts through the middle of a row (whole rows show or whole rows scroll away). Incidentally it also keeps the tap target at the 44dp touch minimum.
 */
private val FOLDER_ROW_HEIGHT = 44.dp

/** The folder list shows at most 6 whole rows; anything beyond that scrolls itself. */
private val LIST_MAX_HEIGHT = FOLDER_ROW_HEIGHT * 6

/**
 * The project <-> cloud-folder binding panel (v1.4). One dialog layer carries the whole flow: sign in -> pick a folder ->
 * auto-align -> see status -> sync / unlink.
 *
 * v1.1: switched from a bottom sheet (`ModalBottomSheet`) to a **large dialog**. The reason was that the bottom sheet treated
 * gestures as its control mechanism -- while picking a folder, a downward swipe meant to scroll the list instead slid the whole sheet away and you had to start over.
 * A dialog does not take over the drag gesture, so no amount of list scrolling can lose the panel; the panel is also taller, showing 6 rows on a screen instead of 4.
 * The cost is that "swipe down to close" is gone, so an explicit "Close" button and the system back key were added to compensate.
 *
 * Picking is still **browse-style**: tap a row to descend a level, and the target level is confirmed by "Select this folder" at the bottom. There are no
 * per-row twin buttons, and no typing just to pick a folder that already exists. New folder is demoted to a small entry point --
 * it is the one action that cannot avoid typing a name.
 *
 * The scroll structure is deliberate: **only the folder list scrolls, the outer layer does not**. Once the outer layer can scroll, a long list pushes the
 * bottom buttons off screen; and outer scroll + list scroll is same-direction nested scrolling, which crashes outright. The list height cap is taken
 * as an integer number of row heights ([LIST_MAX_HEIGHT]), so it either shows whole rows or scrolls whole rows away and never
 * cuts through the middle of a row; the bottom confirm button is therefore always visible, and with [imePadding] that holds when the keyboard is up too.
 *
 * The cloud-drive root is not selectable: syncing the whole drive would amplify delete propagation into an uncontrollable range, so the bottom confirm button is
 * disabled outright at the root level with the reason stated, rather than letting someone tap it and then get an error.
 */
@Composable
fun OneDriveSheet(
    project: Project,
    account: OneDriveAccount?,
    configured: Boolean,
    syncState: SyncState,
    folders: List<CloudFolder>,
    busy: Boolean,
    error: String?,
    onBrowse: (parentId: String, parentPath: String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBind: (CloudFolder) -> Unit,
    /** Create a folder **at the current browse level** and bind it (parentId/parentPath are supplied by the dialog). */
    onCreateAndBind: (parentId: String, parentPath: String, name: String) -> Unit,
    onSyncNow: () -> Unit,
    onUnbind: () -> Unit,
    onDismissError: () -> Unit,
    onDismiss: () -> Unit
) {
    // The browse-level stack is held by the dialog itself: the VM only answers "give me the subfolders of some level". The stack stores
    // CloudFolder itself, so confirming at the bottom never has to look the current level back up from the sub-list (which does not contain itself anyway).
    var stack by remember { mutableStateOf(listOf(CloudFolder(ROOT_ID, "我的文件", ""))) }
    var draft by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    val current = stack.last()
    val atRoot = current.id == ROOT_ID

    LaunchedEffect(current.id, account?.id) {
        if (account != null) onBrowse(current.id, current.path)
    }

    // The dialog does not take over the drag gesture, so "back" has to be caught by us; otherwise the only way out is tapping "Close".
    BackHandler { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        // A dialog at the default width cannot fit the folder list; the width limit is lifted and we leave the margins ourselves with a 0.94 width.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = PANEL_MAX_HEIGHT)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp, bottom = 24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("OneDrive", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text("关闭", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(Modifier.size(4.dp))

                    when {
                        !configured -> Text(
                            text = "尚未配置：请在 gradle.properties 填入 msalClientId（见 docs/PlaningStages/onedrive-setup.md）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )

                        account == null -> Button(onClick = onConnect) { Text("登录 Microsoft 账号") }

                        else -> {
                            AccountLine(username = account.username, onDisconnect = onDisconnect)
                            Spacer(Modifier.size(12.dp))

                            val binding = project.oneDrive
                            if (binding != null) {
                                BoundPanel(
                                    folderPath = binding.folderPath,
                                    lastSyncedAt = binding.lastSyncedAt,
                                    onSyncNow = onSyncNow,
                                    onUnbind = onUnbind
                                )
                            } else {
                                PathRow(
                                    path = current.path,
                                    canGoUp = stack.size > 1,
                                    onGoUp = { stack = stack.dropLast(1) }
                                )
                                Spacer(Modifier.size(4.dp))

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = LIST_MAX_HEIGHT)
                                ) {
                                    when {
                                        busy -> item { Hint("读取中…") }
                                        folders.isEmpty() -> item { Hint("这一层没有子文件夹。") }
                                        else -> items(folders, key = { it.id }) { folder ->
                                            FolderRow(name = folder.name, onClick = { stack = stack + folder })
                                        }
                                    }
                                }

                                Spacer(Modifier.size(10.dp))
                                Hint(
                                    if (atRoot) "根目录不能直接同步，请进入下一层"
                                    else "项目为空会下载云端内容，有文件会上传"
                                )
                                Spacer(Modifier.size(8.dp))

                                if (naming) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = draft,
                                            onValueChange = { draft = it },
                                            singleLine = true,
                                            placeholder = {
                                                Text("新文件夹名称", style = MaterialTheme.typography.bodyMedium)
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        TextButton(
                                            onClick = {
                                                val name = draft.trim()
                                                if (name.isNotEmpty()) {
                                                    onCreateAndBind(current.id, current.path, name)
                                                    draft = ""
                                                    naming = false
                                                }
                                            },
                                            enabled = draft.isNotBlank()
                                        ) { Text("新建并使用") }
                                    }
                                } else {
                                    TextButton(onClick = { naming = true }) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.size(4.dp))
                                        Text("新建文件夹")
                                    }
                                }
                                // At the root level this button is not rendered: a large grey block that can never be tapped is pure visual noise, and
                                // the hint above already says plainly "go one level deeper".
                                if (!atRoot) {
                                    Spacer(Modifier.size(4.dp))
                                    Button(
                                        onClick = { onBind(current) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("选择此文件夹") }
                                }
                            }

                            // With an explicit error row present, do not stack a status line on top; otherwise the same sentence shows twice.
                            if (error == null && syncState.showable(bound = binding != null)) {
                                Spacer(Modifier.size(10.dp))
                                StatusLine(syncState)
                            }
                        }
                    }

                    error?.let { message ->
                        Spacer(Modifier.size(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissError) { Text("知道了") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountLine(username: String, onDisconnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = username.ifEmpty { "已连接" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDisconnect) {
            Text(
                text = "退出登录",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Already bound: it only needs to answer "syncing to where, when last, and how it looks now". */
@Composable
private fun BoundPanel(
    folderPath: String,
    lastSyncedAt: Long,
    onSyncNow: () -> Unit,
    onUnbind: () -> Unit
) {
    Text(
        text = folderPath,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.size(2.dp))
    Text(
        text = lastSyncedAt.takeIf { it > 0 }
            ?.let { "上次同步 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)) }
            ?: "还没有同步过",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.size(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSyncNow) { Text("立即同步") }
        TextButton(onClick = onUnbind) {
            Text("取消关联", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The current browse level (path + go up one level). */
@Composable
private fun PathRow(path: String, canGoUp: Boolean, onGoUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (canGoUp) {
            IconButton(onClick = onGoUp) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "上一层",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            text = path.ifEmpty { "我的文件" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One folder row: the whole row is tappable = descend one level. */
@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FOLDER_ROW_HEIGHT)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
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

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * The picking state only shows "in progress / error"; results like "Uploaded 2" are only shown in an already-bound panel --
 * otherwise an "up to date" left behind by another project's last sync would float at the bottom of a not-yet-bound panel, pure noise.
 */
private fun SyncState.showable(bound: Boolean): Boolean = when (this) {
    is SyncState.Idle -> false
    is SyncState.Working, is SyncState.Error -> true
    is SyncState.Info -> bound
}

/** One status line: it appears only when something truly happens, and takes no visual space while idle. */
@Composable
private fun StatusLine(syncState: SyncState) {
    val text = when (syncState) {
        is SyncState.Idle -> return
        is SyncState.Working -> syncState.message
        is SyncState.Info -> syncState.message
        is SyncState.Error -> syncState.message
    }
    val color = if (syncState is SyncState.Error) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}
