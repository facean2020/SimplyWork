package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeType
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkingWindowSelectorTest {

    private fun node(id: String, type: SessionNodeType, tokens: Int = 1) =
        com.yangz3720.simplywork.core.session.SessionNode(
            id = id, sessionId = "s", parentId = null, nodeType = type,
            tokenCount = tokens, status = com.yangz3720.simplywork.core.session.SessionNodeStatus.success,
            createdAt = 0L
        )

    @Test
    fun `preamble is excluded and oldest rounds fall into the compactable prefix`() {
        val path = listOf(
            node("base", SessionNodeType.SystemPrompt),
            node("u1", SessionNodeType.UserPrompt),
            node("a1", SessionNodeType.ToolCall),
            node("u2", SessionNodeType.UserPrompt),
            node("a2", SessionNodeType.ToolCall)
        )
        val window = WorkingWindowSelector.select(path, maxRounds = 1, tokenBudget = 10_000)

        assertEquals(listOf("base", "u1", "a1"), window.compactablePrefix.map { it.id })
        assertEquals(listOf("u2", "a2"), window.workingNodes.map { it.id })
    }

    @Test
    fun `token budget drops whole rounds but always keeps the newest`() {
        val path = listOf(
            node("u1", SessionNodeType.UserPrompt, tokens = 500),
            node("a1", SessionNodeType.ToolCall, tokens = 500),
            node("u2", SessionNodeType.UserPrompt, tokens = 5),
            node("a2", SessionNodeType.ToolCall, tokens = 5)
        )
        val window = WorkingWindowSelector.select(path, maxRounds = 10, tokenBudget = 10)

        // Round 1 (1000 tokens) exceeds the budget → evicted into the compactable prefix.
        assertEquals(listOf("u1", "a1"), window.compactablePrefix.map { it.id })
        assertEquals(listOf("u2", "a2"), window.workingNodes.map { it.id })
    }

    @Test
    fun `max rounds caps the window`() {
        val path = listOf(
            node("u1", SessionNodeType.UserPrompt), node("a1", SessionNodeType.ToolCall),
            node("u2", SessionNodeType.UserPrompt), node("a2", SessionNodeType.ToolCall),
            node("u3", SessionNodeType.UserPrompt), node("a3", SessionNodeType.ToolCall)
        )
        val window = WorkingWindowSelector.select(path, maxRounds = 2, tokenBudget = 10_000)

        assertEquals(listOf("u1", "a1"), window.compactablePrefix.map { it.id })
        assertEquals(listOf("u2", "a2", "u3", "a3"), window.workingNodes.map { it.id })
    }

    @Test
    fun `steer nodes open rounds too`() {
        val path = listOf(
            node("u1", SessionNodeType.UserPrompt),
            node("st", SessionNodeType.Steer),
            node("a1", SessionNodeType.ToolCall)
        )
        val window = WorkingWindowSelector.select(path, maxRounds = 5, tokenBudget = 10_000)
        // u1 | st..a1 are two rounds; both fit.
        assertEquals(0, window.compactablePrefix.size)
        assertEquals(listOf("u1", "st", "a1"), window.workingNodes.map { it.id })
    }

    @Test
    fun `empty path yields empty window`() {
        val window = WorkingWindowSelector.select(emptyList(), maxRounds = 3, tokenBudget = 100)
        assertEquals(0, window.compactablePrefix.size)
        assertEquals(0, window.workingNodes.size)
    }
}
