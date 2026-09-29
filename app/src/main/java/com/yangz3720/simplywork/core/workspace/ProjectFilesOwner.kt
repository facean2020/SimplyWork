package com.yangz3720.simplywork.core.workspace

/**
 * Project file seam (v1.5): read-only browsing / preview + the read face of the session change log + the clearing face used when a project is deleted.
 *
 * The UI touches the workspace only through it, so a ViewModel need neither know [AgentWorkspace]'s path-safety rules
 * nor fabricate Android dependencies in tests -- [LocalProjectFiles] is pure `java.io`, and a temp directory
 * is enough to run the real implementation (no mock).
 */
interface ProjectFilesOwner {

    /** Lists a directory ([path] is relative to the project root, empty = root). */
    suspend fun list(projectId: String, path: String): Result<AgentWorkspace.Listing>

    /** Reads a text preview (the read-side cap matches fs_read; `truncated = true` when exceeded). */
    suspend fun read(projectId: String, path: String): Result<AgentWorkspace.FileContent>

    /** Changes accumulated for the current session (old → new). */
    suspend fun changes(sessionId: String): List<FileChange>

    /** Deletes a session's change records (when the session is deleted). */
    suspend fun deleteSession(sessionId: String)

    /** Deletes the whole project sandbox directory (when the project is deleted); a missing directory is a no-op. */
    suspend fun deleteProject(projectId: String)
}

class LocalProjectFiles(
    private val workspace: AgentWorkspace,
    private val changeLog: FileChangeLog
) : ProjectFilesOwner {

    // What runCatching captures here is a failure of a synchronous file operation (path escape / not a regular file / IO);
    // there is no suspension point here, so there is no question of swallowing a cancellation along with it.
    override suspend fun list(projectId: String, path: String): Result<AgentWorkspace.Listing> =
        runCatching { workspace.list(projectId, path) }

    override suspend fun read(projectId: String, path: String): Result<AgentWorkspace.FileContent> =
        runCatching { workspace.read(projectId, path) }

    override suspend fun changes(sessionId: String): List<FileChange> = changeLog.changes(sessionId)

    override suspend fun deleteSession(sessionId: String) {
        changeLog.delete(sessionId)
    }

    override suspend fun deleteProject(projectId: String) {
        workspace.deleteProject(projectId)
    }
}
