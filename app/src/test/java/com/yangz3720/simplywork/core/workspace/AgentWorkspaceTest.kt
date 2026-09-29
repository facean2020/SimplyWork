package com.yangz3720.simplywork.core.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Agent sandbox's (v1.3) hard constraints: path-escape protection, size caps, delete semantics.
 * Pure File logic, run straight against a JVM temp directory.
 */
class AgentWorkspaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun workspace(): AgentWorkspace = AgentWorkspace(tmp.root)

    @Test
    fun `write and read round trip with auto parent dirs`() {
        val ws = workspace()
        ws.write("p1", "docs/notes.md", "hello")
        val read = ws.read("p1", "docs/notes.md")
        assertEquals("hello", read.content)
        assertFalse(read.truncated)
        assertEquals(File(tmp.root, "p1/docs/notes.md").canonicalPath, File(tmp.root, "p1/docs/notes.md").canonicalPath)
        assertTrue(File(tmp.root, "p1/docs/notes.md").exists())
    }

    @Test
    fun `parent traversal escape is refused`() {
        val ws = workspace()
        assertThrows(WorkspaceException::class.java) { ws.write("p1", "../../evil.txt", "x") }
        assertThrows(WorkspaceException::class.java) { ws.read("p1", "../secret.txt") }
        assertThrows(WorkspaceException::class.java) { ws.list("p1", "../..") }
        // never lands outside the sandbox
        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
    }

    @Test
    fun `absolute path is refused`() {
        val ws = workspace()
        assertThrows(IllegalArgumentException::class.java) { ws.write("p1", "/etc/passwd", "x") }
    }

    @Test
    fun `invalid project id is refused`() {
        val ws = workspace()
        assertThrows(IllegalArgumentException::class.java) { ws.projectDir("../evil") }
        assertThrows(IllegalArgumentException::class.java) { ws.projectDir("") }
    }

    @Test
    fun `oversized write is rejected with actionable message`() {
        val ws = workspace()
        ws.maxFileBytes = 16
        assertThrows(IllegalArgumentException::class.java) { ws.write("p1", "big.txt", "x".repeat(17)) }
    }

    @Test
    fun `oversized read is truncated and declared`() {
        val ws = workspace()
        ws.write("p1", "big.txt", "abcdefghij") // write first with the default cap
        ws.maxFileBytes = 8                      // then shrink the cap to check truncation on the read side
        val content = ws.read("p1", "big.txt")
        assertEquals("abcdefgh", content.content)
        assertTrue(content.truncated)
        assertEquals(10L, content.sizeBytes)
    }

    @Test
    fun `list caps entries and reports truncated`() {
        val ws = workspace()
        ws.maxListEntries = 3
        repeat(5) { ws.write("p1", "f$it.txt", "x") }
        val listing = ws.list("p1")
        assertEquals(3, listing.entries.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun `move refuses existing target and works when free`() {
        val ws = workspace()
        ws.write("p1", "a.txt", "aaa")
        ws.write("p1", "b.txt", "bbb")
        assertThrows(WorkspaceException::class.java) { ws.move("p1", "a.txt", "b.txt") }
        ws.move("p1", "a.txt", "c.txt")
        assertEquals("aaa", ws.read("p1", "c.txt").content)
    }

    @Test
    fun `delete needs recursive flag for non-empty dirs and protects root`() {
        val ws = workspace()
        ws.write("p1", "dir/a.txt", "x")
        assertThrows(WorkspaceException::class.java) { ws.delete("p1", "dir") }
        val result = ws.delete("p1", "dir", recursive = true)
        assertEquals(2, result.deletedEntries) // the directory itself + 1 file
        assertThrows(WorkspaceException::class.java) { ws.delete("p1", "") }
        assertThrows(WorkspaceException::class.java) { ws.delete("p1", ".", recursive = true) }
    }

    @Test
    fun `projects are isolated by directory`() {
        val ws = workspace()
        ws.write("projA", "file.txt", "A")
        ws.write("projB", "file.txt", "B")
        assertEquals("A", ws.read("projA", "file.txt").content)
        assertEquals("B", ws.read("projB", "file.txt").content)
        assertEquals(1, ws.list("projA").entries.size)
    }

    @Test
    fun `deleteProject removes the whole project tree and leaves siblings alone`() {
        val ws = workspace()
        ws.write("p1", "docs/notes.md", "hi")
        ws.write("p2", "keep.txt", "keep")

        assertTrue(ws.deleteProject("p1"))

        assertFalse(File(tmp.root, "p1").exists())
        assertTrue(File(tmp.root, "p2/keep.txt").exists())
    }

    @Test
    fun `deleteProject on a missing project reports done and never re-creates it`() {
        val ws = workspace()

        // deleteRecursively returns true for "was not there to begin with", which means the same as "it is gone once deleted"
        assertTrue(ws.deleteProject("never-used"))

        // deleting a directory that does not exist must not incidentally mkdirs it into being (projectDir would create it; this path deliberately does not go through that)
        assertFalse(File(tmp.root, "never-used").exists())
    }

    @Test
    fun `deleteProject refuses a traversing project id`() {
        val ws = workspace()
        assertThrows(IllegalArgumentException::class.java) { ws.deleteProject("../evil") }
        assertFalse(File(tmp.root.parentFile, "evil").exists())
    }
}