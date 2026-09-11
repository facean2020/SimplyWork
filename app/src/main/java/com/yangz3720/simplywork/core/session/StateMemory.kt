package com.yangz3720.simplywork.core.session

import kotlinx.serialization.Serializable

/** Status of a single todo item (State Memory, agent-core-design.md §5.3). */
@Serializable
enum class TodoStatus { pending, in_progress, done, cancelled }

@Serializable
data class TodoItem(
    val id: String,
    val title: String,
    val status: TodoStatus = TodoStatus.pending,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isOpen: Boolean get() = status == TodoStatus.pending || status == TodoStatus.in_progress
}

/**
 * State Memory of one session: To-Do list + user preferences. NOT part of the DAG —
 * a single mutable row forcibly injected at the top of every loop's system message
 * (agent-core-design.md §5.3).
 */
@Serializable
data class StateMemory(
    val todos: List<TodoItem> = emptyList(),
    val preferences: List<String> = emptyList()
) {
    val openTodos: List<TodoItem> get() = todos.filter { it.isOpen }

    fun toPromptBlock(): String = buildString {
        appendLine("[STATE MEMORY]")
        if (todos.isEmpty()) {
            appendLine("Todos: (none)")
        } else {
            appendLine("Todos:")
            todos.forEach { todo ->
                val mark = if (todo.isOpen) "[ ]" else "[x]"
                appendLine("- $mark (${todo.status.name}) ${todo.title}")
            }
        }
        if (preferences.isNotEmpty()) {
            appendLine("Preferences:")
            preferences.forEach { appendLine("- $it") }
        }
    }.trimEnd()
}
