package com.yangz3720.simplywork.core.cloud

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** A connected OneDrive account (for display; the access token never leaves the auth layer). */
data class OneDriveAccount(val id: String, val username: String)

/** One entry in the cloud folder picker. [path] is the hierarchical path shown to the user (`Work/ProjectA`). */
data class CloudFolder(val id: String, val name: String, val path: String)

/**
 * The transient sync state; the UI only ever uses it to show one line of text. Deliberately no
 * progress percentage: mirror sync is a short "either it finishes or it errors" transaction,
 * and a fake progress bar would only lie to the user.
 */
sealed interface SyncState {
    data object Idle : SyncState

    data class Working(val message: String) : SyncState

    /** Finished; [message] is a summary such as "uploaded 2 / downloaded 1". */
    data class Info(val message: String) : SyncState

    data class Error(val message: String) : SyncState
}

/**
 * The UI-facing OneDrive seam (v1.4, isomorphic to `AgentRuntimeOwner`: the VM depends only on
 * this interface, JVM tests use a fake, and the implementation lives in
 * `infrastructure/cloud/onedrive/OneDriveService`).
 *
 * Sync semantics (the user's call): **two-way mirror + lazy**. Binding does the first alignment
 * round, and the direction is decided by whether the local project has files: an empty project
 * -> pull from the cloud; a project with files -> upload (if both sides have content, binding is
 * refused). After that it acts at only two moments: push after an agent run finishes, and pull
 * on entering a project (throttled). No polling, no background tasks, no Webhooks.
 */
interface OneDriveOwner {

    val account: StateFlow<OneDriveAccount?>

    val syncState: StateFlow<SyncState>

    /** Whether msalClientId has been filled in gradle.properties. When unconfigured the UI shows a hint rather than failing blankly. */
    val configured: Boolean

    /** Restores the signed-in account on cold start (idempotent). */
    suspend fun restore()

    /** Interactive sign-in (needs an Activity to launch the authorization page). */
    suspend fun connect(activity: Activity): Result<Unit>

    /** Sign out. Clears the account only; **never touches** any project binding or local file. */
    suspend fun disconnect(): Result<Unit>

    /** Lists the subfolders under [parentId]; `parentId = "root"` means the drive root. */
    suspend fun listFolders(parentId: String, parentPath: String): Result<List<CloudFolder>>

    /** Binds to a cloud folder and completes the first alignment round; the direction is decided by whether the local project has files. */
    suspend fun bindFolder(projectId: String, folder: CloudFolder): Result<SyncReport>

    /** Creates a folder under [parentId] and binds it; a freshly created directory is necessarily empty, so the direction is again decided locally. */
    suspend fun createAndBindFolder(
        projectId: String,
        parentId: String,
        parentPath: String,
        name: String
    ): Result<SyncReport>

    /** Unbinds: drops the sync baseline; local and cloud each keep what they have. */
    suspend fun unbind(projectId: String): Result<Unit>

    /**
     * Pull + push. With [throttled] = true, a pull that arrives sooner than the interval since
     * the last one is skipped and null is returned (the automatic sync on entering/switching
     * projects takes this path); an explicit user tap passes false to force a run.
     */
    suspend fun sync(projectId: String, throttled: Boolean): Result<SyncReport?>

    /** The lazy push after one agent run finishes (push only, no pull). Returns null when unbound or signed out. */
    suspend fun pushAfterRun(projectId: String): Result<SyncReport?>
}
