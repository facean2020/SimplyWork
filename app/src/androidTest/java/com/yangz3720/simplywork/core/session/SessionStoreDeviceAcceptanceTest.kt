package com.yangz3720.simplywork.core.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yangz3720.simplywork.core.session.store.JsonlPayloadStore
import com.yangz3720.simplywork.core.session.store.SqliteSessionStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device acceptance for session-core (agent-core-design.md §8.1 item 1):
 * the SQLite structure store + JSONL payload store under real Android storage.
 * These two classes are deliberately untestable on the JVM, so this run IS the
 * acceptance for them.
 */
@RunWith(AndroidJUnit4::class)
class SessionStoreDeviceAcceptanceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = File(context.filesDir, "agent-acceptance").apply { deleteRecursively(); mkdirs() }
        // SQLiteOpenHelper stores the DB under the app's databases/ dir — delete there.
        context.deleteDatabase("acceptance.db")
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun sqliteStoreFullCrudLeafAndRewire() {
        val store = SqliteSessionStore(context, dbFile().name)
        val sessionId = "sess-1"
        store.insertSession(SessionRecord(sessionId, "title", 1000L))

        // insertNode itself must NOT move the leaf; only updateActiveLeaf does.
        store.insertNode(node("n1", sessionId, null))
        store.insertNode(node("n2", sessionId, "n1"))
        assertEquals(null, store.getSession(sessionId)!!.activeLeafId)

        store.updateActiveLeaf(sessionId, "n2")
        store.insertNode(node("n3", sessionId, "n2"))
        store.updateActiveLeaf(sessionId, "n3")

        val nodes = store.listNodes(sessionId)
        assertEquals(listOf("n1", "n2", "n3"), nodes.map { it.id })
        assertEquals("n3", store.getSession(sessionId)!!.activeLeafId)

        // Status update persists.
        store.updateNodeStatus("n2", SessionNodeStatus.failed)
        assertEquals(
            SessionNodeStatus.failed,
            store.listNodes(sessionId).first { it.id == "n2" }.status
        )

        // Compaction rewiring: tail reparents under the summary node.
        store.insertNode(
            SessionNode(
                id = "summary", sessionId = sessionId, parentId = "n2",
                nodeType = SessionNodeType.Summary, tokenCount = 1,
                status = SessionNodeStatus.success, createdAt = 2L
            )
        )
        store.rewireParent("n3", "summary")
        assertEquals("summary", store.listNodes(sessionId).first { it.id == "n3" }.parentId)
        store.close()
    }

    @Test
    fun jsonlPayloadStoreRealFileIo() {
        val store = JsonlPayloadStore(File(baseDir, "payloads"))
        store.append("sess-1", "n1", "UserPrompt", """{"content":"hello\nworld"}""")
        store.append("sess-1", "n2", "ToolCall", """{"content":null,"toolCalls":[]}""")

        // Read returns the INNER payload; newline inside the JSON string must not break lines.
        val n1 = Json.parseToJsonElement(store.read("sess-1", "n1")!!).jsonObject
        assertEquals("hello\nworld", n1["content"]!!.jsonPrimitive.content)

        // Fresh instance over the same dir = process restart; index must rebuild.
        val second = JsonlPayloadStore(File(baseDir, "payloads"))
        assertEquals(2, second.loadAll("sess-1").size)
        assertEquals(
            "hello\nworld",
            Json.parseToJsonElement(second.read("sess-1", "n1")!!).jsonObject["content"]!!.jsonPrimitive.content
        )

        // The on-disk file really is one record per line.
        val lines = File(baseDir, "payloads/sess-1.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
    }

    @Test
    fun repositoryEndToEndOnRealStores() = runBlocking {
        val repository = SessionRepositoryImpl(
            SqliteSessionStore(context, dbFile().name),
            JsonlPayloadStore(File(baseDir, "payloads"))
        )
        val session = repository.createSession("device acceptance")

        // Chain: system → user → assistant(toolCalls) [pending] → (synthetic) tool.
        val system = repository.appendNode(
            session.id, null, SessionNodeType.SystemPrompt,
            buildJsonObject { put("kind", "base"); put("text", "sys") }, 5, SessionNodeStatus.success
        )
        val user = repository.appendNode(
            session.id, system.id, SessionNodeType.UserPrompt,
            buildJsonObject { put("content", "hi") }, 2, SessionNodeStatus.success
        )
        repository.appendNode(
            session.id, user.id, SessionNodeType.ToolCall,
            Json.parseToJsonElement(
                """{"type":"assistant","toolCalls":[{"id":"c1","name":"sys_notify_post","argumentsJson":"{}"}]}"""
            ) as kotlinx.serialization.json.JsonObject,
            9, SessionNodeStatus.pending
        )

        // Simulate a crash + resume cleanup: pending call → abandoned + synthetic result.
        repository.cleanupInterrupted(session.id)

        val path = repository.activePath(session.id)
        assertEquals(
            listOf("SystemPrompt", "UserPrompt", "ToolCall", "ToolResult"),
            path.map { it.nodeType.name }
        )
        assertEquals(SessionNodeStatus.abandoned, path[2].status)
        val synthPayload = repository.payload(path[3]).toString()
        assertTrue(synthPayload.contains("c1"))
        assertTrue(synthPayload.contains("interrupted"))

        // State memory roundtrip on real SQLite.
        repository.saveState(
            session.id,
            StateMemory(
                todos = listOf(TodoItem("t1", "device test", TodoStatus.in_progress, 1, 1)),
                preferences = listOf("reply in Chinese")
            )
        )
        assertEquals("reply in Chinese", repository.loadState(session.id).preferences.single())
        assertEquals("device test", repository.loadState(session.id).todos.single().title)
    }

    private fun dbFile() = File(baseDir, "acceptance.db")

    private fun node(id: String, sessionId: String, parentId: String?) = SessionNode(
        id = id, sessionId = sessionId, parentId = parentId,
        nodeType = SessionNodeType.UserPrompt, tokenCount = 1,
        status = SessionNodeStatus.success, createdAt = 1L
    )
}
