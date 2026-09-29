package com.yangz3720.simplywork.core.cloud

import com.yangz3720.simplywork.core.session.OneDriveBinding
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import java.io.File

/**
 * A projection of what one bind/sync actually did, for the UI's one-line summary (P2: if it was
 * not reported, it did not happen).
 * A non-empty [localWins] means both sides changed the same path and the local copy overwrote
 * the cloud one, so the user needs to know about it.
 */
data class SyncReport(
    val binding: OneDriveBinding,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
    val localWins: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * **Two-way mirror** between a project sandbox and a cloud folder (v1.4). Pure logic with the
 * [RemoteDrive] port injected -> testable on the JVM, touching neither Android nor the network
 * implementation.
 *
 * Four invariants:
 * 1. **id is the primary key, the path is only a derived result**: renaming a directory/file in
 *    the cloud only swaps its name there and the path follows, so a delete can only work by
 *    looking the itemId back up to a path through the manifest.
 * 2. **A pull only overwrites files "the local side has not touched"**: the same path that was
 *    also modified locally since the last sync counts as a conflict and the local side wins
 *    (the agent's output lives locally, and overwriting it would throw away work), leaving push
 *    to overwrite the cloud copy.
 * 3. **Never empty the cloud just because the sandbox looks emptied**: when not one file is
 *    left locally while the manifest is non-empty, refuse to delete from the cloud and emit a
 *    warning instead (deleting the cloud per the manifest after a reinstall / data clear =
 *    erasing the user's drive).
 * 4. **A bind does not accept "both sides have content"**: the direction of the first round is
 *    decided purely by whether the local side has files, and when both are non-empty it would
 *    rather refuse and let a human choose than guess (see [bind]).
 */
class ProjectSyncEngine(
    private val workspace: AgentWorkspace,
    private val manifests: SyncManifestStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * The mirror cap for a single file. Both download and upload push the **whole file through
     * memory** ([RemoteDrive.download] returns a ByteArray), so with no cap a single video is
     * enough to crash the app -- measured: binding an empty project to a cloud folder holding a
     * large file throws `OutOfMemoryError`. Files over the cap are **left untouched on both
     * sides**, with just one aggregated hint: an incomplete mirror beats a crash.
     */
    private val maxSyncFileBytes: Long = DEFAULT_MAX_SYNC_FILE_BYTES
) {

    /**
     * Binds to a cloud folder and completes the **first alignment round**. The direction is
     * decided by whether the local workspace has files:
     * - no local files -> the cloud is the source of truth, pull everything down;
     * - local files while the cloud folder is empty -> the local side is the source of truth,
     *   submit the whole tree;
     * - **both sides have files -> refuse the bind**. There is no comparable clock or version
     *   number, and picking a side automatically could overwrite the user's work, so the choice
     *   goes back to the human (P0).
     *
     * The drive root cannot be bound ([RemoteItem.parentId] being null means root): mirroring a
     * whole drive would blow delete propagation up to an uncontrollable scale.
     */
    suspend fun bind(
        remote: RemoteDrive,
        projectId: String,
        folderId: String,
        folderPath: String
    ): Result<SyncReport> = runCatching {
        val folder = remote.folder(folderId)
        require(folder.isFolder) { "'$folderPath' 不是文件夹：项目只能绑定到文件夹" }
        require(folder.parentId != null) { "不能绑定网盘根目录，请进入下一层挑选一个文件夹" }

        // A reset full enumeration: it hands back both the current cloud state and a usable
        // delta baseline.
        val delta = remote.delta(folder.id, null)
        val acc = Acc()
        val seed = SyncManifest(folderId = folder.id, folders = mapOf("" to folder.id))

        val manifest = if (localFiles(workspace.projectDir(projectId)).isEmpty()) {
            applyDelta(remote, projectId, delta, seed, acc = acc)
        } else {
            val cloudFiles = delta.items.count { !it.deleted && !it.isFolder && it.id != folder.id }
            if (cloudFiles > 0) {
                throw IllegalStateException(
                    "云端文件夹「$folderPath」里已经有 $cloudFiles 个文件，而这个项目里也有文件。" +
                        "两边都有内容时无法安全合并，请改选一个空的云端文件夹。"
                )
            }
            push(remote, projectId, seed, acc)
        }
        manifests.save(projectId, manifest.copy(deltaLink = delta.deltaLink))
        acc.toReport(binding(folder.id, folderPath, delta.deltaLink))
    }

    /**
     * **Creates** a folder in the cloud and binds it straight away. A freshly created directory
     * is necessarily empty, so the direction of the first round is still decided by whether the
     * local side has files (see [bind]). Uses the fail conflict policy: a same-named folder that
     * already exists errors out explicitly instead of quietly cramming two projects into one
     * cloud directory.
     */
    suspend fun createFolderAndBind(
        remote: RemoteDrive,
        projectId: String,
        parentFolderId: String,
        folderName: String,
        folderPath: String
    ): Result<SyncReport> = runCatching {
        val created = remote.createFolder(parentFolderId, folderName)
        bind(remote, projectId, created.id, folderPath).getOrThrow()
    }

    /** Incremental sync: pull the cloud's changes via deltaLink first, then push the local changes up. */
    suspend fun sync(
        remote: RemoteDrive,
        projectId: String,
        binding: OneDriveBinding
    ): Result<SyncReport> = runCatching {
        val baseline = manifests.load(projectId)
            ?: throw IllegalStateException("这个项目还没有同步过，请先选一个云端文件夹")
        val acc = Acc()
        // An expired cursor (HTTP 410) is not a failure: rebuild the baseline from a full
        // enumeration, and reset=true still lets the engine recognise "deleted in the cloud but
        // still in the manifest" entries.
        val delta = try {
            remote.delta(baseline.folderId, baseline.deltaLink)
        } catch (e: DeltaCursorExpired) {
            remote.delta(baseline.folderId, null)
        }
        val pulled = applyDelta(remote, projectId, delta, baseline, acc = acc)
        val pushed = push(remote, projectId, pulled, acc)
        val next = pushed.copy(folderId = baseline.folderId, deltaLink = delta.deltaLink)
        manifests.save(projectId, next)
        acc.toReport(binding.copy(deltaLink = delta.deltaLink, lastSyncedAt = nowMs()))
    }

    /** Unbind: drop the baseline only; the cloud and the local side each keep what they have (never incidentally delete either side). */
    fun unbind(projectId: String) {
        manifests.delete(projectId)
    }

    /**
     * Push only, no pull. For "an agent run just finished": the local side was just written to,
     * so what is needed is sending the output up, not pulling the cloud down a second time.
     * Because a request only goes out when the local stat differs from the baseline, a run with
     * no changes makes **zero network calls** -- that is the key to lazy sync saving power.
     */
    suspend fun pushOnly(
        remote: RemoteDrive,
        projectId: String,
        binding: OneDriveBinding
    ): Result<SyncReport> = runCatching {
        val manifest = manifests.load(projectId)
            ?: throw IllegalStateException("这个项目还没有同步过，请先选一个云端文件夹")
        val acc = Acc()
        val pushed = push(remote, projectId, manifest, acc)
        manifests.save(projectId, pushed)
        acc.toReport(binding.copy(lastSyncedAt = nowMs()))
    }

    // ----- pull -----

    /**
     * Applies a batch of cloud changes onto the local manifest/disk: registers the additions and
     * modifications and persists them, then deletes the local entries that are gone from the
     * cloud, according to the manifest.
     */
    private suspend fun applyDelta(
        remote: RemoteDrive,
        projectId: String,
        delta: RemoteDelta,
        manifest: SyncManifest,
        acc: Acc
    ): SyncManifest {
        val folders = LinkedHashMap(manifest.folders)
        val files = LinkedHashMap(manifest.files)
        val byId = delta.items.associateBy { it.id }

        // 1) Cloud entries -> logical paths. The parent directory may only appear within this
        //    very batch, so iterate to a fixed point (a delta batch carries the whole chain of
        //    newly created directories, so one or two rounds converge).
        val idToPath = HashMap<String, String>().apply { folders.forEach { (p, id) -> this[id] = p } }
        var pending = delta.items.filter { !it.deleted && it.id != manifest.folderId }
            .filter { safeName(it.name) != null || it.isFolder }
        while (pending.isNotEmpty()) {
            val stuck = ArrayList<RemoteItem>()
            for (item in pending) {
                val parentPath = item.parentId?.let { idToPath[it] }
                if (parentPath == null) stuck += item else idToPath[item.id] = join(parentPath, item.name)
            }
            if (stuck.size == pending.size) {
                acc.warnings += "有 ${stuck.size} 个云端条目无法定位到父目录，本次跳过"
                break
            }
            pending = stuck
        }

        // 2) Delete set = incremental deleted items + (on a full rebuild) manifest entries that are absent from the enumeration
        val deletedIds = LinkedHashSet<String>()
        delta.items.filter { it.deleted }.forEach { deletedIds += it.id }
        if (delta.reset) {
            files.values.map { it.itemId }.filterNot { it in idToPath }.forEach { deletedIds += it }
            folders.filterKeys { it.isNotEmpty() }.values.filterNot { it in idToPath }.forEach { deletedIds += it }
        }

        val filePathByItem = files.entries.associate { (p, e) -> e.itemId to p }
        val folderPathByItem = folders.entries.associate { (p, id) -> id to p }
        for (id in deletedIds) {
            folderPathByItem[id]?.let { path ->
                if (path.isEmpty()) return@let // the bound root is never deleted
                if (runCatching { workspace.delete(projectId, path, recursive = true) }.isSuccess) acc.deletedLocal++
                files.keys.filter { it == path || it.startsWith("$path/") }.forEach { files.remove(it) }
                folders.keys.filter { it == path || it.startsWith("$path/") }.forEach { folders.remove(it) }
                return@let
            }
            filePathByItem[id]?.let { path ->
                if (runCatching { workspace.delete(projectId, path) }.isSuccess) acc.deletedLocal++
                files.remove(path)
            }
        }

        // 3) Write the cloud's additions/modifications to disk
        val root = workspace.projectDir(projectId)
        for ((id, path) in idToPath) {
            if (id == manifest.folderId || path.isEmpty()) continue
            val item = byId[id] ?: continue
            if (item.deleted) continue
            if (item.isFolder) {
                runCatching { workspace.mkdir(projectId, path) }
                folders[path] = id
                continue
            }
            val entry = files[path]
            if (entry != null && entry.eTag == item.eTag) continue // cloud unchanged
            val local = File(root, path)
            val stat = local.takeIf { it.isFile }?.let { LocalStat(it.lastModified(), it.length()) }
            val localChanged = entry != null && stat != null &&
                (stat.sizeBytes != entry.localSizeBytes || stat.modifiedAtMs != entry.localModifiedAtMs)
            if (localChanged) {
                acc.localWins += path // conflict: local wins, leave it to push
                continue
            }
            if (item.sizeBytes > maxSyncFileBytes) {
                acc.oversized += 1
                continue
            }
            workspace.writeBytes(projectId, path, remote.download(id))
            acc.downloaded++
            files[path] = SyncEntry(
                itemId = id,
                eTag = item.eTag.orEmpty(),
                localModifiedAtMs = local.lastModified(),
                localSizeBytes = local.length()
            )
        }
        return manifest.copy(folders = folders, files = files)
    }

    // ----- push -----

    /** Push the local sandbox's changes to the cloud: new/modified ones get uploaded, ones deleted locally get deleted in the cloud too. */
    private suspend fun push(
        remote: RemoteDrive,
        projectId: String,
        manifest: SyncManifest,
        acc: Acc
    ): SyncManifest {
        val root = workspace.projectDir(projectId)
        val folders = LinkedHashMap(manifest.folders)
        val files = LinkedHashMap(manifest.files)

        // Directories shallow -> deep, so parent ids are in place first (new nested directories all get created within one push)
        val dirs = localDirs(root)
        dirs.sortedBy { depth(it) }.forEach { path ->
            if (folders.containsKey(path)) return@forEach
            val parentId = folders[path.substringBeforeLast('/', "")]
            if (parentId == null) {
                acc.warnings += "父目录未登记，跳过新建云端目录：$path"
                return@forEach
            }
            folders[path] = remote.createFolder(parentId, path.substringAfterLast('/')).id
        }

        // Files: new, or a local stat differing from the baseline -> upload
        val local = localFiles(root)
        local.forEach { (path, stat) ->
            val entry = files[path]
            if (entry != null && entry.localSizeBytes == stat.sizeBytes && entry.localModifiedAtMs == stat.modifiedAtMs) {
                return@forEach
            }
            val parentId = folders[path.substringBeforeLast('/', "")]
            if (parentId == null) {
                acc.warnings += "父目录未登记，跳过上传：$path"
                return@forEach
            }
            // Upload pushes the whole file through memory too, so a large file that suddenly turns up locally (written by the model, dropped in by the user) has to be blocked as well.
            if (stat.sizeBytes > maxSyncFileBytes) {
                acc.oversized += 1
                return@forEach
            }
            val item = remote.upload(parentId, path.substringAfterLast('/'), workspace.readBytes(projectId, path))
            files[path] = SyncEntry(item.id, item.eTag.orEmpty(), stat.modifiedAtMs, stat.sizeBytes)
            acc.uploaded++
        }

        // Deleting in the cloud: entries that were deleted locally. If not a single file is left
        // locally while the manifest is non-empty, that is read as a cleared sandbox
        // (reinstall / data clear), so refuse to empty the user's drive per the manifest -- report
        // a warning only.
        val wipeGuard = local.isEmpty() && files.isNotEmpty()
        if (wipeGuard) {
            acc.warnings += "项目里已经没有文件，为免误删已跳过云端删除（云端 ${files.size} 个文件保持原样）"
        } else {
            (files.keys - local.keys).forEach { path ->
                val entry = files.remove(path) ?: return@forEach
                remote.deleteItem(entry.itemId)
                acc.deletedRemote++
            }
            (folders.keys - dirs - setOf("")).sortedByDescending { depth(it) }.forEach { path ->
                val id = folders.remove(path) ?: return@forEach
                remote.deleteItem(id)
                acc.deletedRemote++
            }
        }
        return manifest.copy(folders = folders, files = files)
    }

    // ----- local traversal -----

    private data class LocalStat(val modifiedAtMs: Long, val sizeBytes: Long)

    private fun localFiles(root: File): Map<String, LocalStat> {
        val out = LinkedHashMap<String, LocalStat>()
        walk(root, "") { file, path ->
            if (file.isFile) out[path] = LocalStat(file.lastModified(), file.length())
        }
        return out
    }

    private fun localDirs(root: File): Set<String> {
        val out = LinkedHashSet<String>()
        walk(root, "") { file, path -> if (file.isDirectory) out += path }
        return out
    }

    private fun walk(dir: File, prefix: String, onEntry: (File, String) -> Unit) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val path = join(prefix, file.name)
            onEntry(file, path)
            if (file.isDirectory) walk(file, path, onEntry)
        }
    }

    // ----- helpers -----

    /** A cloud file name must not contain a separator and must not be `.`/`..`; everything else is accepted as-is. */
    private fun safeName(name: String): String? =
        name.takeIf { it.isNotEmpty() && it != "." && it != ".." && !it.contains('/') && !it.contains('\\') }

    private fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

    private fun depth(path: String): Int = path.count { it == '/' }

    private fun binding(folderId: String, folderPath: String, deltaLink: String) = OneDriveBinding(
        folderId = folderId,
        folderPath = folderPath,
        deltaLink = deltaLink,
        lastSyncedAt = nowMs(),
        boundAt = nowMs()
    )

    /** `inner` is so the skip hint can read the outer [maxSyncFileBytes]. */
    private inner class Acc {
        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var oversized = 0
        val localWins = ArrayList<String>()
        val warnings = ArrayList<String>()

        fun toReport(binding: OneDriveBinding): SyncReport {
            val all = warnings.toList().let { base ->
                if (oversized == 0) base
                else base + "跳过 $oversized 个超过 ${maxSyncFileBytes / 1024 / 1024} MB 的文件"
            }
            return SyncReport(
                binding = binding,
                uploaded = uploaded,
                downloaded = downloaded,
                deletedLocal = deletedLocal,
                deletedRemote = deletedRemote,
                localWins = localWins.toList(),
                warnings = all
            )
        }
    }

    companion object {
        /** 16 MiB: ample for documents, code and notes, yet not enough to let whole-file buffering blow up the heap. */
        const val DEFAULT_MAX_SYNC_FILE_BYTES = 16L * 1024 * 1024
    }
}
