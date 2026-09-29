package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.infrastructure.sandbox.ScriptSandbox
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxConfig
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Async script jobs: starting one returns at once, you can actively await it, finishing is notified, and stopping yields an explicit result.
 *
 * The sandbox is a stand-in (a real one needs an Android context and the JS engine service, and it is
 * the subject of the execution tests itself -- see `ScriptSandboxContractTest`). Only **scheduling semantics** are checked here.
 */
class ScriptJobRegistryTest {

    /** Controllable sandbox: records the scripts it receives, the outcome is decided by [gate] (null = return [result] immediately). */
    private class FakeSandbox(
        private val gate: CompletableDeferred<SandboxResult>? = null,
        private val result: SandboxResult = SandboxResult.Success("ok", 1)
    ) : ScriptSandbox {
        val scripts = mutableListOf<String>()
        override val isInitialized: Boolean = true

        override suspend fun execute(
            script: String,
            context: Map<String, Any?>,
            config: SandboxConfig
        ): SandboxResult {
            scripts += script
            return gate?.await() ?: result
        }

        override suspend fun reset(): Result<Unit> = Result.success(Unit)
        override fun close() = Unit
    }

    /**
     * Explicitly hand the registry a scope built on the test dispatcher.
     *
     * Not `backgroundScope`: it does not follow [advanceUntilIdle], the job just sits there forever and the
     * case fails as "the job never finished" -- that is a trap of the test scaffolding, not the behaviour of the code under test.
     */
    private fun TestScope.registry(
        sandbox: ScriptSandbox,
        onFinished: (String, String, SandboxResult) -> Unit = { _, _, _ -> }
    ) = ScriptJobRegistry(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        sandbox = sandbox,
        onFinished = onFinished
    )

    private fun started(id: ScriptJobStart): String {
        assertTrue("expected Started but got $id", id is ScriptJobStart.Started)
        return (id as ScriptJobStart.Started).jobId
    }

    // ----- starting a job -----

    @Test
    fun `start returns immediately with a job id, without waiting for the script`() = runTest {
        val gate = CompletableDeferred<SandboxResult>()
        val sandbox = FakeSandbox(gate = gate)
        val jobs = registry(sandbox)

        val jobId = started(jobs.start("s1", "sleep()", 1_000))

        assertTrue(jobId.startsWith("js-"))
        // start has already returned while the script still sits unstarted in the dispatch queue -- which is the very definition of "async"
        assertEquals(0, sandbox.scripts.size)
        runCurrent()
        assertEquals(1, sandbox.scripts.size)
        assertTrue("job should still be running", jobs.status(jobId) is ScriptJobOutcome.Pending)
    }

    @Test
    fun `a finished job keeps its result for later lookups`() = runTest {
        val jobs = registry(FakeSandbox(result = SandboxResult.Success("42", 7)))

        val jobId = started(jobs.start("s1", "6*7", 1_000))
        advanceUntilIdle()

        val outcome = jobs.status(jobId)
        assertEquals(ScriptJobOutcome.Finished(jobId, SandboxResult.Success("42", 7)), outcome)
    }

    @Test
    fun `unknown job ids get an explicit answer, never a hang`() = runTest {
        val jobs = registry(FakeSandbox())

        assertEquals(ScriptJobOutcome.Unknown("js-nope"), jobs.status("js-nope"))
        assertEquals(ScriptJobOutcome.Unknown("js-nope"), jobs.await("js-nope", 1_000))
    }

    // ----- awaiting on purpose -----

    @Test
    fun `await returns the result once the script finishes`() = runTest {
        val jobs = registry(FakeSandbox(result = SandboxResult.Success("done", 3)))
        val jobId = started(jobs.start("s1", "1", 1_000))

        val outcome = jobs.await(jobId, maxWaitMs = 60_000)

        assertEquals(ScriptJobOutcome.Finished(jobId, SandboxResult.Success("done", 3)), outcome)
    }

    @Test
    fun `await gives up at the max wait WITHOUT cancelling the job`() = runTest {
        val gate = CompletableDeferred<SandboxResult>()
        val jobs = registry(FakeSandbox(gate = gate))
        val jobId = started(jobs.start("s1", "long()", 1_000))

        val outcome = jobs.await(jobId, maxWaitMs = 5_000)

        assertTrue("expected Pending but got $outcome", outcome is ScriptJobOutcome.Pending)
        // the point: an await timeout means "I stop waiting for now", not "you stop running"
        assertTrue("the job must still be running", jobs.status(jobId) is ScriptJobOutcome.Pending)

        gate.complete(SandboxResult.Success("eventually", 9))
        advanceUntilIdle()
        assertEquals(
            ScriptJobOutcome.Finished(jobId, SandboxResult.Success("eventually", 9)),
            jobs.status(jobId)
        )
    }

    // ----- completion notification -----

    @Test
    fun `a job finishing while nobody waits notifies the loop`() = runTest {
        val notices = mutableListOf<Triple<String, String, SandboxResult>>()
        val jobs = registry(
            FakeSandbox(result = SandboxResult.Success("x", 1)),
            onFinished = { id, session, result -> notices += Triple(id, session, result) }
        )

        val jobId = started(jobs.start("session-7", "1", 1_000))
        advanceUntilIdle()

        assertEquals(1, notices.size)
        assertEquals(jobId, notices.single().first)
        assertEquals("session-7", notices.single().second)
    }

    @Test
    fun `a job finishing while somebody waits is not also announced`() = runTest {
        val gate = CompletableDeferred<SandboxResult>()
        val notices = mutableListOf<String>()
        val jobs = registry(FakeSandbox(gate = gate), onFinished = { id, _, _ -> notices += id })
        val jobId = started(jobs.start("s1", "1", 1_000))

        // let the await attach first, then let the script finish: the result already reached the waiter, so no second wake-up is due
        val waiting = launch { jobs.await(jobId, maxWaitMs = 60_000) }
        // runCurrent (rather than advanceUntilIdle): the waiter has to attach, but the virtual clock must not
        // be pushed onto withTimeoutOrNull's timeout, else await returns on timeout first and this case no longer tests what it means to test.
        runCurrent()
        gate.complete(SandboxResult.Success("x", 1))
        advanceUntilIdle()

        assertTrue(notices.isEmpty())
        assertTrue(waiting.isCompleted)
    }

    // ----- cap and cancellation -----

    @Test
    fun `queuing beyond the running cap is rejected explicitly`() = runTest {
        val gate = CompletableDeferred<SandboxResult>()
        val jobs = registry(FakeSandbox(gate = gate))

        repeat(4) { assertTrue(jobs.start("s1", "loop()", 1_000) is ScriptJobStart.Started) }
        val fifth = jobs.start("s1", "loop()", 1_000)

        // the sandbox is serial: rather than queue the model up in an invisible line, refuse it explicitly
        assertTrue("expected Rejected but got $fifth", fifth is ScriptJobStart.Rejected)
    }

    @Test
    fun `cancelAll ends running jobs with an explicit result`() = runTest {
        val gate = CompletableDeferred<SandboxResult>()
        val jobs = registry(FakeSandbox(gate = gate))
        val jobId = started(jobs.start("s1", "forever()", 1_000))

        jobs.cancelAll()

        val outcome = jobs.await(jobId, maxWaitMs = 1_000)
        assertTrue("expected a finished outcome but got $outcome", outcome is ScriptJobOutcome.Finished)
        val result = (outcome as ScriptJobOutcome.Finished).result
        assertTrue("cancellation must be reported, not swallowed: $result", result is SandboxResult.Error)
    }
}
