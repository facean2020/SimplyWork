package com.yangz3720.simplywork.core.workspace

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Persistence of the change log: isolated per session, readable across instances, corrupt files do not blow up, and a session id cannot be used as a path.
 */
class JsonFileChangeLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun change(sessionId: String, path: String, atMs: Long) = FileChange(
        sessionId = sessionId,
        projectId = "p1",
        path = path,
        kind = FileChangeKind.Created,
        atMs = atMs
    )

    @Test
    fun `records and reads back per session in order`() = runBlocking {
        val log = JsonFileChangeLog(tmp.newFolder())

        log.record(change("s1", "a.md", 1))
        log.record(change("s1", "b.md", 2))
        log.record(change("s2", "c.md", 3))

        assertEquals(listOf("a.md", "b.md"), log.changes("s1").map { it.path })
        assertEquals(listOf("c.md"), log.changes("s2").map { it.path })
        assertTrue(log.changes("unknown").isEmpty())
    }

    @Test
    fun `diff lines survive the round trip`() = runBlocking {
        val dir = tmp.newFolder()
        val log = JsonFileChangeLog(dir)
        log.record(
            change("s1", "a.md", 1).copy(
                kind = FileChangeKind.Updated,
                addedLines = 1,
                removedLines = 1,
                diff = listOf(
                    TextDiff.Line(TextDiff.LineKind.Removed, "old"),
                    TextDiff.Line(TextDiff.LineKind.Added, "new")
                ),
                omittedLines = 3
            )
        )

        val read = JsonFileChangeLog(dir).changes("s1").single()
        assertEquals(FileChangeKind.Updated, read.kind)
        assertEquals(1, read.addedLines)
        assertEquals(1, read.removedLines)
        assertEquals(3, read.omittedLines)
        assertEquals(listOf('-', '+'), read.diff.map { it.prefix })
        assertEquals(listOf("old", "new"), read.diff.map { it.text })
    }

    @Test
    fun `keeps only the newest entries per session`() = runBlocking {
        val log = JsonFileChangeLog(tmp.newFolder(), maxPerSession = 2)
        log.record(change("s1", "a.md", 1))
        log.record(change("s1", "b.md", 2))
        log.record(change("s1", "c.md", 3))

        assertEquals(listOf("b.md", "c.md"), log.changes("s1").map { it.path })
    }

    @Test
    fun `corrupt file reads as empty instead of failing the projection`() = runBlocking {
        val dir = tmp.newFolder()
        File(dir, "s1.json").writeText("{not json at all")
        assertTrue(JsonFileChangeLog(dir).changes("s1").isEmpty())
    }

    @Test
    fun `session id cannot escape the log directory`() {
        val log = JsonFileChangeLog(tmp.newFolder())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { log.record(change("../../escape", "a.md", 1)) }
        }
    }

    @Test
    fun `delete drops only the target session file`() = runBlocking {
        val dir = tmp.newFolder()
        val log = JsonFileChangeLog(dir)
        log.record(change("s1", "a.md", 1))
        log.record(change("s2", "b.md", 2))

        log.delete("s1")

        assertFalse(File(dir, "s1.json").exists())
        assertTrue(log.changes("s1").isEmpty())
        assertEquals(listOf("b.md"), log.changes("s2").map { it.path })
    }
}
