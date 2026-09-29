package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.infrastructure.sandbox.ScriptSandbox
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxConfig
import com.yangz3720.simplywork.infrastructure.sandbox.model.SandboxResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Result of [ScriptJobRegistry.start]: started (we got a job id) or rejected (with a reason). */
sealed interface ScriptJobStart {
    data class Started(val jobId: String) : ScriptJobStart
    data class Rejected(val reason: String) : ScriptJobStart
}

/** Result of [ScriptJobRegistry.await] / [ScriptJobRegistry.status]. */
sealed interface ScriptJobOutcome {
    /** The script finished (timeouts/OOM/errors included -- those are "finished" too, not "not finished yet"). */
    data class Finished(val jobId: String, val result: SandboxResult) : ScriptJobOutcome

    /** Still unfinished at the max wait handed to [ScriptJobRegistry.await]. The job is still running. */
    data class Pending(val jobId: String, val waitedMs: Long) : ScriptJobOutcome

    /** No such job: the id is mistyped, or the process was reclaimed (jobs live only in-process, nothing is persisted). */
    data class Unknown(val jobId: String) : ScriptJobOutcome
}

/**
 * Async script job registry (v1.6).
 *
 * Background: the sandbox's [ScriptSandbox.execute] has suspending semantics -- wired straight in as
 * a tool it could only mean "the call blocks the loop" (a synchronous wait). Real asynchrony needs
 * three things: starting a job returns at once, a finished job can **notify** the loop by itself,
 * and the loop can also **wait** on it by itself (with a max wait). This class is where those three
 * things land.
 *
 * Two boundaries, both drawn deliberately:
 * - **Queue rather than parallelise**: the sandbox has a single shared isolate and a timeout kills
 *   that whole isolate (see the note on `QuickJsSandbox.execMutex`). So this does not pretend to
 *   parallelise either -- past [MAX_RUNNING_JOBS] it rejects outright, rather than filing the model
 *   into an invisible queue.
 * - **Lives only in-process**: jobs are not persisted. Once the process is reclaimed, `await` gets
 *   the explicit answer [ScriptJobOutcome.Unknown] instead of hanging forever (P2: better to say
 *   explicitly "it is gone" than to wait silently).
 */
class ScriptJobRegistry(
    private val scope: CoroutineScope,
    private val sandbox: ScriptSandbox,
    /**
     * Called when a job finishes and **nobody was waiting at that moment** (with an awaiter there is
     * no need to say it twice -- the result has already been handed over by `await`). The
     * implementation owns turning this into a signal the loop can see (in this project, by pushing a
     * FollowUp into the message queue).
     */
    private val onFinished: (jobId: String, sessionId: String, result: SandboxResult) -> Unit = { _, _, _ -> },
    private val maxRetained: Int = MAX_RETAINED_JOBS
) {

    private class Entry(val id: String, val sessionId: String) {
        val completion = CompletableDeferred<SandboxResult>()
        var worker: Job? = null
        /** How many callers are currently `await`ing this job. */
        var awaiters: Int = 0
        /** Whether the completion event has already been pushed (so we never wake twice). */
        var notified: Boolean = false
    }

    private val mutex = Mutex()
    private val jobs = LinkedHashMap<String, Entry>()

    /**
     * Start a job and return its id at once. The script itself runs on [scope] and does not occupy the
     * caller.
     */
    suspend fun start(sessionId: String, script: String, timeoutMs: Long): ScriptJobStart {
        val entry = Entry(id = "js-${sequence.incrementAndGet()}", sessionId = sessionId)
        val rejection = mutex.withLock {
            val running = jobs.values.count { !it.completion.isCompleted }
            if (running >= MAX_RUNNING_JOBS) {
                "already $running script(s) running (max $MAX_RUNNING_JOBS) — await or wait for one to finish"
            } else {
                evictFinished()
                jobs[entry.id] = entry
                null
            }
        }
        if (rejection != null) return ScriptJobStart.Rejected(rejection)

        entry.worker = scope.launch {
            val result = sandbox.execute(
                script = script,
                config = SandboxConfig(timeoutMs = timeoutMs)
            )
            entry.completion.complete(result)
            val notify = mutex.withLock {
                if (entry.awaiters == 0 && !entry.notified) {
                    entry.notified = true
                    true
                } else {
                    false
                }
            }
            if (notify) onFinished(entry.id, entry.sessionId, result)
        }
        return ScriptJobStart.Started(entry.id)
    }

    /**
     * Actively wait for a job, at most [maxWaitMs]. If it arrives we return the result; if not we
     * return [ScriptJobOutcome.Pending] -- **the job is not cancelled by the wait timing out** (this
     * means "I will stop waiting for now", not "you stop running").
     */
    suspend fun await(jobId: String, maxWaitMs: Long): ScriptJobOutcome {
        val entry = mutex.withLock { jobs[jobId] } ?: return ScriptJobOutcome.Unknown(jobId)
        mutex.withLock { entry.awaiters += 1 }
        val startedAt = System.currentTimeMillis()
        try {
            val result = withTimeoutOrNull(maxWaitMs) { entry.completion.await() }
            return if (result == null) {
                ScriptJobOutcome.Pending(jobId, System.currentTimeMillis() - startedAt)
            } else {
                ScriptJobOutcome.Finished(jobId, result)
            }
        } finally {
            mutex.withLock { entry.awaiters -= 1 }
        }
    }

    /** Just a glance, no waiting. */
    suspend fun status(jobId: String): ScriptJobOutcome {
        val entry = mutex.withLock { jobs[jobId] } ?: return ScriptJobOutcome.Unknown(jobId)
        return if (entry.completion.isCompleted) {
            ScriptJobOutcome.Finished(jobId, entry.completion.getCompleted())
        } else {
            ScriptJobOutcome.Pending(jobId, waitedMs = 0L)
        }
    }

    /**
     * Cancel every job still running (called when a run ends).
     *
     * Why cancel: sandbox execution is serial, so an orphan script nobody is waiting for any more
     * holds that lock forever and blocks every script of the **next run**. Each cancelled job also
     * gets an explicit result filled in, so that a late `await` never hangs there forever.
     */
    suspend fun cancelAll() {
        val entries = mutex.withLock { jobs.values.toList() }
        entries.forEach { entry ->
            entry.worker?.cancel()
            entry.completion.complete(
                SandboxResult.Error(
                    message = "cancelled: the run ended before this script finished",
                    executionTimeMs = 0
                )
            )
        }
    }

    /** The caller must hold [mutex]. Only clears finished ones; running jobs are left completely alone. */
    private fun evictFinished() {
        while (jobs.size >= maxRetained) {
            val victim = jobs.values.firstOrNull { it.completion.isCompleted } ?: return
            jobs.remove(victim.id)
        }
    }

    private companion object {
        /**
         * Cap on scripts running at once. The sandbox is serial, so this number is not "degree of
         * parallelism" but "how long a queue is allowed" -- past it we reject outright and make the
         * model collect what it already has first.
         */
        const val MAX_RUNNING_JOBS = 4

        /** How many finished jobs are retained for later lookup. */
        const val MAX_RETAINED_JOBS = 8

        private val sequence = AtomicLong(0)
    }
}
