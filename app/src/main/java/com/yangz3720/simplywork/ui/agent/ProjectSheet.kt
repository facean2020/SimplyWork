package com.yangz3720.simplywork.ui.agent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yangz3720.simplywork.core.session.Project
import com.yangz3720.simplywork.core.session.ProjectLogic

/**
 * 项目切换/新建弹层（v1.3，参考 Qwen Studio 建档）：列项目（当前项打勾）、
 * 点选切换、底部输入新建。新建不切换当前项目（与 agent 工具语义一致）。
 *
 * v1.4：每行右侧多一个云端入口（[onOpenOneDrive]），文案随绑定状态变化 ——
 * 用文字而不是新增图标，避免为了一个状态再引一套图标依赖。
 *
 * v1.6：长按行 → 确认后删除项目（[onDelete]）。默认项目只弹告知、不给删除按钮
 * （它是历史会话的兜底归属，见 [ProjectLogic.DEFAULT_PROJECT_ID]）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectSheet(
    projects: List<Project>,
    currentProjectId: String,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onOpenOneDrive: (Project) -> Unit,
    onDelete: (Project) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("项目", style = MaterialTheme.typography.titleMedium)
            Text(
                "各项目的会话与文件相互独立；长按可删除项目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(projects, key = { it.id }) { project ->
                    val selected = project.id == currentProjectId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClickLabel = "切换到该项目",
                                onLongClickLabel = "删除项目",
                                onClick = { onSelect(project.id) },
                                onLongClick = { pendingDelete = project }
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.List,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .weight(1f)
                        )
                        TextButton(onClick = { onOpenOneDrive(project) }) {
                            val bound = project.oneDrive != null
                            Text(
                                text = if (bound) "已连接" else "云端",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (bound) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("新项目名称", style = MaterialTheme.typography.bodyMedium) }
                )
                Spacer(Modifier.size(8.dp))
                TextButton(
                    onClick = {
                        val name = draft.trim()
                        if (name.isNotEmpty()) {
                            onCreate(name)
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank()
                ) {
                    Text("新建项目")
                }
            }
        }
    }

    pendingDelete?.let { target ->
        val deletable = target.id != ProjectLogic.DEFAULT_PROJECT_ID
        ConfirmDeleteDialog(
            title = if (deletable) "删除项目" else "无法删除默认项目",
            body = if (deletable) {
                "将删除「${target.name}」及其全部会话、本地文件与云端关联记录；" +
                    "云端文件夹里的文件会保留。此操作不可撤销。"
            } else {
                "「${target.name}」是历史会话的兜底归属，删掉它那些会话就无处可去了。"
            },
            onConfirm = if (deletable) {
                {
                    onDelete(target)
                    pendingDelete = null
                }
            } else null,
            onDismiss = { pendingDelete = null }
        )
    }
}