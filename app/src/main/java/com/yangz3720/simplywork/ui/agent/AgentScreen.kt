package com.yangz3720.simplywork.ui.agent

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import com.yangz3720.simplywork.core.session.Project
import com.yangz3720.simplywork.core.session.SessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Main conversation viewport (architecture.md `AgentConversationScreen`, implemented in the UI stage):
 * single-column conversation stream + ChatGPT-style session drawer + bottom input bar + Q1 approval dialog + todo panel.
 * v1 is non-streaming: the conversation projection refreshes as a whole frame, keyed off persisted facts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val approval by viewModel.approval.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // MSAL's interactive sign-in needs an Activity to launch the authorization page; the Compose side reads it from here.
    val activity = LocalActivity.current
    var draft by remember { mutableStateOf("") }
    var showTodos by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var oneDriveTarget by remember { mutableStateOf<Project?>(null) }
    var editingMessage by remember { mutableStateOf<UiMessage?>(null) }

    // v1.8 uploads. The pickers are the platform's own (photo picker / document UI), so no storage or media
    // permission is involved; the document picker is opened with */* on purpose -- which formats are accepted is
    // decided in one place (the extractor factory), so adding pdf/word later needs no change here.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.attachUpload(it.toString()) }
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.attachUpload(it.toString()) }
    }

    val activeAccount = state.providerAccounts.firstOrNull { it.id == state.activeProviderId }
    val modelLabel = when {
        activeAccount != null && state.activeModel.isNotBlank() -> "${activeAccount.name} · ${state.activeModel}"
        state.activeModel.isNotBlank() -> state.activeModel
        else -> "选择模型"
    }

    // Long-press a past user prompt -> enter edit mode (pre-filled with the original text); sending opens a new branch (the old branch is kept and switchable).
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
                // v1.3 project area: current project + switch/create entry points (the session list is already filtered by project).
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
                            text = "点按切换项目",
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
                // v1.5 project files: the read-only browsing entry point for the current project's sandbox. Indented under the project name so it reads as a sub-action of the project.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFiles() }
                        .padding(start = 48.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "项目文件",
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
                    onDelete = { session -> viewModel.deleteSession(session.id) },
                    onRefresh = { viewModel.refreshSessions() }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    // Gemini/ChatGPT-style model picker chip: top row is the session name, bottom row is the current provider + model (tapping opens the sheet to switch).
                    // The session name only exists once the session has been persisted; a new conversation does not occupy that row (this used to be filled by an App-name placeholder).
                    title = {
                        Column(
                            modifier = Modifier.clickable { showModelPicker = true }
                        ) {
                            state.currentTitle?.takeIf { it.isNotBlank() }?.let { title ->
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                    },
                    uploads = state.pendingUploads,
                    uploadBusy = state.uploadBusy,
                    uploadError = state.uploadError,
                    canAttachImage = state.activeModelSupportsVision,
                    onAddImage = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onAddDocument = { pickDocument.launch(arrayOf("*/*")) },
                    onRemoveUpload = { viewModel.removeUpload(it) },
                    loadImageBytes = { viewModel.loadImageBytes(it) }
                )
            }
        ) { innerPadding ->
            // The conversation stream fills the remaining height, and the "new files" card (v1.5) at the bottom is pinned directly above the input bar --
            // it lives in the content area rather than the bottomBar: the input bar brings its own imePadding, and stacking another layer would push the card
            // behind the keyboard; placed here, it gets compressed along with the content area when the keyboard comes up, so it is always visible.
            Column(modifier = Modifier.padding(innerPadding).fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (state.messages.isEmpty()) {
                        EmptyState(
                            onSuggestion = { draft = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        ConversationList(
                            messages = state.messages,
                            onEditUser = startEdit,
                            onSwitchBranch = { message, direction -> viewModel.switchBranch(message.id, direction) },
                            loadImageBytes = { viewModel.loadImageBytes(it) }
                        )
                    }
                }
                if (state.newFiles.isNotEmpty()) {
                    NewFilesCard(
                        files = state.newFiles,
                        onOpen = { viewModel.openFilePreview(it) }
                    )
                }
            }
        }
    }

    // Q1: HITL approval dialog -- dismissing it (back gesture / tapping outside) counts as "deny", the safe default.
    approval?.let { request ->
        ApprovalDialog(
            request = request,
            onAllow = { viewModel.respondApproval(true) },
            onDeny = { viewModel.respondApproval(false) }
        )
    }

    // Todos (the projection of State Memory)
    if (showTodos) {
        ModalBottomSheet(
            onDismissRequest = { showTodos = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            TodoSheet(todos = state.todos, modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    // Top-bar model picker sheet (Copilot-style focused list: aggregates the configured providers' models, and selecting one switches provider + model)
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

    // v1.3 project switch/create sheet (entered from the session drawer).
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
            onOpenOneDrive = { project ->
                // Two ModalBottomSheets cannot be resident at the same time: collapse the project sheet first, then open the cloud sheet.
                showProjects = false
                oneDriveTarget = project
            },
            onDelete = { project -> viewModel.deleteProject(project.id) },
            onDismiss = { showProjects = false }
        )
    }

    // v1.4 OneDrive binding panel; project is re-resolved from the latest list every frame, so a completed binding is reflected in the state immediately.
    oneDriveTarget?.let { target ->
        val project = state.projects.firstOrNull { it.id == target.id } ?: target
        OneDriveSheet(
            project = project,
            account = state.oneDriveAccount,
            configured = state.oneDriveConfigured,
            syncState = state.syncState,
            folders = state.cloudFolders,
            busy = state.cloudBusy,
            error = state.cloudError,
            onBrowse = { parentId, parentPath -> viewModel.browseCloudFolders(parentId, parentPath) },
            onConnect = { viewModel.connectOneDrive(activity) },
            onDisconnect = { viewModel.disconnectOneDrive() },
            onBind = { folder -> viewModel.bindCloudFolder(project.id, folder) },
            onCreateAndBind = { parentId, parentPath, name ->
                viewModel.createCloudFolderAndBind(project.id, parentId, parentPath, name)
            },
            onSyncNow = { viewModel.syncCloudNow(project.id) },
            onUnbind = { viewModel.unbindCloud(project.id) },
            onDismissError = { viewModel.dismissCloudError() },
            onDismiss = { oneDriveTarget = null }
        )
    }
}

// ----- Session drawer (ChatGPT-style list) -----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionDrawer(
    sessions: List<SessionRecord>,
    currentSessionId: String?,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (SessionRecord) -> Unit,
    onRefresh: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SessionRecord?>(null) }
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
                            .combinedClickable(
                                onClickLabel = "打开对话",
                                onLongClickLabel = "删除对话",
                                onClick = { onOpen(session.id) },
                                onLongClick = { pendingDelete = session }
                            )
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

    pendingDelete?.let { target ->
        ConfirmDeleteDialog(
            title = "删除对话",
            body = "将删除「${target.title}」的全部消息与文件改动记录，此操作不可撤销。" +
                "项目里的文件不受影响。",
            onConfirm = {
                onDelete(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}