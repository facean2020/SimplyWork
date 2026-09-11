package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.core.session.SessionRepository
import com.yangz3720.simplywork.core.session.TodoItem
import com.yangz3720.simplywork.core.session.TodoStatus
import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Built-in `todo_write` tool (agent-core-design.md §8.3): the ONLY mutation path for
 * State Memory todos. Replace-all semantics — the model rewrites the whole list each
 * time (simplest correct protocol for small lists); ids are generated server-side.
 *
 * Necessary minimum: without a todo tool, State Memory stays empty and the
 * CompletionGuard has nothing to verify closure against.
 */
object TodoTool {

    const val TOOL_NAME = "todo_write"

    @Serializable
    data class TodoInput(val items: List<TodoItemInput> = emptyList())

    @Serializable
    data class TodoItemInput(val title: String, val status: TodoStatus = TodoStatus.pending)

    private val json = Json { ignoreUnknownKeys = true }

    fun definition(): ToolDefinition = ToolDefinition(
        name = TOOL_NAME,
        description = "Replace the whole todo list for this task. Use it to plan multi-step work, " +
            "mark progress, and close out items. Statuses: pending | in_progress | done | cancelled.",
        parametersSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("items", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "Short imperative description of the item")
                            })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(TodoStatus.entries.map { JsonPrimitive(it.name) }))
                            })
                        })
                        put("required", JsonArray(listOf("title").map { JsonPrimitive(it) }))
                    })
                })
            })
            put("required", JsonArray(listOf("items").map { JsonPrimitive(it) }))
        }
    )

    /** Builds the registered tool wired to [repository]'s State Memory. */
    fun registeredTool(repository: SessionRepository): RegisteredTool = RegisteredTool(
        definition = definition(),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw -> runCatching { json.decodeFromJsonElement(TodoInput.serializer(), raw) } },
        runner = { parsed, context ->
            val input = parsed as? TodoInput
                ?: return@RegisteredTool errorObject("todo_write arguments did not parse")
            replaceTodos(repository, context.sessionId, input)
        }
    )

    private suspend fun replaceTodos(
        repository: SessionRepository,
        sessionId: String,
        input: TodoInput
    ): JsonObject {
        val now = System.currentTimeMillis()
        val existing = repository.loadState(sessionId)
        val todos = input.items.map { item ->
            TodoItem(
                id = UUID.randomUUID().toString(),
                title = item.title,
                status = item.status,
                createdAt = now,
                updatedAt = now
            )
        }
        repository.saveState(sessionId, existing.copy(todos = todos))
        return buildJsonObject {
            put("status", "success")
            put("openTodos", todos.count { it.isOpen })
            putJsonArray("todos") {
                todos.forEach { todo ->
                    add(buildJsonObject {
                        put("title", todo.title)
                        put("status", todo.status.name)
                    })
                }
            }
        }
    }

    private fun errorObject(message: String): JsonObject = buildJsonObject {
        put("status", "invalid_arguments")
        put("reason", message)
    }
}
