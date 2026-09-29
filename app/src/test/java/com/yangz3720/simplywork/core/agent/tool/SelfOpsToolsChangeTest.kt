package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.core.session.ProjectLogic
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.core.session.SessionRepositoryImpl
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.FileChangeKind
import com.yangz3720.simplywork.core.workspace.InMemoryFileChangeLog
import com.yangz3720.simplywork.core.workspace.TextDiff
import com.yangz3720.simplywork.testsupport.InMemoryPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The change log of the fs_* tools (v1.5): what the card reports must be the **real diff** -- both the
 * line counts and the diff lines come from the two real contents before and after the write, not from "how many lines the new content has". A failed operation leaves no record.
 */
class SelfOpsToolsChangeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val workspace by lazy { AgentWorkspace(tmp.root) }
    private val changes = InMemoryFileChangeLog()
    private val projects = ProjectStore(InMemoryPrefs())
    // lazy: TemporaryFolder's directory is only created in before(), so touching root during field initialisation would throw.
    private val tools by lazy {
        SelfOpsTools.registeredTools(
            repository = SessionRepositoryImpl(InMemorySessionStore(), InMemoryPayloadStore()),
            config = FakeSelfConfig(),
            workspace = workspace,
            projects = projects,
            changes = changes
        )
    }

    private class FakeSelfConfig : AgentSelfConfig {
        override fun snapshot() = AgentSelfConfig.Snapshot(
            activeProviderId = null,
            activeModel = "",
            timeoutMs = 60_000L,
            accounts = emptyList(),
            projects = emptyList(),
            currentProjectId = ProjectLogic.DEFAULT_PROJECT_ID
        )

        override suspend fun apply(mutation: AgentSelfConfig.Mutation) =
            AgentSelfConfig.ApplyResult(emptyList(), rebuildPending = false, errors = emptyList())
    }

    private fun execute(name: String, arguments: String, toolCallId: String = "c1"): JsonObject = runBlocking {
        val tool = tools.first { it.definition.name == name }
        val parsed = tool.parse(Json.parseToJsonElement(arguments).jsonObject).getOrThrow()
        tool.execute(parsed, ToolExecutionContext("s1", toolCallId))
    }

    private fun recorded() = runBlocking { changes.changes("s1") }

    @Test
    fun `writing a new file records Created with every line added`() {
        execute("fs_write", """{"path":"docs/notes.md","content":"# 标题\n正文"}""")

        val change = recorded().single()
        assertEquals("docs/notes.md", change.path)
        assertEquals(FileChangeKind.Created, change.kind)
        assertEquals("c1", change.toolCallId)
        assertEquals(2, change.addedLines)
        assertEquals(0, change.removedLines)
        assertEquals(listOf(TextDiff.LineKind.Added, TextDiff.LineKind.Added), change.diff.map { it.kind })
    }

    @Test
    fun `overwriting an existing file records the real difference`() {
        workspace.write(ProjectLogic.DEFAULT_PROJECT_ID, "a.md", "a\nb\nc")
        execute("fs_write", """{"path":"a.md","content":"a\nX\nc"}""")

        val change = recorded().single()
        assertEquals(FileChangeKind.Updated, change.kind)
        assertEquals(1, change.addedLines)
        assertEquals(1, change.removedLines)
        assertEquals(listOf(" a", "-b", "+X", " c"), change.diff.map { "${it.prefix}${it.text}" })
    }

    @Test
    fun `appending records only the appended lines`() {
        workspace.write(ProjectLogic.DEFAULT_PROJECT_ID, "a.md", "a\nb")
        execute("fs_write", """{"path":"a.md","content":"\nc","append":true}""")

        val change = recorded().single()
        assertEquals(FileChangeKind.Appended, change.kind)
        assertEquals(1, change.addedLines)
        assertEquals(0, change.removedLines)
        assertEquals("a\nb\nc", workspace.read(ProjectLogic.DEFAULT_PROJECT_ID, "a.md").content)
    }

    @Test
    fun `deleting a file records the removed lines`() {
        workspace.write(ProjectLogic.DEFAULT_PROJECT_ID, "a.md", "a\nb")
        execute("fs_delete", """{"path":"a.md"}""")

        val change = recorded().single()
        assertEquals(FileChangeKind.Deleted, change.kind)
        assertEquals(0, change.addedLines)
        assertEquals(2, change.removedLines)
        assertEquals(listOf("-a", "-b"), change.diff.map { "${it.prefix}${it.text}" })
    }

    @Test
    fun `moving a file records both paths and no content diff`() {
        workspace.write(ProjectLogic.DEFAULT_PROJECT_ID, "a.md", "a")
        execute("fs_move", """{"from":"a.md","to":"b.md"}""")

        val change = recorded().single()
        assertEquals(FileChangeKind.Moved, change.kind)
        assertEquals("a.md", change.path)
        assertEquals("b.md", change.toPath)
        assertEquals(null, change.addedLines)
        assertTrue(change.diff.isEmpty())
    }

    @Test
    fun `a refused write leaves no record`() {
        val output = execute("fs_write", """{"path":"../../evil.txt","content":"x"}""")

        assertEquals("error", output["status"]!!.jsonPrimitive.content)
        assertTrue(recorded().isEmpty())
    }
}
