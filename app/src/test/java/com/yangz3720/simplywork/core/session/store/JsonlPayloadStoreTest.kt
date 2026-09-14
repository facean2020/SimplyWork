package com.yangz3720.simplywork.core.session.store

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonlPayloadStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `append then read returns the payload intact`() {
        val store = JsonlPayloadStore(tmp.newFolder())
        val location = store.append("s1", "node-1", "ToolCall", """{"content":"hi","toolCalls":[]}""")

        assertTrue(location.length > 0)
        val payload = Json.parseToJsonElement(store.read("s1", "node-1")!!).jsonObject
        assertEquals("hi", payload["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `payload containing newlines never breaks the one-record-per-line format`() {
        val store = JsonlPayloadStore(tmp.newFolder())
        store.append("s1", "a", "UserPrompt", """{"content":"line1\nline2"}""")
        store.append("s1", "b", "UserPrompt", """{"content":"second"}""")

        val a = Json.parseToJsonElement(store.read("s1", "a")!!).jsonObject
        assertEquals("line1\nline2", a["content"]!!.jsonPrimitive.content)
        val b = Json.parseToJsonElement(store.read("s1", "b")!!).jsonObject
        assertEquals("second", b["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a fresh store instance rebuilds the index from the file`() {
        val dir = tmp.newFolder()
        JsonlPayloadStore(dir).append("s1", "n1", "UserPrompt", """{"content":"hello"}""")

        // Simulate a process restart: a brand-new store instance over the same directory.
        val second = JsonlPayloadStore(dir)
        assertEquals("hello", Json.parseToJsonElement(second.read("s1", "n1")!!).jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(1, second.loadAll("s1").size)
    }

    @Test
    fun `unknown node or session reads as null`() {
        val store = JsonlPayloadStore(tmp.newFolder())
        assertNull(store.read("sX", "ghost"))
        store.append("s1", "n1", "UserPrompt", """{"content":"x"}""")
        assertNull(store.read("s2", "n1"))
    }

    @Test
    fun `loadAll maps every id to its payload`() {
        val dir = tmp.newFolder()
        val store = JsonlPayloadStore(dir)
        store.append("s1", "n1", "UserPrompt", """{"content":"one"}""")
        store.append("s1", "n2", "ToolResult", """{"content":"two"}""")
        store.append("s1", "n3", "Summary", """{"text":"three"}""")

        val all = store.loadAll("s1")
        assertEquals(3, all.size)
        assertEquals("three", Json.parseToJsonElement(all["n3"]!!).jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete drops the session file and its index for good`() {
        val dir = tmp.newFolder()
        val store = JsonlPayloadStore(dir)
        store.append("s1", "n1", "UserPrompt", """{"content":"one"}""")
        store.append("s2", "n2", "UserPrompt", """{"content":"two"}""")

        store.delete("s1")

        // 落盘文件与内存索引都要消失：索引留着会让读命中已删文件的旧偏移量
        assertFalse(File(dir, "s1.jsonl").exists())
        assertNull(store.read("s1", "n1"))
        assertTrue(store.loadAll("s1").isEmpty())
        // 别的会话不受影响
        assertEquals(
            "two",
            Json.parseToJsonElement(store.read("s2", "n2")!!).jsonObject["content"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `delete of a never-written session is a no-op`() {
        val store = JsonlPayloadStore(tmp.newFolder())
        store.delete("ghost")
        assertNull(store.read("ghost", "n"))
    }
}
