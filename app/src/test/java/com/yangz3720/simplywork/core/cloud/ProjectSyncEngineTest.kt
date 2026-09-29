package com.yangz3720.simplywork.core.cloud

import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The behavioural contract of the two-way mirror engine (v1.4). It all runs against an in-memory fake
 * cloud plus a real filesystem: mirror correctness is almost entirely about judging "who touched it, who should override whom", so this layer has to be locked down.
 */
class ProjectSyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val projectId = "p1"

    private fun workspace() = AgentWorkspace(File(tmp.root, "projects"))

    private fun engine(ws: AgentWorkspace) =
        ProjectSyncEngine(ws, SyncManifestStore(File(tmp.root, "sync")))

    /** The same engine with a squeezed mirror cap: the skip logic can then be covered with real small files instead of forging 16 MiB. */
    private fun engine(ws: AgentWorkspace, maxSyncFileBytes: Long) =
        ProjectSyncEngine(ws, SyncManifestStore(File(tmp.root, "sync")), maxSyncFileBytes = maxSyncFileBytes)

    // ----- first bind: both directions -----

    @Test
    fun `binding uploads the whole project when the cloud folder is new and the project has files`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "docs/notes.md", "hello")
        ws.write(projectId, "todo.txt", "todo")
        val drive = FakeDrive()

        val report = engine(ws)
            .createFolderAndBind(drive, projectId, "ROOT", "项目A", "项目A")
            .getOrThrow()

        assertEquals(2, report.uploaded)
        assertEquals("hello", drive.contentOf("项目A/docs/notes.md"))
        assertEquals("todo", drive.contentOf("项目A/todo.txt"))
        assertTrue((report.binding.deltaLink ?: "").isNotEmpty())
        assertTrue(report.binding.folderId.isNotEmpty())
    }

    @Test
    fun `binding downloads the whole cloud tree when the project is empty`() = runBlockingTest {
        val ws = workspace()
        val drive = FakeDrive()
        val folder = drive.createFolder("ROOT", "项目A")
        drive.createFolder(folder.id, "sub")
        drive.upload(folder.id, "readme.md", "from cloud".toByteArray())
        drive.upload(drive.idOf("项目A/sub"), "deep.txt", "deeper".toByteArray())

        val report = engine(ws).bind(drive, projectId, folder.id, "项目A").getOrThrow()

        assertEquals(2, report.downloaded)
        assertEquals(0, report.uploaded)
        assertEquals("from cloud", ws.read(projectId, "readme.md").content)
        assertEquals("deeper", ws.read(projectId, "sub/deep.txt").content)
    }

    @Test
    fun `binding to a file is refused`() = runBlockingTest {
        val ws = workspace()
        val drive = FakeDrive()
        val folder = drive.createFolder("ROOT", "项目A")
        drive.upload(folder.id, "a.txt", "x".toByteArray())

        val result = engine(ws).bind(drive, projectId, drive.idOf("项目A/a.txt"), "项目A/a.txt")

        assertTrue(result.isFailure)
    }

    @Test
    fun `binding the drive root is refused`() = runBlockingTest {
        val ws = workspace()
        val drive = FakeDrive()

        // the drive root has no parent: mirroring the whole drive would blow delete propagation up beyond control, so it must be blocked
        val result = engine(ws).bind(drive, projectId, "ROOT", "")

        assertTrue(result.isFailure)
    }

    @Test
    fun `binding is refused when both the project and the cloud folder have files`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "local.txt", "mine")
        val drive = FakeDrive()
        val folder = drive.createFolder("ROOT", "项目A")
        drive.upload(folder.id, "cloud.txt", "theirs".toByteArray())

        val result = engine(ws).bind(drive, projectId, folder.id, "项目A")

        assertTrue(result.isFailure)
        // a refusal must mean "nothing happened": neither download nor upload, both sides left exactly as they were
        assertFalse(File(ws.projectDir(projectId), "cloud.txt").exists())
        assertEquals("theirs", drive.contentOf("项目A/cloud.txt"))
        assertEquals("mine", ws.read(projectId, "local.txt").content)
    }

    // ----- oversized-file guardrail (whole files pass through memory, so without a cap comes OOM) -----

    @Test
    fun `an oversized cloud file is skipped instead of being pulled into memory`() = runBlockingTest {
        val ws = workspace()
        val drive = FakeDrive()
        val folder = drive.createFolder("ROOT", "项目A")
        drive.upload(folder.id, "small.txt", "ok".toByteArray())
        drive.upload(folder.id, "huge.bin", ByteArray(300))

        val report = engine(ws, maxSyncFileBytes = 256)
            .bind(drive, projectId, folder.id, "项目A").getOrThrow()

        assertEquals(1, report.downloaded)
        assertEquals("ok", ws.read(projectId, "small.txt").content)
        assertFalse(File(ws.projectDir(projectId), "huge.bin").exists())
        assertTrue(report.warnings.any { it.contains("跳过 1 个") })
    }

    @Test
    fun `an oversized local file is not uploaded`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "small.txt", "ok")
        ws.writeBytes(projectId, "huge.bin", ByteArray(300))
        val drive = FakeDrive()

        val report = engine(ws, maxSyncFileBytes = 256)
            .createFolderAndBind(drive, projectId, "ROOT", "项目A", "项目A").getOrThrow()

        assertEquals(1, report.uploaded)
        assertEquals("ok", drive.contentOf("项目A/small.txt"))
        assertFalse(drive.exists("项目A/huge.bin"))
        assertTrue(report.warnings.any { it.contains("跳过 1 个") })
    }

    // ----- lazy push -----

    @Test
    fun `pushOnly makes no remote call when nothing changed locally`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "a.txt", "v1")
        val drive = FakeDrive()
        val engine = engine(ws)
        val binding = engine.createFolderAndBind(drive, projectId, "ROOT", "项目A", "项目A").getOrThrow().binding
        val uploadsBefore = drive.uploads
        val deltasBefore = drive.deltaCalls.size

        val report = engine.pushOnly(drive, projectId, binding).getOrThrow()

        assertEquals(0, report.uploaded)
        assertEquals(uploadsBefore, drive.uploads)
        // push only, never pull: pushOnly must not fire a single delta call
        assertEquals(deltasBefore, drive.deltaCalls.size)
    }

    @Test
    fun `pushOnly uploads only the file that changed`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "a.txt", "v1")
        ws.write(projectId, "b.txt", "stable")
        val drive = FakeDrive()
        val engine = engine(ws)
        val binding = engine.createFolderAndBind(drive, projectId, "ROOT", "项目A", "项目A").getOrThrow().binding
        val uploadsBefore = drive.uploads

        ws.write(projectId, "a.txt", "v2-changed")
        val report = engine.pushOnly(drive, projectId, binding).getOrThrow()

        assertEquals(1, report.uploaded)
        assertEquals(uploadsBefore + 1, drive.uploads)
        assertEquals("v2-changed", drive.contentOf("项目A/a.txt"))
    }

    // ----- the two directions of deletion -----

    @Test
    fun `sync removes a local file that was deleted in the cloud`() = runBlockingTest {
        val ws = workspace()
        val drive = FakeDrive()
        val folder = drive.createFolder("ROOT", "项目A")
        drive.upload(folder.id, "gone.txt", "bye".toByteArray())
        drive.upload(folder.id, "stays.txt", "hi".toByteArray())
        val engine = engine(ws)
        val binding = engine.bind(drive, projectId, folder.id, "项目A").getOrThrow().binding
        assertTrue(File(ws.projectDir(projectId), "gone.txt").exists())

        drive.deleteItem(drive.idOf("项目A/gone.txt"))
        val report = engine.sync(drive, projectId, binding).getOrThrow()

        assertEquals(1, report.deletedLocal)
        assertFalse(File(ws.projectDir(projectId), "gone.txt").exists())
        assertTrue(File(ws.projectDir(projectId), "stays.txt").exists())
    }

    @Test
    fun `sync deletes the cloud copy of a file removed locally`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "doomed.txt", "x")
        ws.write(projectId, "survivor.txt", "y")
        val drive = FakeDrive()
        val engine = engine(ws)
        val binding = engine.createFolderAndBind(drive, projectId, "ROOT", "项目A", "项目A").getOrThrow().binding

        ws.delete(projectId, "doomed.txt")
        val report = engine.sync(drive, projectId, binding).getOrThrow()

        assertEquals(1, report.deletedRemote)
        assertFalse(drive.exists("项目A/doomed.txt"))
        assertEquals("y", drive.contentOf("项目A/survivor.txt"))
    }

    @Test
    fun `sync refuses to wipe the cloud when the local workspace looks emptied`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "keep.txt", "important")
        val drive = FakeDrive()
        val engine = engine(ws)
        val binding = engine.createFolderAndBind(drive, projectId, "ROOT", "项目A", "项目A").getOrThrow().binding

        // simulate the sandbox being wiped (reinstall / cleared data): not one local file is left, yet the manifest still remembers what the cloud holds
        ws.delete(projectId, "keep.txt")
        val report = engine.sync(drive, projectId, binding).getOrThrow()

        assertEquals(0, report.deletedRemote)
        assertTrue(report.warnings.isNotEmpty())
        // the cloud must be kept as-is -- deleting it from the manifest wipes the user's drive
        assertEquals("important", drive.contentOf("项目A/keep.txt"))
    }

    // ----- conflicts and cursors -----

    @Test
    fun `a two-sided change keeps the local version and reports the conflict`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "a.txt", "v1")
        val drive = FakeDrive()
        val engine = engine(ws)
        val binding = engine.createFolderAndBind(drive, projectId, "ROOT", "项目A", "项目A").getOrThrow().binding

        ws.write(projectId, "a.txt", "local-wins")
        drive.upload(binding.folderId, "a.txt", "remote-edit".toByteArray())

        val report = engine.sync(drive, projectId, binding).getOrThrow()

        assertEquals(listOf("a.txt"), report.localWins)
        assertEquals("local-wins", ws.read(projectId, "a.txt").content)
        assertEquals("local-wins", drive.contentOf("项目A/a.txt"))
    }

    @Test
    fun `an expired delta cursor rebuilds the baseline instead of failing`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "a.txt", "v1")
        val drive = FakeDrive()
        val engine = engine(ws)
        val binding = engine.createFolderAndBind(drive, projectId, "ROOT", "项目A", "项目A").getOrThrow().binding

        drive.expireCursorOnce = true
        val report = engine.sync(drive, projectId, binding).getOrThrow()

        assertEquals("v1", drive.contentOf("项目A/a.txt"))
        assertTrue("410 之后必须留下可用的新游标", (report.binding.deltaLink ?: "").isNotEmpty())
    }

    @Test
    fun `a nested directory created locally is created in the cloud`() = runBlockingTest {
        val ws = workspace()
        ws.write(projectId, "a.txt", "v1")
        val drive = FakeDrive()
        val engine = engine(ws)
        val binding = engine.createFolderAndBind(drive, projectId, "ROOT", "项目A", "项目A").getOrThrow().binding

        ws.write(projectId, "deep/nested/file.txt", "deep")
        engine.pushOnly(drive, projectId, binding).getOrThrow()

        assertEquals("deep", drive.contentOf("项目A/deep/nested/file.txt"))
    }

    /** No real delayed dispatch is needed in tests; just run things in order. */
    private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

    // ----- in-memory fake cloud -----

    /**
     * In-memory OneDrive: a monotonically increasing [revision] models Graph's incremental semantics -- `deltaLink`
     * encodes "which version has been seen so far", and later changes are filtered out by `revision > since`. Deleted items come
     * back carrying `deleted = true` (matching Graph: for a deleted driveItem only the id is reliable).
     */
    private class FakeDrive : RemoteDrive {

        private class Node(
            val id: String,
            val name: String,
            val parentId: String?,
            val isFolder: Boolean,
            var eTag: String,
            var revision: Int,
            var deleted: Boolean = false,
            var bytes: ByteArray = ByteArray(0)
        )

        private val nodes = LinkedHashMap<String, Node>()
        private var revision = 0
        private var nextId = 1
        var uploads = 0
        val deltaCalls = mutableListOf<String>()
        var expireCursorOnce = false

        init {
            nodes["ROOT"] = Node("ROOT", "", null, true, "etag-0", 0)
        }

        private fun live(): List<Node> = nodes.values.filterNot { it.deleted }

        fun idOf(path: String): String {
            val (node, _) = resolve(path) ?: error("no such cloud path: $path")
            return node.id
        }

        fun contentOf(path: String): String = resolve(path)?.first?.bytes?.decodeToString()
            ?: error("no such cloud path: $path")

        fun exists(path: String): Boolean = resolve(path) != null

        private fun resolve(path: String): Pair<Node, String>? {
            var parent: Node = nodes.getValue("ROOT")
            var rest = path
            while (true) {
                val (head, tail) = if ('/' in rest) rest.substringBefore('/') to rest.substringAfter('/') else rest to ""
                val child = live().firstOrNull { it.parentId == parent.id && it.name == head } ?: return null
                if (tail.isEmpty()) return child to parent.id
                parent = child
                rest = tail
            }
        }

        private fun inSubtree(node: Node, rootId: String): Boolean {
            var current: Node? = node
            while (current != null) {
                if (current.id == rootId) return true
                current = current.parentId?.let { nodes[it] }
            }
            return false
        }

        private fun bump(node: Node) {
            revision++
            node.revision = revision
            node.eTag = "etag-$revision"
        }

        override suspend fun folder(folderId: String): RemoteItem {
            val node = nodes[folderId]?.takeIf { !it.deleted } ?: error("no such folder: $folderId")
            return node.toItem()
        }

        override suspend fun children(folderId: String): List<RemoteItem> =
            live().filter { it.parentId == folderId }.map { it.toItem() }

        override suspend fun createFolder(parentFolderId: String, name: String): RemoteItem {
            require(live().none { it.parentId == parentFolderId && it.name == name }) {
                "nameAlreadyExists: $name"
            }
            val node = Node("id-${nextId++}", name, parentFolderId, true, "", 0)
            bump(node)
            nodes[node.id] = node
            return node.toItem()
        }

        override suspend fun delta(folderId: String, deltaLink: String?): RemoteDelta {
            deltaCalls += "delta:${deltaLink ?: "full"}"
            if (expireCursorOnce && deltaLink != null) {
                expireCursorOnce = false
                throw DeltaCursorExpired("fake 410")
            }
            val scoped = nodes.values.filter { inSubtree(it, folderId) && it.id != folderId }
            if (deltaLink == null) {
                return RemoteDelta(
                    items = scoped.filterNot { it.deleted }.map { it.toItem() },
                    deltaLink = "delta:$revision",
                    reset = true
                )
            }
            val since = deltaLink.removePrefix("delta:").toInt()
            return RemoteDelta(
                items = scoped.filter { it.revision > since }
                    .map { if (it.deleted) it.toItem().copy(deleted = true) else it.toItem() },
                deltaLink = "delta:$revision",
                reset = false
            )
        }

        override suspend fun download(itemId: String): ByteArray =
            nodes[itemId]?.bytes ?: error("no such item: $itemId")

        override suspend fun upload(parentFolderId: String, fileName: String, bytes: ByteArray): RemoteItem {
            uploads++
            val existing = live().firstOrNull {
                it.parentId == parentFolderId && it.name == fileName && !it.isFolder
            }
            val node = existing ?: Node("id-${nextId++}", fileName, parentFolderId, false, "", 0)
                .also { nodes[it.id] = it }
            node.bytes = bytes
            bump(node)
            return node.toItem()
        }

        override suspend fun deleteItem(itemId: String) {
            val node = nodes[itemId] ?: return
            nodes.values.filter { inSubtree(it, itemId) }.forEach {
                it.deleted = true
                bump(it)
            }
            if (!node.deleted) node.deleted = true
        }

        private fun Node.toItem() = RemoteItem(
            id = id,
            name = name,
            isFolder = isFolder,
            eTag = eTag,
            modifiedAtMs = revision.toLong(),
            sizeBytes = bytes.size.toLong(),
            parentId = parentId,
            deleted = deleted
        )
    }
}
