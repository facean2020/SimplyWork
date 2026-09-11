package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.session.SessionRepository

/**
 * Termination interceptor (flow node N): when the model produces a plain-text answer
 * (no tool calls) and the queue is empty, verify the To-Do list is closed
 * (architecture.md "CompletionGuard", agent-core-design.md §6).
 */
class CompletionGuard(private val repository: SessionRepository) {

    data class Verdict(
        val completed: Boolean,
        /** Follow-up instruction to inject when not completed. */
        val followUp: String? = null,
        /** Machine-readable cause for observability. */
        val reason: String? = null
    )

    suspend fun evaluate(sessionId: String, finalText: String): Verdict {
        val open = repository.loadState(sessionId).openTodos
        if (open.isNotEmpty()) {
            return Verdict(
                completed = false,
                followUp = "Your todo list still has open items: " +
                    open.joinToString("; ") { "\"${it.title}\"" } +
                    ". Continue working on them now; if any no longer apply, update the list via todo_write " +
                    "(mark done/cancelled) and then give the final answer.",
                reason = OPEN_TODOS
            )
        }
        if (finalText.isBlank()) {
            return Verdict(
                completed = false,
                followUp = "Your last response was empty. Summarize the outcome for the user now.",
                reason = EMPTY_RESPONSE
            )
        }
        return Verdict(completed = true, reason = COMPLETED)
    }

    companion object {
        const val OPEN_TODOS = "open_todos"
        const val EMPTY_RESPONSE = "empty_response"
        const val COMPLETED = "todos_closed"
    }
}
