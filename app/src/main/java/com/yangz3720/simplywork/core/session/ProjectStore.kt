package com.yangz3720.simplywork.core.session

import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Project -> OneDrive folder binding (v1.4). What is bound is a **folder** (a driveItem id),
 * not a file: the project's sandbox directory is mirrored into that folder as a whole tree.
 *
 * [deltaLink] is the Graph incremental cursor: with it in hand, each sync only pulls what changed
 * "after that point", and together with [lastSyncedAt] it makes up lazy sync (no polling, no
 * background tasks). It advances with every sync.
 */
@Serializable
data class OneDriveBinding(
    /** The cloud folder's driveItem id (the sync primary key; never store a path, since renames/moves change it). */
    val folderId: String,
    /** The folder name for display (including its parent path, e.g. `Work/ProjectA`). */
    val folderPath: String,
    val deltaLink: String? = null,
    val lastSyncedAt: Long = 0L,
    val boundAt: Long = 0L
)

/**
 * A "project" (v1.3, following Qwen Studio / GPT project-filing semantics, the user's framing
 * being "sessions belong to a project"):
 * one project = one filing unit = the unit of session ownership + a sandbox workspace directory
 * (`filesDir/agent_workspace/projects/<projectId>/`, managed by [com.yangz3720.simplywork.core.workspace.AgentWorkspace]).
 *
 * The number of projects is small (single digits to a few dozen), so reading and writing the
 * whole SharedPreferences JSON blob is good enough; session ownership lives in the
 * `sessions.project_id` column (SqliteSessionStore v2). Old session rows with
 * `project_id = NULL` are the [DEFAULT_PROJECT_ID] semantic fallback and are not backfilled.
 *
 * [oneDrive] being null = no cloud binding (v1.4). New fields carry default values, so old JSON
 * decodes as is; snapshotting/serialising this structure never leaks a token -- tokens live only
 * in MSAL's own cache.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val oneDrive: OneDriveBinding? = null
)

/** Pure logic (the single arbitration point, JVM-testable): name validation, dedup, default project. */
object ProjectLogic {

    /** The default project with a stable id: it takes over every pre-v1.3 session (projectId == null). */
    const val DEFAULT_PROJECT_ID = "default"
    const val DEFAULT_PROJECT_NAME = "默认项目"

    const val MAX_NAME_LENGTH = 48
    val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** @return the validated name, or the error message (the model can self-correct from it; P2 explicit failure). */
    fun validateName(raw: String): String? = when {
        raw.isBlank() -> "project name must not be blank"
        raw.length > MAX_NAME_LENGTH -> "project name must be at most $MAX_NAME_LENGTH characters (got ${raw.length})"
        else -> null
    }

    fun create(projects: List<Project>, rawName: String, nowMs: Long): Result<Project> {
        validateName(rawName)?.let { return Result.failure(IllegalArgumentException(it)) }
        val name = rawName.trim()
        // Case-insensitive name dedup: creating a project with a duplicate name is a common slip
        // by the model, so reject it explicitly instead of producing two directories that differ
        // only in case and making the user guess in the switcher which one is which.
        projects.firstOrNull { it.name.equals(rawName.trim(), ignoreCase = true) }?.let {
            return Result.failure(IllegalArgumentException("project '${it.name}' already exists (${it.id})"))
        }
        return Result.success(
            Project(id = UUID.randomUUID().toString(), name = rawName.trim(), createdAt = nowMs)
        )
    }

    fun byId(projects: List<Project>, id: String): Project? = projects.firstOrNull { it.id == id }
}

/**
 * The project registry: the project list + the current-project pointer. The JSON goes into
 * `SharedPreferences` as one blob (the same persistence convention as
 * [com.yangz3720.simplywork.ui.agent.ProviderKeyring]); concurrency is limited to the single
 * process's UI/agent loop, so there is no locking (consistent with keyring).
 *
 * [changes] exists for the UI layer to observe and refresh on (switching projects changes the
 * session list's filter).
 */
class ProjectStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _changes = MutableStateFlow(0)

    /** Incremented on every project / current-project change; the UI refreshes the session list from it. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    init {
        ensureDefault()
    }

    fun list(): List<Project> = decode().sortedBy { it.createdAt }

    fun byId(id: String): Project? = decode().firstOrNull { it.id == id }

    fun currentId(): String {
        val stored = prefs.getString(CURRENT_KEY, null)
        // The current pointer went stale (the project was cleared externally) -> fall back to the
        // default project, never return an id that does not exist (P2).
        val projects = decode()
        return if (projects.any { it.id == stored }) stored!! else ProjectLogic.DEFAULT_PROJECT_ID
    }

    fun current(): Project = byId(currentId()) ?: decode().firstOrNull { it.id == ProjectLogic.DEFAULT_PROJECT_ID }
        ?: Project(ProjectLogic.DEFAULT_PROJECT_ID, ProjectLogic.DEFAULT_PROJECT_NAME, 0L)

    fun create(name: String, nowMs: Long = System.currentTimeMillis()): Result<Project> {
        val existing = decode()
        val created = ProjectLogic.create(existing, name, nowMs)
            .getOrElse { return Result.failure(it) }
        encode(existing + created)
        bump()
        return Result.success(created)
    }

    /** @return false when [id] does not exist (no silent switch, P2). */
    fun switchTo(id: String): Boolean {
        if (byId(id) == null) return false
        prefs.edit().putString(CURRENT_KEY, id).apply()
        bump()
        return true
    }

    /**
     * Delete a project: this only touches the registry. The sessions, sandbox directory, change
     * log and cloud baseline under that project are cleared in order by the UI layer
     * ([com.yangz3720.simplywork.ui.agent.AgentViewModel.deleteProject]) -- the registry knows
     * nothing about the session DB and the workspace, and shoehorning those in as well would make
     * this layer fat and impossible to unit test.
     *
     * The default project cannot be deleted: it is the fallback home of every pre-v1.3 session
     * (see [currentId]), and deleting it would leave those sessions nowhere to go.
     *
     * @return failure when [id] is the default project or does not exist (explicit failure, no
     * silent drop, P2).
     */
    fun delete(id: String): Result<Unit> {
        if (id == ProjectLogic.DEFAULT_PROJECT_ID) {
            return Result.failure(IllegalArgumentException("默认项目不可删除"))
        }
        val existing = decode()
        if (existing.none { it.id == id }) {
            return Result.failure(IllegalArgumentException("未知项目：$id"))
        }
        encode(existing.filterNot { it.id == id })
        // The current pointer names the deleted project -> fall back to the default, never leave a
        // dangling pointer (the same rule as [currentId]).
        if (prefs.getString(CURRENT_KEY, null) == id) {
            prefs.edit().putString(CURRENT_KEY, ProjectLogic.DEFAULT_PROJECT_ID).apply()
        }
        bump()
        return Result.success(Unit)
    }

    // ----- OneDrive binding (v1.4) -----

    fun oneDriveBinding(projectId: String): OneDriveBinding? = byId(projectId)?.oneDrive

    /** Every project id bound to the cloud (the sync coordinator enumerates these instead of doing a full scan). */
    fun boundProjectIds(): List<String> = decode().filter { it.oneDrive != null }.map { it.id }

    /**
     * The single write entry point for a binding: `transform` receives the current binding
     * (possibly null) and returns the new binding; returning null means unbind. Bind / unbind /
     * advancing deltaLink / recording the sync time all go through here, so the invariant
     * "a project has exactly one cloud folder" cannot be routed around.
     *
     * @return false when [projectId] does not exist (explicit failure, no silent drop of a write).
     */
    fun updateOneDrive(projectId: String, transform: (OneDriveBinding?) -> OneDriveBinding?): Boolean {
        val existing = decode()
        val project = existing.firstOrNull { it.id == projectId } ?: return false
        val updated = transform(project.oneDrive)
        encode(existing.map { if (it.id == projectId) it.copy(oneDrive = updated) else it })
        bump()
        return true
    }

    private fun ensureDefault() {
        if (byId(ProjectLogic.DEFAULT_PROJECT_ID) == null) {
            encode(
                decode() + Project(
                    id = ProjectLogic.DEFAULT_PROJECT_ID,
                    name = ProjectLogic.DEFAULT_PROJECT_NAME,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun decode(): List<Project> = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString(ListSerializer(Project.serializer()), it) }
    }.getOrNull().orEmpty()

    private fun encode(projects: List<Project>) {
        prefs.edit().putString(KEY, json.encodeToString(ListSerializer(Project.serializer()), projects)).apply()
    }

    private fun bump() {
        _changes.value += 1
    }

    private companion object {
        const val KEY = "projects"
        const val CURRENT_KEY = "current_project_id"
    }
}