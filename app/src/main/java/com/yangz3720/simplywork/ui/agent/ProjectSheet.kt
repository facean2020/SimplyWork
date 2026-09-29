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
 * Project switch/create sheet (v1.3, modelled on Qwen Studio's project setup): lists projects (the current one ticked),
 * tap to switch, type at the bottom to create. Creating does not switch the current project (consistent with the agent tool semantics).
 *
 * v1.4: each row gains a cloud entry point on the right ([onOpenOneDrive]), and its label changes with the binding state --
 * text rather than an added icon, so we do not pull in a whole set of icon dependencies for one state.
 *
 * v1.6: long-press a row -> delete the project after confirmation ([onDelete]). The default project only gets a notice, with no delete button
 * (it is the fallback owner of historical sessions, see [ProjectLogic.DEFAULT_PROJECT_ID]).
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