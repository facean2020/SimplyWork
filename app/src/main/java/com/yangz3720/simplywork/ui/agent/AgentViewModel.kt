package com.yangz3720.simplywork.ui.agent

import android.Manifest
import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yangz3720.simplywork.core.AgentRunSupervisor
import com.yangz3720.simplywork.core.AgentRuntime
import com.yangz3720.simplywork.core.agent.AgentStepEvent
import com.yangz3720.simplywork.core.agent.ContextBudgetPlan
import com.yangz3720.simplywork.core.agent.NodePayloads
import com.yangz3720.simplywork.core.attachment.ImageBlobStore
import com.yangz3720.simplywork.core.attachment.PendingUpload
import com.yangz3720.simplywork.core.attachment.PromptUploads
import com.yangz3720.simplywork.core.attachment.UploadOwner
import com.yangz3720.simplywork.core.cloud.CloudFolder
import com.yangz3720.simplywork.core.cloud.OneDriveOwner
import com.yangz3720.simplywork.core.session.Project
import com.yangz3720.simplywork.core.session.ProjectLogic
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionRecord
import com.yangz3720.simplywork.core.workspace.ExternalPreview
import com.yangz3720.simplywork.core.workspace.ExternalPreviewException
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.core.workspace.FileChangeKind
import com.yangz3720.simplywork.core.workspace.FileKinds
import com.yangz3720.simplywork.core.workspace.ProjectFilesOwner
import com.yangz3720.simplywork.infrastructure.model.ModelInfo
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import com.yangz3720.simplywork.infrastructure.model.model.Attachment
import com.yangz3720.simplywork.infrastructure.model.model.Message
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Current model selection: provider account + model id (switched dynamically from the chat top bar). */
data class ModelSelection(val providerId: String, val model: String)

/**
 * Composition-root seam (the DI surface of `AgentViewModel` in architecture.md): the App container
 * implements it, JVM tests use a fake. Multi-provider semantics: each provider only stores its key; provider+model are picked on the chat screen.
 */
interface AgentRuntimeOwner {
    fun current(): AgentRuntime?
    fun accounts(): List<ProviderAccount>
    fun selection(): ModelSelection?

    /**
     * Config version (v1.3): bumped when the agent edits its own config via self_config_update. The VM
     * uses it to call [rebuildRuntimeIfNeeded] while idle, so the change lands (rebuilding mid-run would swap out the running coordinator).
     */
    val configVersion: StateFlow<Int>

    /** Apply the pending config change that needs a rebuild (no-op when there is none); the caller guarantees no run is active. */
    fun rebuildRuntimeIfNeeded()

    /** Settings screen: save a provider's API key (rebuilds the runtime when it is the currently selected provider). */
    fun saveAccountKey(accountId: String, apiKey: String)

    /** Settings screen: add a custom endpoint; returns the persisted account. */
    fun upsertCustomAccount(name: String, baseUrl: String, apiKey: String): ProviderAccount

    /** Settings screen: remove a custom endpoint (presets cannot be removed; the caller guarantees this). */
    fun removeCustomAccount(accountId: String)

    /** Chat top bar: switch the current provider + model and rebuild the runtime (the caller guarantees no run is active). */
    fun applySelection(providerId: String, model: String)

    /** Settings screen (v1.1): whether non-destructive calls are auto-approved. */
    fun autoApproveNonDestructive(): Boolean

    /** Settings screen (v1.1): toggle auto-approval (rebuilds the runtime so the audit gate picks up the new policy). */
    fun setAutoApproveNonDestructive(enabled: Boolean)

    /** Settings screen (v1.1): context budget of the current model (including where it comes from; the settings screen must show this faithfully). Null when no model is selected. */
    fun contextBudget(): ContextBudgetPlan?

    /** Settings screen (v1.1): manually set the context budget; null = fall back to automatic derivation. */
    fun setContextBudgetOverride(tokens: Int?)

    /** Settings screen: read the Markdown Soul belonging to one project. */
    fun loadProjectSoul(projectId: String): String

    /** Settings screen: persist the Markdown Soul belonging to one project. */
    fun saveProjectSoul(projectId: String, content: String)

    /** Project deletion: drop the project's Soul together with the rest of its data. */
    fun deleteProjectSoul(projectId: String)

    /** Chat screen: whether the selected model accepts image input, i.e. whether the image attach entry is offered. */
    fun activeModelSupportsVision(): Boolean

    /** Fetch the endpoint's model catalogue for a provider account (GET /models), including whatever the vendor disclosed. */
    suspend fun fetchModels(account: ProviderAccount): Result<List<ModelInfo>>
}

/**
 * MVI state holder (UI layer in architecture.md): projects the loop/queue offered by
 * [AgentRuntimeOwner] plus SessionRepository into [AgentUiState].
 *
 * Responsibilities:
 * - Run driving: collect the cold Flow of `AgentLoopCoordinator.run` (Q2: the loop restarts from
 *   the outside; after a failure [retry] resumes with `initialPrompt=null`);
 * - Multi-session: ChatGPT-style list ([startNewChat] creates the session lazily, the first message's first line becomes the title);
 * - HITL (Q1): exposes [ApprovalCoordinator.pending] so the UI can show the approval dialog;
 * - Input sent while running degrades to Steer automatically (design section 6 node B).
 */
class AgentViewModel(
    private val owner: AgentRuntimeOwner,
    /**
     * Run driving (v1.6): the run is owned by the process-level supervisor, no longer by this VM's viewModelScope.
     * The run keeps going after the Activity is destroyed or backgrounded (the foreground service pins the process up front) and this VM degrades to a subscriber.
     */
    private val runSupervisor: AgentRunSupervisor,
    val approvalCoordinator: ApprovalCoordinator,
    /** Must be the same instance the AppContainer wired into the executor — a private copy
     * would leave every permission request hanging until the router timeout. */
    val permissionCoordinator: PermissionCoordinator,
    /** Project registry (v1.3): session ownership + sandbox directory; the UI and the agent share the same instance. */
    private val projectStore: ProjectStore,
    /** OneDrive seam (v1.4): account, cloud folder browsing, mirror sync. */
    private val oneDrive: OneDriveOwner,
    /** Project files seam (v1.5): read-only browsing / preview + the session change log. */
    private val projectFiles: ProjectFilesOwner,
    /** External preview seam (v1.7): types this app cannot render (audio, images) go to an outside app instead of pulling a decoder/player into the build. */
    private val externalPreview: ExternalPreview,
    /** Upload seam (v1.8): a picked image / document becomes something the model can read. */
    private val uploads: UploadOwner,
    /** v1.9: binary image store, shared with the upload loader and the request formatter (thumbnails read from it). */
    private val imageBlobs: ImageBlobStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private fun initialState(): AgentUiState {
        val selection = owner.selection()
        // Souls are files: they are not read here (this runs on the main thread) -- see the init block.
        return AgentUiState(
            providerAccounts = owner.accounts(),
            activeProviderId = selection?.providerId,
            activeModel = selection?.model.orEmpty(),
            modelConfigured = selection != null && selection.model.isNotBlank(),
            projects = projectStore.list(),
            currentProjectId = projectStore.currentId(),
            autoApproveNonDestructive = owner.autoApproveNonDestructive(),
            contextBudget = owner.contextBudget(),
            activeModelSupportsVision = owner.activeModelSupportsVision()
        )
    }

    /** Reads every project's Soul file: one file read per project, so callers must already be off the main thread. */
    private fun loadProjectSouls(projects: List<Project>): Map<String, String> =
        projects.associate { project -> project.id to owner.loadProjectSoul(project.id) }

    /** HITL pending request (Q1); when non-null the UI shows the approval dialog. */
    val approval = approvalCoordinator.pending

    /** Pending runtime permission request (v1.2); when non-null the UI triggers the system grant dialog. */
    val permission = permissionCoordinator.pending

    /** v1.6: POST_NOTIFICATIONS is asked at most once per process (see [ensureNotificationPermission]). */
    private var notificationPermissionRequested = false

    init {
        viewModelScope.launch(ioDispatcher) {
            // Read once, then publish: `update` re-runs its lambda on contention, and this one does file IO.
            val souls = loadProjectSouls(_uiState.value.projects)
            _uiState.update { it.copy(projectSouls = souls) }
            val sessions = sessionsForCurrentProject(repository()?.listSessions().orEmpty())
            _uiState.update { it.copy(sessions = sessions) }
            // The currently executing run takes over first: coming back from the notification / after an Activity
            // recreation, the UI must land on the executing session, not on "the most recent session". This path
            // **must not** go through openSession -- that would incidentally stop the run (see openSession's semantics).
            val running = runSupervisor.state.value
            val target = running.sessionId?.takeIf { id -> sessions.any { it.id == id } }
                ?: sessions.firstOrNull()?.id
            if (target != null) {
                if (running.running) switchToSession(target) else openSession(target)
            }
        }
        // v1.6: running/iteration are maintained in exactly one place, the supervisor -- a run may have started
        // before this VM was born (Activity recreation) and may continue after this VM is destroyed. Every UI-side
        // "reject while running" gate still reads uiState.running; only its source changed to here.
        viewModelScope.launch(ioDispatcher) {
            runSupervisor.state.collect { run ->
                _uiState.update { it.copy(running = run.running, iteration = run.iteration) }
            }
        }
        // Projecting loop events (conversation re-projection / todos / session list) stays on the UI side:
        // the facts live in the repository, and the events only decide "when to read them again".
        viewModelScope.launch(ioDispatcher) {
            runSupervisor.events.collect { onEvent(it) }
        }
        // v1.3: the agent edits its own config via self_config_update -> the rebuild lands while idle (never
        // during a run, which would swap out the running coordinator; the change applies to the next run and the tool result already declared it).
        viewModelScope.launch(ioDispatcher) {
            owner.configVersion.drop(1).collect {
                if (!_uiState.value.running) {
                    owner.rebuildRuntimeIfNeeded()
                    refreshSelection()
                }
            }
        }
        // Project / current-project change (UI or agent) -> the session filter changed, refresh the list.
        viewModelScope.launch(ioDispatcher) {
            projectStore.changes.drop(1).collect { refreshProjectsAndSessions() }
        }
        // OneDrive (v1.4): restore the signed-in account at startup and expose account / sync state to the UI.
        viewModelScope.launch(ioDispatcher) {
            oneDrive.restore()
            _uiState.update {
                it.copy(oneDriveAccount = oneDrive.account.value, oneDriveConfigured = oneDrive.configured)
            }
            // Bound current project: pull lazily once on entry (throttled; a cold start necessarily exceeds the interval).
            oneDrive.sync(_uiState.value.currentProjectId, throttled = true)
        }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.account.collect { acc ->
                _uiState.update { it.copy(oneDriveAccount = acc, oneDriveConfigured = oneDrive.configured) }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.syncState.collect { state -> _uiState.update { it.copy(syncState = state) } }
        }
    }

    // ----- Session management -----

    /** ChatGPT-style new chat: clears the current projection; the session is only persisted when the first message is sent (owned by the current project). */
    fun startNewChat() {
        runSupervisor.stop()
        // copy rather than rebuild: only the session projection is cleared, so provider / project / OneDrive and
        // other orthogonal state all survive (a rebuild-style version would silently drop fields when new ones are added).
        _uiState.update { current ->
            current.copy(
                currentSessionId = null,
                currentTitle = null,
                messages = emptyList(),
                running = false,
                iteration = 0,
                runError = null,
                todos = emptyList(),
                // The new-files card means "what this session produced", so it is gone once the session changes.
                newFiles = emptyList(),
                // A new session has not sent a request yet, so context usage is zeroed (otherwise the previous session's number would show).
                contextPromptTokens = 0
            )
        }
    }

    // ----- Projects (v1.3) -----

    /** Switch the current project: session list filtering + new session ownership + the default directory of the fs_* tools all follow. Rejected while running. */
    fun switchProject(projectId: String) {
        if (_uiState.value.running) {
            _uiState.update { it.copy(runError = "正在运行中，请先停止当前任务再切换项目") }
            return
        }
        if (!projectStore.switchTo(projectId)) {
            _uiState.update { it.copy(runError = "未知项目：$projectId") }
            return
        }
        // switchTo triggers projectStore.changes -> refreshProjectsAndSessions; here the conversation projection
        // goes back to the empty "new chat" state (the old project's sessions are no longer part of the current view).
        startNewChat()
        // The file browser now points at the previous project's directory, so fall back to the root (it re-lists on the next open).
        _uiState.update { it.copy(filePath = "", fileEntries = emptyList(), filesError = null, filePreview = null) }
        // Pull lazily once on entering a project (throttling lives in the owner; no repeated network calls within 10 minutes).
        viewModelScope.launch(ioDispatcher) { oneDrive.sync(projectId, throttled = true) }
    }

    /** Create a project (the sandbox directory is born with it); does not switch the current project. */
    fun createProject(name: String) {
        if (_uiState.value.running) return
        viewModelScope.launch(ioDispatcher) {
            projectStore.create(name).fold(
                onSuccess = { refreshProjectsAndSessions() },
                onFailure = { e -> _uiState.update { it.copy(runError = e.message ?: "新建项目失败") } }
            )
        }
    }

    /**
     * Delete a project (**irreversible**; the UI must confirm with a dialog first): everything under the project
     * is wiped -- all sessions (messages + payload + change log), the sandbox directory, the Soul, the cloud sync
     * baseline, and only then the project entry.
     *
     * Cloud **files are kept**: unbinding only drops the baseline; deleting a local project never touches the user's drive.
     * The order is deliberately "clear the child data first, delete the entry last": if it fails midway the project
     * is still there and can be retried; the other order would leave orphan data that can never be recognised again.
     */
    fun deleteProject(projectId: String) {
        if (_uiState.value.running) {
            _uiState.update { it.copy(runError = "正在运行中，请先停止当前任务再删除项目") }
            return
        }
        // The default project is blocked right here: the registry only rejects it at the last step, by which point the sessions and the sandbox are already wiped.
        if (projectId == ProjectLogic.DEFAULT_PROJECT_ID) {
            _uiState.update { it.copy(runError = "默认项目不可删除") }
            return
        }
        val wasCurrent = _uiState.value.currentProjectId == projectId
        viewModelScope.launch(ioDispatcher) {
            val repository = repository()
            repository?.listSessions().orEmpty()
                .filter { (it.projectId ?: ProjectLogic.DEFAULT_PROJECT_ID) == projectId }
                .forEach { session ->
                    repository?.deleteSession(session.id)
                    projectFiles.deleteSession(session.id)
                }
            oneDrive.unbind(projectId)
            projectFiles.deleteProject(projectId)
            // The Soul lives outside the sandbox (souls/<projectId>.md), so wiping the directory is not enough.
            owner.deleteProjectSoul(projectId)
            projectStore.delete(projectId).fold(
                onSuccess = {
                    // The current project was deleted -> the session projection must fall back with it, otherwise the chat stays on a session of the deleted project.
                    if (wasCurrent) startNewChat()
                    // projectStore.delete already bumped changes -> refreshProjectsAndSessions.
                },
                onFailure = { e -> _uiState.update { it.copy(runError = e.message ?: "删除项目失败") } }
            )
        }
    }

    // ----- Session deletion -----

    /**
     * Delete a single session (**irreversible**): nodes, state memory, payload files and the change log are cleared together.
     * If the deleted session is the current one, go back to the empty "new chat" state.
     */
    fun deleteSession(sessionId: String) {
        if (_uiState.value.running) {
            _uiState.update { it.copy(runError = "正在运行中，请先停止当前任务再删除会话") }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            repository()?.deleteSession(sessionId)
            projectFiles.deleteSession(sessionId)
            if (_uiState.value.currentSessionId == sessionId) startNewChat()
            refreshProjectsAndSessions()
        }
    }

    // ----- OneDrive (v1.4): account / folder browsing / binding / sync -----

    /**
     * Interactive sign-in ([activity] is passed in by the UI from LocalActivity; MSAL uses it to launch the auth page).
     * Null is allowed and reported explicitly: silently ignoring the click is harder to debug than an error (P2).
     */
    fun connectOneDrive(activity: Activity?) {
        if (activity == null) {
            _uiState.update { it.copy(cloudError = "无法获取当前 Activity，请重新打开页面后再试") }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.connect(activity).onFailure { e ->
                _uiState.update { it.copy(cloudError = e.message ?: "OneDrive 登录失败") }
            }
        }
    }

    /** Sign out; only clears the account, touching no bindings and no local files. */
    fun disconnectOneDrive() {
        viewModelScope.launch(ioDispatcher) { oneDrive.disconnect() }
    }

    /** Open / drill into the cloud folder picker. [parentId] = "root" means the drive root. */
    fun browseCloudFolders(parentId: String, parentPath: String) {
        _uiState.update { it.copy(cloudBusy = true, cloudError = null) }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.listFolders(parentId, parentPath).fold(
                onSuccess = { folders -> _uiState.update { it.copy(cloudFolders = folders, cloudBusy = false) } },
                onFailure = { e ->
                    _uiState.update { it.copy(cloudBusy = false, cloudError = e.message ?: "无法读取云端文件夹") }
                }
            )
        }
    }

    /** Bind to a cloud folder: the owner performs a first alignment up front (pull when the project is empty, upload when it has content). */
    fun bindCloudFolder(projectId: String, folder: CloudFolder) {
        // Clear the previous error first on retry, otherwise the old message shows side by side with the new result.
        _uiState.update { it.copy(cloudError = null) }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.bindFolder(projectId, folder).onFailure { e ->
                _uiState.update { it.copy(cloudError = e.message ?: "同步失败") }
            }
        }
    }

    /** Create a folder in the cloud and bind to it. */
    fun createCloudFolderAndBind(projectId: String, parentId: String, parentPath: String, name: String) {
        _uiState.update { it.copy(cloudError = null) }
        viewModelScope.launch(ioDispatcher) {
            oneDrive.createAndBindFolder(projectId, parentId, parentPath, name).onFailure { e ->
                _uiState.update { it.copy(cloudError = e.message ?: "新建文件夹失败") }
            }
        }
    }

    fun unbindCloud(projectId: String) {
        viewModelScope.launch(ioDispatcher) {
            oneDrive.unbind(projectId).onFailure { e ->
                _uiState.update { it.copy(cloudError = e.message ?: "取消关联失败") }
            }
        }
    }

    /** Explicit user "sync now": skips throttling. Rejected while running (a sync would contend with the run for the same workspace). */
    fun syncCloudNow(projectId: String) {
        if (_uiState.value.running) {
            _uiState.update { it.copy(cloudError = "正在运行中，请先停止当前任务再同步") }
            return
        }
        viewModelScope.launch(ioDispatcher) { oneDrive.sync(projectId, throttled = false) }
    }

    fun dismissCloudError() {
        _uiState.update { it.copy(cloudError = null) }
    }

    // ----- Project files (v1.5): read-only browsing / preview -----

    /** Open the file manager: back to the project root directory. */
    fun openProjectFiles() {
        loadFileDir("")
    }

    /** Enter a subdirectory ([path] is relative to the project root). */
    fun openFileDir(path: String) {
        loadFileDir(path)
    }

    /** Go up one level (a no-op at the root; the UI renders no back button at the root). */
    fun fileDirUp() {
        val current = _uiState.value.filePath
        if (current.isEmpty()) return
        loadFileDir(current.substringBeforeLast('/', ""))
    }

    /**
     * Preview a file. The "new files" card at the bottom of the chat and the file manager share this single
     * entry point -- wherever you come from it is the same screen with the same content, never two preview code paths.
     *
     * Binary is detected by NUL bytes in the content (more reliable than an extension allowlist): no mojibake is rendered, it just says the type is unsupported.
     */
    fun openFilePreview(path: String) {
        val projectId = _uiState.value.currentProjectId
        viewModelScope.launch(ioDispatcher) {
            val preview = projectFiles.read(projectId, path).fold(
                onSuccess = { content ->
                    val binary = content.content.contains('\u0000')
                    FilePreview(
                        path = path,
                        markdown = !binary && FileKinds.isMarkdown(path),
                        text = content.content.takeIf { !binary },
                        notice = when {
                            binary -> "无法预览此类型"
                            content.truncated -> "文件较大，仅显示开头部分"
                            else -> null
                        },
                        truncated = content.truncated,
                        sizeBytes = content.sizeBytes
                    )
                },
                onFailure = { FilePreview(path = path, notice = "无法读取该文件") }
            )
            _uiState.update { it.copy(filePreview = preview) }
        }
    }

    /**
     * Hand the file currently on the preview page to an outside app (v1.7).
     *
     * The reason this exists: inline preview only understands text, and packing a decoder plus a player for every
     * media type into the app is not a trade worth making. Failures land in [FilePreview.notice] on the page itself
     * rather than in a toast -- the user is looking at that page, and it is already where "why you cannot see this
     * file" is stated (see [openFilePreview]).
     */
    fun openFileExternally() {
        val preview = _uiState.value.filePreview ?: return
        val projectId = _uiState.value.currentProjectId
        viewModelScope.launch(ioDispatcher) {
            externalPreview.open(projectId, preview.path).onFailure { error ->
                val message = (error as? ExternalPreviewException)?.message ?: "无法用外部应用打开该文件"
                // Only write to the page if it is still showing the same file: a slow launch failure must not stamp a
                // notice onto a file the user has already navigated away from.
                _uiState.update { state ->
                    state.filePreview
                        ?.takeIf { it.path == preview.path }
                        ?.let { state.copy(filePreview = it.copy(notice = message)) }
                        ?: state
                }
            }
        }
    }

    fun closeFilePreview() {
        _uiState.update { it.copy(filePreview = null) }
    }

    fun dismissFilesError() {
        _uiState.update { it.copy(filesError = null) }
    }

    private fun loadFileDir(path: String) {
        val projectId = _uiState.value.currentProjectId
        _uiState.update { it.copy(filePath = path, filesBusy = true, filesError = null) }
        viewModelScope.launch(ioDispatcher) {
            projectFiles.list(projectId, path).fold(
                onSuccess = { listing ->
                    _uiState.update { it.copy(fileEntries = listing.entries, filesBusy = false) }
                },
                onFailure = {
                    // The reason for the failure (path escape / not a directory) has no actionable value for the user, so only
                    // "cannot read it" is reported and the tree is left empty -- more honest than a screen of stale entries.
                    _uiState.update {
                        it.copy(fileEntries = emptyList(), filesBusy = false, filesError = "无法读取该目录")
                    }
                }
            )
        }
    }

    /** Switch session: abort the current run (original semantics -- switching sessions means abandoning this execution) and re-project. */
    fun openSession(sessionId: String) {
        if (_uiState.value.currentSessionId == sessionId) return
        runSupervisor.stop()
        switchToSession(sessionId)
    }

    /** Switch the projection only, leaving the run alone: [openSession]'s takeover path (returning to the executing session after an Activity recreation). */
    private fun switchToSession(sessionId: String) {
        _uiState.update { it.copy(currentSessionId = sessionId, runError = null) }
        viewModelScope.launch(ioDispatcher) {
            refreshConversation()
            refreshTodos()
        }
    }

    /** Entry point to refresh the session list when the drawer opens (lists only the current project's sessions). */
    fun refreshSessions() {
        viewModelScope.launch(ioDispatcher) {
            refreshProjectsAndSessions()
        }
    }

    /**
     * Project / current-project change -> the session list's filter changed with it, so re-read the list.
     *
     * The filter must use the **registry's** current project ([ProjectStore.currentId]), never the id the projection
     * still holds: this method runs *because* the switch happened, so filtering by `it.currentProjectId` filters by the
     * project we just left. The drawer would then list the previous project's conversations under the new project's
     * name, and the new project's own conversations would stay out of reach until some other refresh happened to land.
     */
    private suspend fun refreshProjectsAndSessions() {
        val all = repository()?.listSessions().orEmpty()
        val currentProjectId = projectStore.currentId()
        val projects = projectStore.list()
        val souls = loadProjectSouls(projects)
        _uiState.update {
            it.copy(
                projects = projects,
                currentProjectId = currentProjectId,
                projectSouls = souls,
                sessions = sessionsFor(all, currentProjectId)
            )
        }
    }

    /** The registry decides which project is current; [AgentUiState.currentProjectId] only reports it (same rule as [refreshProjectsAndSessions]). */
    private fun sessionsForCurrentProject(all: List<SessionRecord>): List<SessionRecord> =
        sessionsFor(all, projectStore.currentId())

    /** pre-v1.3 rows (projectId=null) render as owned by the default project, never dangling. */
    private fun sessionsFor(all: List<SessionRecord>, projectId: String): List<SessionRecord> =
        all.filter { (it.projectId ?: ProjectLogic.DEFAULT_PROJECT_ID) == projectId }

    // ----- Input and run -----

    /**
     * Send input: when idle -> start a run; while running -> degrade to Steer (takes effect on the next loop step).
     *
     * v1.8: pending uploads ride along. They are converted here and cleared in the same `update` that hands
     * the prompt off -- a chip left behind would be attached again on the next message.
     *
     * Steering is text-only (design §6 node B): the input bar refuses to attach while a run is in flight
     * rather than letting an upload be silently dropped here.
     */
    fun send(text: String) {
        val trimmed = text.trim()
        val picked = _uiState.value.pendingUploads
        if (trimmed.isEmpty() && picked.isEmpty()) return
        val state = _uiState.value
        if (state.running) {
            runSupervisor.steer(trimmed)
            return
        }
        val attachments = PromptUploads.attachments(picked)
        // An upload-only turn still needs a title: the file name says more than "新对话" ever could.
        val titleSource = trimmed.ifBlank { picked.firstOrNull()?.name.orEmpty() }
        viewModelScope.launch(ioDispatcher) {
            val sessionId = state.currentSessionId ?: createSessionFor(titleSource)
            // Chips survive a refused start (model not configured): the pick is the expensive part, and the
            // user can retry after configuring instead of re-selecting the same file.
            if (startRun(sessionId, trimmed, attachments)) {
                _uiState.update { it.copy(pendingUploads = emptyList(), uploadError = null) }
            }
        }
    }

    // ----- Uploads (v1.8) -----

    /**
     * Pick an image / document. Conversion happens **now** rather than at send time: a format the app cannot
     * read has to be reported while the user is still standing at the picker, not after they hit send.
     */
    fun attachUpload(uri: String, name: String? = null) {
        if (_uiState.value.uploadBusy) return
        _uiState.update { it.copy(uploadBusy = true, uploadError = null) }
        viewModelScope.launch(ioDispatcher) {
            uploads.load(uri, name).fold(
                onSuccess = { upload ->
                    _uiState.update { it.copy(pendingUploads = it.pendingUploads + upload, uploadBusy = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(uploadBusy = false, uploadError = e.message ?: "无法添加该文件") }
                }
            )
        }
    }

    /** Drop one chip (the file was picked by mistake, or the payload is simply no longer wanted). */
    fun removeUpload(uploadId: String) {
        _uiState.update { state ->
            state.copy(pendingUploads = state.pendingUploads.filterNot { it.id == uploadId })
        }
        // The image blob was written at pick time and is not yet referenced by any session:
        // release it now or the file would sit there unreferenced forever.
        viewModelScope.launch(ioDispatcher) { uploads.release(uploadId) }
    }

    /**
     * Raw WebP bytes for a blob id (v1.9 thumbnails). Runs on the caller's dispatcher -- the Compose
     * side already wraps this in `Dispatchers.IO` (see `rememberImageThumb`). Null when no store is
     * wired or the blob is gone; the thumb then falls back to its placeholder.
     */
    fun loadImageBytes(imageId: String): ByteArray? = imageBlobs?.read(imageId)

    fun dismissUploadError() {
        _uiState.update { it.copy(uploadError = null) }
    }

    /** Q2: loop restart from the outside after a failure -- resume in the same session without appending any user message. */
    fun retry() {
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        if (state.running) return
        startRun(sessionId, initialPrompt = null)
    }

    /**
     * Edit a historical prompt and resend it (branch semantics): append a new UserPrompt under the parent of
     * the original message (a new branch; the old branch is kept), then move the leaf to the new branch and restart
     * the loop with `initialPrompt=null` -- the loop assembles the existing path directly, no re-enqueueing needed.
     */
    fun editAndResend(messageId: String, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return
        val state = _uiState.value
        if (state.running || state.currentSessionId == null) return
        val sessionId = state.currentSessionId
        viewModelScope.launch(ioDispatcher) {
            val repository = repository() ?: return@launch
            val node = withContext(ioDispatcher) {
                repository.listNodes(sessionId).firstOrNull { it.id == messageId }
            } ?: return@launch
            // An edited prompt keeps the uploads of the turn it replaces: re-asking the same question about
            // the same image should not quietly turn into a question with no image.
            repository.appendUserPrompt(sessionId, node.parentId, trimmed, attachmentsOf(node))
            startRun(sessionId, initialPrompt = null)
        }
    }

    /** Branch navigation: < > switch to an adjacent historical branch (the leaf moves to that branch's latest state). */
    fun switchBranch(messageId: String, direction: Int) {
        val state = _uiState.value
        if (state.running || state.currentSessionId == null) return
        val sessionId = state.currentSessionId
        viewModelScope.launch(ioDispatcher) {
            val repository = repository() ?: return@launch
            val siblings = withContext(ioDispatcher) { repository.promptSiblings(sessionId, messageId) }
            if (siblings.size < 2) return@launch
            val index = siblings.indexOfFirst { it.id == messageId }
            val targetIndex = index + direction
            if (targetIndex !in siblings.indices) return@launch
            val leaf = withContext(ioDispatcher) { repository.branchLeaf(sessionId, siblings[targetIndex].id) }
            repository.updateActiveLeaf(sessionId, leaf)
            refreshConversation()
            refreshTodos()
        }
    }

    /** The attachments of one persisted node; an unreadable payload means none (the edit itself still goes through). */
    private fun attachmentsOf(node: SessionNode): List<Attachment> {
        val repository = repository() ?: return emptyList()
        return runCatching {
            (NodePayloads.decodeMessage(repository.payload(node)) as? Message.User)?.attachments.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Run abort (matching ChatGPT's stop semantics): already-persisted nodes are kept; pending nodes are swept by the next run. */
    fun stopRun() {
        runSupervisor.stop()
    }

    fun respondApproval(allowed: Boolean) {
        approvalCoordinator.respond(allowed)
    }

    /** Feed back the result of the system grant dialog (v1.2). The executor still re-checks permissions; this only reports "what the user chose". */
    fun respondPermission(granted: Boolean) {
        permissionCoordinator.respond(granted)
    }

    /**
     * v1.6: the foreground service's persistent notification is the only visible sign that a task keeps
     * running once the app is in the background, and on Android 13+ (minSdk 34 here) the system **drops
     * that notification** while POST_NOTIFICATIONS is not granted -- the service still runs, but the
     * notification drawer stays empty, so "running in the foreground" looks like it does not work.
     *
     * Asked once per process, on first composition: a permission that is already granted resolves without
     * a dialog, and a denial is not re-asked (the platform itself stops showing the dialog after two
     * denials, and asking again on every run would only be noise). No prior state check is needed --
     * the system answers an already-granted permission immediately, and this VM has no Context.
     */
    fun ensureNotificationPermission() {
        if (notificationPermissionRequested) return
        notificationPermissionRequested = true
        viewModelScope.launch(ioDispatcher) {
            permissionCoordinator.request(
                listOf(Manifest.permission.POST_NOTIFICATIONS),
                "需要通知权限，才能在任务执行期间显示常驻通知（离开应用后继续运行）"
            )
        }
    }

    // ----- Provider / model selection -----

    /** Sync the selection state after an external config change (agent self-edit). */
    private fun refreshSelection() {
        val selection = owner.selection()
        _uiState.update {
            it.copy(
                providerAccounts = owner.accounts(),
                activeProviderId = selection?.providerId,
                activeModel = selection?.model.orEmpty(),
                modelConfigured = selection != null && selection.model.isNotBlank(),
                autoApproveNonDestructive = owner.autoApproveNonDestructive(),
                contextBudget = owner.contextBudget()
            )
        }
    }

    /** Settings screen: toggle auto-approval (destructive calls are unaffected and always show a dialog). */
    fun setAutoApproveNonDestructive(enabled: Boolean) {
        owner.setAutoApproveNonDestructive(enabled)
        _uiState.update { it.copy(autoApproveNonDestructive = owner.autoApproveNonDestructive()) }
    }

    /** Settings screen: manually set the context budget; null = fall back to automatic derivation (computed from the model's context length). */
    fun setContextBudgetOverride(tokens: Int?) {
        owner.setContextBudgetOverride(tokens)
        _uiState.update { it.copy(contextBudget = owner.contextBudget()) }
    }

    /** Settings screen: write only the selected project's Soul, then reflect the saved text in UI state. */
    fun saveProjectSoul(projectId: String, content: String) {
        viewModelScope.launch(ioDispatcher) {
            owner.saveProjectSoul(projectId, content)
            _uiState.update { state ->
                state.copy(projectSouls = state.projectSouls + (projectId to content))
            }
        }
    }

    /** Top-bar dynamic model switch: rejected while running (switching rebuilds the runtime and would interrupt the current run). */
    fun selectModel(providerId: String, model: String) {
        if (_uiState.value.running) {
            _uiState.update { it.copy(runError = "正在运行中，请先停止当前任务再切换模型") }
            return
        }
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return
        owner.applySelection(providerId, trimmed)
        _uiState.update {
            it.copy(
                activeProviderId = providerId,
                activeModel = trimmed,
                modelConfigured = true,
                // Re-read it: the attach entry must follow the new model, not the previous one's capability.
                activeModelSupportsVision = owner.activeModelSupportsVision()
            )
        }
    }

    /** Settings screen: save the provider's API key. */
    fun saveAccountKey(accountId: String, apiKey: String) {
        owner.saveAccountKey(accountId, apiKey)
        _uiState.update { it.copy(providerAccounts = owner.accounts()) }
    }

    /** Settings screen: add a custom endpoint. */
    fun addCustomAccount(name: String, baseUrl: String, apiKey: String) {
        owner.upsertCustomAccount(name, baseUrl, apiKey)
        _uiState.update { it.copy(providerAccounts = owner.accounts()) }
    }

    /** Settings screen: remove a custom endpoint (if it is the current selection, the selection state is cleared too). */
    fun removeCustomAccount(accountId: String) {
        owner.removeCustomAccount(accountId)
        val selection = owner.selection()
        _uiState.update {
            it.copy(
                providerAccounts = owner.accounts(),
                activeProviderId = selection?.providerId,
                activeModel = selection?.model.orEmpty(),
                modelConfigured = selection != null && selection.model.isNotBlank()
            )
        }
    }

    /** Chat screen / settings screen: fetch a provider's model list (GET /models). */
    suspend fun fetchModels(account: ProviderAccount): Result<List<ModelInfo>> {
        val result = owner.fetchModels(account)
        // Fetching the catalogue may have just taught us the *selected* model's vision capability, so the attach
        // entry has to be re-evaluated even though the selection itself didn't change.
        _uiState.update { it.copy(activeModelSupportsVision = owner.activeModelSupportsVision()) }
        return result
    }

    // ----- Internals -----

    private fun repository() = owner.current()?.repository

    private suspend fun createSessionFor(prompt: String): String {
        val title = prompt.lineSequence().firstOrNull().orEmpty().take(24).ifBlank { "新对话" }
        // Ownership comes from the registry, not from the projection: a message sent in the brief window between
        // switching projects and the list refreshing would otherwise be filed under the project just left behind.
        val record = repository()?.createSession(title, projectStore.currentId())
            ?: throw IllegalStateException("Runtime unavailable")
        _uiState.update {
            it.copy(
                currentSessionId = record.id,
                currentTitle = record.title,
                sessions = listOf(record) + it.sessions
            )
        }
        return record.id
    }

    /**
     * Start a run; false = refused (no model configured) and nothing was handed off.
     * Driving is handed to [runSupervisor] (it brings up the foreground service to stay alive
     * itself); this VM only handles pre-checks and optimistic UI feedback. [AgentUiState.running]'s real source is the supervisor's state flow.
     */
    private fun startRun(
        sessionId: String,
        initialPrompt: String?,
        attachments: List<Attachment> = emptyList()
    ): Boolean {
        if (owner.current() == null) {
            _uiState.update { it.copy(runError = "模型未配置，请先在设置中完成配置") }
            return false
        }
        _uiState.update { it.copy(running = true, runError = null, iteration = 0) }
        runSupervisor.start(sessionId, initialPrompt, attachments)
        return true
    }

    private suspend fun onEvent(event: AgentStepEvent) {
        when (event) {
            // The running flag is maintained by the supervisor's state flow (it is already true by the time LoopStarted arrives).
            is AgentStepEvent.LoopStarted -> Unit

            // User messages / steers become visible as soon as they are persisted (no waiting for the next ModelTurn).
            is AgentStepEvent.InboundPersisted -> refreshConversation()

            is AgentStepEvent.ModelTurn -> {
                // promptTokens is exactly the context size actually sent in this turn -- that is what compaction looks at,
                // so it is surfaced to the settings screen as the "current context usage" directly.
                _uiState.update { it.copy(contextPromptTokens = event.promptTokens) }
                refreshConversation()
            }

            is AgentStepEvent.ToolStarted ->
                refreshConversation()

            is AgentStepEvent.ToolCompleted -> {
                refreshConversation()
                refreshTodos()
            }

            is AgentStepEvent.ToolRejected -> refreshConversation()

            is AgentStepEvent.ContextCompacted -> refreshConversation()

            is AgentStepEvent.CompactionSkipped -> Unit

            // Follow-ups are persisted only on the next drain, and the following ModelTurn triggers the refresh.
            is AgentStepEvent.FollowUpInjected -> Unit

            is AgentStepEvent.Completed -> {
                // The agent may have changed config during the run: land the pending rebuild (takes effect on the next run).
                owner.rebuildRuntimeIfNeeded()
                refreshSelection()
                refreshConversation()
                refreshTodos()
                refreshSessions()
                // Lazy push: this run may have written to the workspace, so send the output to the cloud. Push only, never pull; zero network when nothing changed.
                oneDrive.pushAfterRun(_uiState.value.currentProjectId)
            }

            is AgentStepEvent.Failed -> {
                owner.rebuildRuntimeIfNeeded()
                _uiState.update {
                    it.copy(runError = event.cause.message ?: event.cause::class.simpleName)
                }
                refreshConversation()
            }
        }
    }

    /** Rebuild the conversation projection from persisted facts (the active path -> UiMessage; the full node set provides branch info). */
    private suspend fun refreshConversation() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val repository = repository() ?: return
        val path = withContext(ioDispatcher) { repository.activePath(sessionId) }
        val allNodes = withContext(ioDispatcher) { repository.listNodes(sessionId) }
        // The change log shares its source with the nodes: while projecting the conversation the diffs are pasted back onto the matching tool entries (v1.5).
        val changes = withContext(ioDispatcher) { projectFiles.changes(sessionId) }
        val messages = ConversationRenderer.render(path, allNodes, changes) { node -> repository.payload(node) }
        val title = withContext(ioDispatcher) { repository.getSession(sessionId)?.title }
        _uiState.update {
            it.copy(messages = messages, currentTitle = title ?: it.currentTitle, newFiles = newFilesOf(changes))
        }
    }

    /**
     * Data for the "new files" card at the bottom: files created in this session that **still exist right now**;
     * repeated creations at the same path keep only the latest entry. Deleted / moved files do not stay on the card -- an entry that opens a 404 is worse than none.
     */
    private fun newFilesOf(changes: List<FileChange>): List<FileChange> {
        if (changes.isEmpty()) return emptyList()
        val gone = changes
            .filter { it.kind == FileChangeKind.Deleted || it.kind == FileChangeKind.Moved }
            .flatMap { listOfNotNull(it.path, it.toPath) }
            .toSet()
        return changes.asReversed()
            .filter { it.kind == FileChangeKind.Created && it.path !in gone }
            .distinctBy { it.path }
    }

    private suspend fun refreshTodos() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val state = withContext(ioDispatcher) { repository()?.loadState(sessionId) } ?: return
        _uiState.update { it.copy(todos = state.todos) }
    }
}
