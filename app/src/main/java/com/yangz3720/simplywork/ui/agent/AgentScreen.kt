package com.yangz3720.simplywork.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yangz3720.simplywork.core.session.SessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 主对话视窗（architecture.md `AgentConversationScreen`，UI 阶段实现）：
 * 单列对话流 + ChatGPT 式会话抽屉 + 底部输入条 + Q1 审批弹窗 + 待办面板。
 * v1 非流式：对话投影以持久化事实为准整帧刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val approval by viewModel.approval.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var showTodos by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var editingMessage by remember { mutableStateOf<UiMessage?>(null) }

    val activeAccount = state.providerAccounts.firstOrNull { it.id == state.activeProviderId }
    val modelLabel = when {
        activeAccount != null && state.activeModel.isNotBlank() -> "${activeAccount.name} · ${state.activeModel}"
        state.activeModel.isNotBlank() -> state.activeModel
        else -> "选择模型"
    }

    // 长按历史提问 → 进入编辑（预填原文）；发送后开启新分支（旧分支保留可切换）。
    val startEdit: (UiMessage) -> Unit = { message ->
        if (!state.running) {
            editingMessage = message
            draft = message.text
        }
    }
    val submitDraft: (String) -> Unit = { text ->
        val editing = editingMessage
        if (editing != null) {
            viewModel.editAndResend(editing.id, text)
            editingMessage = null
            draft = ""
        } else {
            viewModel.send(text)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
                // v1.3 项目区：当前项目 + 切换/新建入口（会话列表已按项目过滤）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showProjects = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier
                        .padding(start = 10.dp)
                        .weight(1f)) {
                        Text(
                            text = state.projects.firstOrNull { it.id == state.currentProjectId }?.name ?: "项目",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "会话与沙箱按项目隔离 · 点按切换",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                SessionDrawer(
                    sessions = state.sessions,
                    currentSessionId = state.currentSessionId,
                    onNewChat = {
                        viewModel.startNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onOpen = { id ->
                        viewModel.openSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onRefresh = { viewModel.refreshSessions() }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    // Gemini/ChatGPT 式模型选择 chip：上行会话名，下行当前服务商·模型（点按弹层切换）。
                    title = {
                        Column(
                            modifier = Modifier.clickable { showModelPicker = true }
                        ) {
                            Text(
                                text = state.currentTitle ?: "SimplyWork",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = modelLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (state.modelConfigured) MaterialTheme.colorScheme.onBackground
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "切换模型",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "会话列表")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                InputBar(
                    draft = draft,
                    onDraftChange = { draft = it },
                    running = state.running,
                    iteration = state.iteration,
                    runError = state.runError,
                    todos = state.todos,
                    onSend = submitDraft,
                    onStop = { viewModel.stopRun() },
                    onRetry = { viewModel.retry() },
                    onShowTodos = { showTodos = true },
                    editing = editingMessage,
                    onCancelEdit = {
                        editingMessage = null
                        draft = ""
                    }
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxWidth()) {
                if (state.messages.isEmpty()) {
                    EmptyState(
                        onSuggestion = { draft = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    ConversationList(
                        messages = state.messages,
                        onEditUser = startEdit,
                        onSwitchBranch = { message, direction -> viewModel.switchBranch(message.id, direction) }
                    )
                }
            }
        }
    }

    // Q1：HITL 审批弹窗 —— 关闭弹窗（返回/点外部）按"拒绝"处理，安全默认。
    approval?.let { request ->
        ApprovalDialog(
            request = request,
            onAllow = { viewModel.respondApproval(true) },
            onDeny = { viewModel.respondApproval(false) }
        )
    }

    // 待办（State Memory 投影）
    if (showTodos) {
        ModalBottomSheet(
            onDismissRequest = { showTodos = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            TodoSheet(todos = state.todos, modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    // 顶栏模型选择弹层（Copilot 式聚焦列表：汇总已配置服务商模型，选择即切换服务商+模型）
    if (showModelPicker) {
        ModelPickerSheet(
            accounts = state.providerAccounts,
            activeProviderId = state.activeProviderId,
            activeModel = state.activeModel,
            fetchModels = { account -> viewModel.fetchModels(account) },
            onSelect = { providerId, model -> viewModel.selectModel(providerId, model) },
            onDismiss = { showModelPicker = false }
        )
    }

    // v1.3 项目切换/新建弹层（从会话抽屉进入）。
    if (showProjects) {
        ProjectSheet(
            projects = state.projects,
            currentProjectId = state.currentProjectId,
            onSelect = {
                viewModel.switchProject(it)
                showProjects = false
                scope.launch { drawerState.close() }
            },
            onCreate = { viewModel.createProject(it) },
            onDismiss = { showProjects = false }
        )
    }
}

// ----- 会话抽屉（ChatGPT 式列表） -----

@Composable
private fun SessionDrawer(
    sessions: List<SessionRecord>,
    currentSessionId: String?,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit
) {
    LaunchedEffect(Unit) { onRefresh() }
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "对话",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onNewChat) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(4.dp))
                Text("新对话", style = MaterialTheme.typography.labelMedium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        if (sessions.isEmpty()) {
            Text(
                text = "还没有对话",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        } else {
            LazyColumn {
                items(sessions, key = { it.id }) { session ->
                    val selected = session.id == currentSessionId
                    val formatter = remember { SimpleDateFormat("MM-dd", Locale.getDefault()) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable { onOpen(session.id) }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatter.format(Date(session.createdAt)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}