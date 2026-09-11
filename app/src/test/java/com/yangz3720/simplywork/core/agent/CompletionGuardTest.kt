package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.session.SessionRepositoryImpl
import com.yangz3720.simplywork.core.session.StateMemory
import com.yangz3720.simplywork.core.session.TodoItem
import com.yangz3720.simplywork.core.session.TodoStatus
import com.yangz3720.simplywork.core.session.store.InMemoryPayloadStore
import com.yangz3720.simplywork.core.session.store.InMemorySessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionGuardTest {

    private val repository = SessionRepositoryImpl(InMemorySessionStore(), InMemoryPayloadStore())
    private val guard = CompletionGuard(repository)

    @Test
    fun `no todos and non-empty text completes`() = runTest {
        repository.createSession("t")
        val verdict = guard.evaluate("s1", "all done!")
        assertTrue(verdict.completed)
        assertNull(verdict.followUp)
    }

    @Test
    fun `open todos block completion with a listing follow-up`() = runTest {
        repository.createSession("t")
        repository.saveState(
            "s1",
            com.yangz3720.simplywork.core.session.StateMemory(
                todos = listOf(
                    com.yangz3720.simplywork.core.session.TodoItem("t1", "buy milk", TodoStatus.pending, 1, 1),
                    com.yangz3720.simplywork.core.session.TodoItem("t2", "done thing", TodoStatus.done, 2, 2)
                )
            )
        )
        val verdict = guard.evaluate("s1", "here is my answer")
        assertFalse(verdict.completed)
        assertTrue(verdict.reason == CompletionGuard.OPEN_TODOS)
        assertTrue(verdict.followUp!!.contains("buy milk"))
    }

    @Test
    fun `blank final text blocks completion`() = runTest {
        repository.createSession("t")
        val verdict = guard.evaluate("s1", "   ")
        assertFalse(verdict.completed)
        assertTrue(verdict.reason == CompletionGuard.EMPTY_RESPONSE)
    }
}
