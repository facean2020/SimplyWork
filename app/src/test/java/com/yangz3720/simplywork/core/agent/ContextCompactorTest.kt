package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.session.SessionRepositoryImpl
import com.yangz3720.simplywork.core.session.StateMemory
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorTest {

    private fun repo(): SessionRepositoryImpl = SessionRepositoryImpl(InMemorySessionStore(), InMemoryPayloadStore())

    private suspend fun seedLongSession(repository: SessionRepositoryImpl, rounds: Int): String {
        val sid = repository.createSession("compaction").id
        val base = repository.appendNode(
            sid, null, SessionNodeType.SystemPrompt,
            buildJsonObject { put("kind", "base"); put("text", "BASE") },
            10, SessionNodeStatus.success
        )
        var parent: String? = base.id
        repeat(rounds) { i ->
            val user = repository.appendNode(
                sid, parent, SessionNodeType.UserPrompt,
                buildJsonObject { put("content", "round $i") },
                100, SessionNodeStatus.success
            )
            val answer = repository.appendNode(
                sid, user.id, SessionNodeType.ToolCall,
                buildJsonObject { put("content", "answer $i") },
                100, SessionNodeStatus.success
            )
            parent = answer.id
        }
        // Final user marker: every round is closed by a user-type node.
        repository.appendNode(
            sid, parent, SessionNodeType.UserPrompt,
            buildJsonObject { put("content", "final") },
            100, SessionNodeStatus.success
        )
        return sid
    }

    private val config = AgentConfig(
        maxWorkingRounds = 2,
        workingTokenBudget = 10_000,
        compactionTriggerTokens = 100
    )

    @Test
    fun `below watermark does nothing`() = runTest {
        val repository = repo()
        val sid = seedLongSession(repository, rounds = 1)
        // Trigger above the whole path (410 tokens) → no compaction.
        val relaxedConfig = config.copy(compactionTriggerTokens = 10_000)
        val compactor = ContextCompactor(repository, TruncateSummarizer(), HeuristicTokenEstimator(), relaxedConfig)
        val outcome = compactor.maybeCompact(sid, repository.activePath(sid))
        assertTrue(outcome is ContextCompactor.Outcome.NotNeeded)
    }

    @Test
    fun `above watermark compacts the prefix and rewires the branch`() = runTest {
        val repository = repo()
        val sid = seedLongSession(repository, rounds = 6)
        val compactor = ContextCompactor(repository, TruncateSummarizer(), HeuristicTokenEstimator(), config)

        val pathBefore = repository.activePath(sid)
        val outcome = compactor.maybeCompact(sid, pathBefore)
        assertTrue(outcome is ContextCompactor.Outcome.Compacted)
        assertTrue((outcome as ContextCompactor.Outcome.Compacted).savedTokens > 0)

        val pathAfter = repository.activePath(sid)
        // Path keeps the base prompt, gains exactly one Summary node, and is now shorter.
        assertEquals(SessionNodeType.SystemPrompt, pathAfter.first().nodeType)
        assertEquals(1, pathAfter.count { it.nodeType == SessionNodeType.Summary })
        assertTrue(pathAfter.size < pathBefore.size)
    }

    @Test
    fun `second compaction chains onto the previous summary`() = runTest {
        val repository = repo()
        val sid = seedLongSession(repository, rounds = 8)
        val compactor = ContextCompactor(repository, TruncateSummarizer(), HeuristicTokenEstimator(), config)

        val first = compactor.maybeCompact(sid, repository.activePath(sid))
        assertTrue(first is ContextCompactor.Outcome.Compacted)

        val second = compactor.maybeCompact(sid, repository.activePath(sid))
        assertTrue(second is ContextCompactor.Outcome.Compacted)
        assertEquals(1, repository.activePath(sid).count { it.nodeType == SessionNodeType.Summary })
    }

    @Test
    fun `summarizer failure skips compaction explicitly`() = runTest {
        val repository = repo()
        val sid = seedLongSession(repository, rounds = 6)
        val failing = object : Summarizer {
            override val strategyName = "failing"
            override suspend fun summarize(nodes: List<com.yangz3720.simplywork.core.session.SessionNode>, payloads: List<kotlinx.serialization.json.JsonObject>): String =
                throw IllegalStateException("llm down")
        }
        val compactor = ContextCompactor(repository, failing, HeuristicTokenEstimator(), config)
        val outcome = compactor.maybeCompact(sid, repository.activePath(sid))
        assertTrue(outcome is ContextCompactor.Outcome.Skipped)
        assertTrue((outcome as ContextCompactor.Outcome.Skipped).reason.contains("summarizer failed"))
        // Nothing changed on the path.
        assertEquals(0, repository.activePath(sid).count { it.nodeType == SessionNodeType.Summary })
    }

    @Test
    fun `state memory counts toward the watermark`() = runTest {
        val repository = repo()
        val sid = seedLongSession(repository, rounds = 1)
        repository.saveState(sid, StateMemory(preferences = listOf("p".repeat(10_000))))
        val compactor = ContextCompactor(repository, TruncateSummarizer(), HeuristicTokenEstimator(), config)
        val outcome = compactor.maybeCompact(sid, repository.activePath(sid))
        // Even above the trigger there is nothing compactable (single round = working window).
        assertTrue(outcome is ContextCompactor.Outcome.Skipped)
    }
}
