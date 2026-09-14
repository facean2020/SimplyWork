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

/** 网盘根在 Graph 里的固定 item id；根层只用于浏览，不可挂接。 */
private const val ROOT_ID = "root"

/**
 * 面板高度上限（内容少时面板按内容收紧，这只是一个上限）。
 */
private val PANEL_MAX_HEIGHT = 640.dp

/**
 * 单个文件夹行的**固定**行高。它是 [LIST_MAX_HEIGHT] 的计量单位：行高被写死，列表
 * 上限取整数倍，两者共用一个真值源，列表才永远不会切在行中间（要么整行显示、
 * 要么整行滚走）。顺带保证点击区域不小于 44dp 的触控最小尺寸。
 */
private val FOLDER_ROW_HEIGHT = 44.dp

/** 文件夹列表最多显示 6 整行，多出来的自己滚。 */
private val LIST_MAX_HEIGHT = FOLDER_ROW_HEIGHT * 6

/**
 * 项目 ↔ 云端文件夹的挂接面板（v1.4）。一个弹层走完全部流程：登录 → 挑文件夹 →
 * 自动对齐 → 看状态 → 同步 / 取消关联。
 *
 * v1.1：从底部面板（`ModalBottomSheet`）改成**大弹窗**。原因是底部面板把手势当成了
 * 控制方式 —— 挑文件夹时手往下滑本想翻列表，却直接把整个面板滑走了，人得从头再来一遍。
 * 弹窗不接管下拉手势，列表怎么滚都不会把面板弄没；面板也更高，一屏能看 6 行而不是 4 行。
 * 代价是失去了"下滑关闭"，所以补上一个显式的「关闭」按钮和系统返回键。
 *
 * 挑选仍然是**浏览式**的：点一行进下一层，目标层级由底部的「选择此文件夹」确认。没有
 * 每行的双按钮，也不用为了挑一个已存在的文件夹打字。新建文件夹退成一个小入口 ——
 * 它是唯一躲不掉输入名字的动作。
 *
 * 滚动结构是刻意的：**只有文件夹列表滚，外层不滚**。外层一旦可滚动，长列表会把
 * 底部按钮推出屏幕；而外层滚 + 列表也滚是同向嵌套滚动，会直接崩。列表高度上限取
 * 整数行高（[LIST_MAX_HEIGHT]），所以它要么整行显示、要么整行滚走，永远不切在行
 * 中间；底部确认按钮因此永远可见，配 [imePadding] 后键盘弹起也一样。
 *
 * 网盘根不可选：整盘同步会把删除传播放大到不可控范围，所以底部的确认按钮在根层
 * 直接禁用并说明原因，而不是让人点下去再报错。
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
    /** 在**当前浏览层级**新建文件夹并挂接（parentId/parentPath 由弹层给出）。 */
    onCreateAndBind: (parentId: String, parentPath: String, name: String) -> Unit,
    onSyncNow: () -> Unit,
    onUnbind: () -> Unit,
    onDismissError: () -> Unit,
    onDismiss: () -> Unit
) {
    // 浏览层级栈由弹层自己持有：VM 只负责"给我某一层的子文件夹"。栈里存 CloudFolder
    // 本身，底部确认时就不用再从子列表里反查当前层级（子列表里根本没有自己）。
    var stack by remember { mutableStateOf(listOf(CloudFolder(ROOT_ID, "我的文件", ""))) }
    var draft by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    val current = stack.last()
    val atRoot = current.id == ROOT_ID

    LaunchedEffect(current.id, account?.id) {
        if (account != null) onBrowse(current.id, current.path)
    }

    // 弹窗不接管下拉手势，所以"返回"必须由我们兜住，否则只能靠点「关闭」。
    BackHandler { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        // 默认宽度的对话框装不下文件夹列表；放开宽度限制后自己用 0.94 的宽度留出边距。
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
                                // 根层不渲染这个按钮：一个永远点不动的灰色大块只是视觉噪音，
                                // 上面的提示已经说清"要往下进一层"。
                                if (!atRoot) {
                                    Spacer(Modifier.size(4.dp))
                                    Button(
                                        onClick = { onBind(current) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("选择此文件夹") }
                                }
                            }

                            // 有显式错误行时不再叠一行状态，否则同一句话会显示两遍。
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

/** 已挂接：只需回答"同步到哪、上次什么时候、现在怎么样"。 */
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

/** 当前浏览层级（路径 + 上一层）。 */
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

/** 一行文件夹：整行可点 = 进下一层。 */
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
 * 挑选态只显示"进行中 / 出错"；「上传 2」这类结果只在已挂接的面板里显示 ——
 * 不然别的项目上次同步留下的「已是最新」会飘在一个还没挂接的面板底部，纯噪音。
 */
private fun SyncState.showable(bound: Boolean): Boolean = when (this) {
    is SyncState.Idle -> false
    is SyncState.Working, is SyncState.Error -> true
    is SyncState.Info -> bound
}

/** 一行状态：只在真的有事发生时出现，空闲时不占视觉。 */
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
