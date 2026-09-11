package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.core.session.SessionRepositoryImpl
import com.yangz3720.simplywork.core.session.TodoStatus
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoToolTest {

    private val repository = SessionRepositoryImpl(InMemorySessionStore(), InMemoryPayloadStore())

    @Test
    fun `todo_write replaces the todo list and reports open count`() = runTest {
        repository.createSession("t")
        val tool = TodoTool.registeredTool(repository)

        val parsed = tool.parse(
            Json.parseToJsonElement(
                """{"items":[{"title":"buy milk","status":"pending"},{"title":"reply email","status":"in_progress"}]}"""
            ).jsonObject
        ).getOrThrow()

        val output = tool.execute(parsed, ToolExecutionContext("s1")).let { Json.parseToJsonElement(it.toString()).jsonObject }
        assertEquals("success", output["status"]!!.jsonPrimitive.content)
        assertEquals(2, output["openTodos"]!!.jsonPrimitive.content.toInt())

        val state = repository.loadState("s1")
        assertEquals(2, state.openTodos.size)
        assertEquals("buy milk", state.openTodos[0].title)
        assertEquals(TodoStatus.in_progress, state.openTodos[1].status)
    }

    @Test
    fun `malformed arguments fail the parse result`() = runTest {
        val tool = TodoTool.registeredTool(repository)
        val parsed = tool.parse(Json.parseToJsonElement("""{"items":"not-a-list"}""").jsonObject)
        assertTrue(parsed.isFailure)
    }

    @Test
    fun `replace-all semantics drop stale items`() = runTest {
        repository.createSession("t")
        val tool = TodoTool.registeredTool(repository)
        tool.execute(
            tool.parse(Json.parseToJsonElement("""{"items":[{"title":"a"}]}""").jsonObject).getOrThrow(),
            ToolExecutionContext("s1")
        )
        tool.execute(
            tool.parse(Json.parseToJsonElement("""{"items":[{"title":"b","status":"done"}]}""").jsonObject).getOrThrow(),
            ToolExecutionContext("s1")
        )
        val state = repository.loadState("s1")
        assertEquals(0, state.openTodos.size)
        assertEquals(1, state.todos.size)
    }
}
