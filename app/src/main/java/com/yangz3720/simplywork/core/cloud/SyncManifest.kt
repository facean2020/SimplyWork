package com.yangz3720.simplywork.core.cloud

import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-file baseline from the last sync. Deciding "has this changed locally" no longer relies on comparing timestamps across clocks;
 * instead the local file's current `size + mtime` is compared against the values recorded here:
 * different = touched locally. This is a lightweight variant of rsync, avoiding full-content hashing.
 */
@Serializable
data class SyncEntry(
    val itemId: String,
    val eTag: String,
    val localModifiedAtMs: Long,
    val localSizeBytes: Long
)

/**
 * Sync manifest of one project ↔ one cloud folder. It lives **outside the sandbox** (`agent_workspace/sync/`),
 * otherwise the manifest itself would be synced up and would pollute the incremental decisions.
 *
 * [folders] maps relative paths (project root = empty string) to driveItem ids;
 * it does two jobs: resolving cloud entries into logical paths, and remembering the ids of newly created directories so children can be uploaded.
 */
@Serializable
data class SyncManifest(
    val version: Int = 1,
    /** Id of the bound cloud folder (= the empty-string entry of [folders], stored redundantly for self-validation). */
    val folderId: String,
    val deltaLink: String? = null,
    val files: Map<String, SyncEntry> = emptyMap(),
    val folders: Map<String, String> = emptyMap()
)

/**
 * JSON file storage for manifests (one file per project). The project id is validated with [AgentWorkspace.ProjectIdRegex]
 * before the path is concatenated -- the same path-safety constraints as the sandbox, never allowing `../` to escape.
 */
class SyncManifestStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(projectId: String): File {
        require(AgentWorkspace.ProjectIdRegex.matches(projectId)) { "invalid project id: '$projectId'" }
        return File(dir, "$projectId.json")
    }

    /** Unreadable or corrupt both return null: the caller treats it as "baseline not yet established" and rebuilds in full (P2, no silence). */
    fun load(projectId: String): SyncManifest? {
        val file = file(projectId)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(SyncManifest.serializer(), file.readText()) }.getOrNull()
    }

    fun save(projectId: String, manifest: SyncManifest) {
        dir.mkdirs()
        // Write a temp file first, then rename atomically: being killed mid-sync never leaves half a JSON file behind.
        val tmp = File(dir, "${projectId}.json.tmp")
        tmp.writeText(json.encodeToString(SyncManifest.serializer(), manifest))
        if (!tmp.renameTo(file(projectId))) {
            file(projectId).writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete(projectId: String) {
        file(projectId).delete()
    }
}
