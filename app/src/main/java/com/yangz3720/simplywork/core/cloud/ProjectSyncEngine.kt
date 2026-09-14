package com.yangz3720.simplywork.core.cloud

import com.yangz3720.simplywork.core.session.OneDriveBinding
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import java.io.File

/**
 * 一次绑定/同步做了什么的投影，供 UI 一行摘要（不上报就不算成功，P2）。
 * [localWins] 非空意味着双向都改过同一路径、本地覆盖了云端，用户需要知情。
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
 * 项目沙箱 ↔ 云端文件夹的**双向镜像**（v1.4）。纯逻辑 + [RemoteDrive] 端口注入 →
 * JVM 可测，不碰 Android、不碰网络实现。
 *
 * 四条不变量：
 * 1. **id 是主键，路径只是推导结果**：目录/文件重命名在云端只换 name，路径随之变化，
 *    所以删除只能靠清单把 itemId 反查回路径。
 * 2. **拉取只覆盖"本地没动过"的文件**：本地自上次同步后也改过的同路径，判为冲突，
 *    本地胜出（agent 的产出在本地，覆盖会丢工作成果），交给 push 覆盖云端。
 * 3. **绝不因沙箱疑似被清空而清空云端**：本地一个文件都不剩而清单非空时拒绝删除云端，
 *    改为产出 warning（重装/清数据的场景下照清单删云端 = 抹掉用户云盘）。
 * 4. **绑定不接受"两边都有内容"**：首轮方向只由本地有没有文件决定，两边都非空时
 *    宁可拒绝并让人来选，也不自动猜（见 [bind]）。
 */
class ProjectSyncEngine(
    private val workspace: AgentWorkspace,
    private val manifests: SyncManifestStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * 单个文件的镜像上限。下载与上传都是**整文件过一遍内存**（[RemoteDrive.download]
     * 返回 ByteArray），所以不设上限时一个视频就能把 App 打崩 —— 实测把空项目绑到含大
     * 文件的云端文件夹会抛 `OutOfMemoryError`。超过上限的文件**两边都不动**，只出一条
     * 汇总提示：宁可镜像不完整，也不能崩。
     */
    private val maxSyncFileBytes: Long = DEFAULT_MAX_SYNC_FILE_BYTES
) {

    /**
     * 绑定到云端文件夹并完成**首轮对齐**。方向由本地工作区有没有文件决定：
     * - 本地没有文件 → 云端为事实源，全量拉取下来；
     * - 本地有文件、云端文件夹是空的 → 本地为事实源，整棵递交上去；
     * - **两边都有文件 → 拒绝绑定**。没有可比的时钟或版本号，自动选一边就可能覆盖
     *   用户的工作成果，所以把选择权交回人（P0）。
     *
     * 网盘根目录不可绑定（[RemoteItem.parentId] 为 null 即根）：整盘镜像会把删除
     * 传播放大到不可控范围。
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

        // reset 全量枚举：既拿到云端现状，也拿到可用的 delta 基线。
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
     * 在云端**新建**文件夹并立刻绑定。新建出来的目录必然是空的，所以首轮方向仍由
     * 本地有没有文件决定（见 [bind]）。用 fail 冲突策略：同名已存在会显式报错，
     * 而不是把两个项目悄悄打进同一个云端目录。
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

    /** 增量同步：先按 deltaLink 拉云端的改动，再把本地的改动推上去。 */
    suspend fun sync(
        remote: RemoteDrive,
        projectId: String,
        binding: OneDriveBinding
    ): Result<SyncReport> = runCatching {
        val baseline = manifests.load(projectId)
            ?: throw IllegalStateException("这个项目还没有同步过，请先选一个云端文件夹")
        val acc = Acc()
        // 游标过期（HTTP 410）不是失败：以全量枚举重建基线，reset=true 让引擎
        // 仍能识别"云端已删但清单里还有"的条目。
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

    /** 解除绑定：只丢基线，云端与本地各自保留现状（绝不顺手删任何一边）。 */
    fun unbind(projectId: String) {
        manifests.delete(projectId)
    }

    /**
     * 只推送、不拉取。给"一轮 agent run 刚跑完"用：本地刚被写过，需要的是把产出送上去，
     * 而不是再拉一遍云端。因为只在本地 stat 与基线不同时才发请求，无改动时**零网络调用**
     * —— 这是懒惰同步省电的关键。
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

    // ----- 拉取 -----

    /**
     * 把一批云端变化应用到本地清单/磁盘：登记新增/修改并落盘，按清单删除本地已消失
     * 的条目。
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

        // 1) 云端条目 → 逻辑路径。父目录可能在本批次里才出现，迭代到不动点
        //    （delta 批次带整条新建目录链，一两轮即收敛）。
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

        // 2) 删除集合 = 增量删除项 + （全量重建时）清单有而枚举里没有的项
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
                if (path.isEmpty()) return@let // 绑定的根永不删
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

        // 3) 落盘云端的新增/修改
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
            if (entry != null && entry.eTag == item.eTag) continue // 云端未变
            val local = File(root, path)
            val stat = local.takeIf { it.isFile }?.let { LocalStat(it.lastModified(), it.length()) }
            val localChanged = entry != null && stat != null &&
                (stat.sizeBytes != entry.localSizeBytes || stat.modifiedAtMs != entry.localModifiedAtMs)
            if (localChanged) {
                acc.localWins += path // 冲突：本地胜出，交给 push
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

    // ----- 推送 -----

    /** 把本地沙箱的改动推到云端：新的/改过的上传，本地删掉的云端也删。 */
    private suspend fun push(
        remote: RemoteDrive,
        projectId: String,
        manifest: SyncManifest,
        acc: Acc
    ): SyncManifest {
        val root = workspace.projectDir(projectId)
        val folders = LinkedHashMap(manifest.folders)
        val files = LinkedHashMap(manifest.files)

        // 目录浅→深，保证父目录 id 先就位（新嵌套目录一次 push 内全部建好）
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

        // 文件：新增或本地 stat 与基线不同 → 上传
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
            // 上传同样整文件过内存，本地突然出现一个大文件（模型写的、用户放的）也要挡住。
            if (stat.sizeBytes > maxSyncFileBytes) {
                acc.oversized += 1
                return@forEach
            }
            val item = remote.upload(parentId, path.substringAfterLast('/'), workspace.readBytes(projectId, path))
            files[path] = SyncEntry(item.id, item.eTag.orEmpty(), stat.modifiedAtMs, stat.sizeBytes)
            acc.uploaded++
        }

        // 删除云端：本地已删的条目。若本地一个文件都不剩而清单非空，判为沙箱被清空
        // （重装/清数据），拒绝照清单清空用户云盘 —— 只报 warning。
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

    // ----- 本地遍历 -----

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

    // ----- 辅助 -----

    /** 云端文件名不可含分隔符，也不可为 `.`/`..`；其余一律照收。 */
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

    /** `inner` 是为了读外层的 [maxSyncFileBytes] 拼出跳过提示。 */
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
        /** 16 MiB：文档、代码、笔记绰绰有余，又不至于让整文件缓冲把堆撑爆。 */
        const val DEFAULT_MAX_SYNC_FILE_BYTES = 16L * 1024 * 1024
    }
}
