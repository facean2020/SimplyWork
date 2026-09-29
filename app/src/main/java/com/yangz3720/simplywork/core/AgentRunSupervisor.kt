package com.yangz3720.simplywork.core

import com.yangz3720.simplywork.core.agent.AgentStepEvent
import com.yangz3720.simplywork.core.agent.IncomingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The single source of truth for run state (shared by the UI and the foreground service).
 *
 * [contextPromptTokens] is not part of it: that is "the context the most recent turn actually sent
 * in", which the UI projects for itself from [AgentStepEvent.ModelTurn]; the reset point (switching
 * session) is the UI's call too, and keeping it here would overwrite back and forth with the UI's
 * explicit clearing.
 */
data class RunState(
    val sessionId: String? = null,
    val running: Boolean = false,
    val iteration: Int = 0
)

/**
 * Process-level run driver (v1.6): moves ownership of Agent Loop execution out of `viewModelScope`.
 *
 * Why it had to move: `AgentLoopCoordinator.run` is a cold Flow, so whoever collects it holds it --
 * that used to be `AgentViewModel`'s `viewModelScope`, so the moment the Activity was destroyed (or
 * reclaimed by the system) the run broke off and the foreground service had nothing to keep alive
 * (the service alive, the run already dead). After the move: driving belongs to the app process,
 * [AgentRunService] only pins the process in the foreground, and the UI degrades to a subscriber.
 *
 * Holds no Android dependency (starting and stopping the foreground service goes through
 * constructor-injected callbacks), so the whole class is directly testable on the JVM.
 */
class AgentRunSupervisor(
    private val scope: CoroutineScope,
    private val runtime: () -> AgentRuntime?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Fires just before the run really starts (AppContainer uses it to bring up the foreground service). */
    private val onRunStarted: (String) -> Unit = {},
    /** Fires after the run ends (completed / failed / cancelled), used to take the foreground service down. */
    private val onRunStopped: () -> Unit = {}
) {

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    /**
     * UI projection signal: forwards loop events verbatim so the UI decides for itself what to
     * refresh (session re-projection / todos / session list). With no subscriber the events are
     * simply dropped -- the facts all live in SessionRepository, so after re-subscribing they come
     * back via re-projection and nothing depends on the events themselves.
     */
    private val _events = MutableSharedFlow<AgentStepEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentStepEvent> = _events.asSharedFlow()

    private var job: Job? = null

    /**
     * Incrementing generation: after an old run is cancelled its teardown callback comes back
     * carrying a stale generation, which is how it gets dropped -- otherwise the old run's
     * `running = false` would overwrite the new run that just started (both are `_state.update`).
     */
    private var generation = 0

    /**
     * Single-task assumption (equivalent to the old `runJob?.cancel()`): when a run is already
     * going, cancel it first and then start the new one. A no-op when the model is unconfigured
     * ([runtime] is null); the caller owns showing the "not configured" message.
     */
    fun start(sessionId: String, initialPrompt: String?) {
        val runtime = runtime() ?: return
        cancelCurrent()
        val token = ++generation
        _state.value = RunState(sessionId = sessionId, running = true)
        // Bring up the foreground service before starting the loop: the service must be in the
        // foreground before the process gets demoted to background. The loop itself queues on the
        // dispatcher, so it cannot jump ahead of this line (see the dispatcher default Dispatchers.IO).
        onRunStarted(sessionId)
        job = scope.launch(dispatcher) {
            try {
                runtime.coordinator.run(sessionId, initialPrompt).collect { event -> dispatch(event) }
                finish(token)
            } catch (e: CancellationException) {
                finish(token)
                throw e
            } catch (e: Exception) {
                // The loop pulls LLM failures/timeouts in as AgentStepEvent.Failed (it does not
                // throw); what arrives here is a wiring-layer exception (e.g. no such session) --
                // the service still has to be taken down, otherwise the notification hangs forever.
                _events.tryEmit(AgentStepEvent.Failed(sessionId, e))
                finish(token)
            }
        }
    }

    /** User abort: nodes already persisted are kept, pending nodes are swept by the next run. */
    fun stop() {
        val wasRunning = job != null
        cancelCurrent()
        _state.value = RunState()
        if (wasRunning) onRunStopped()
    }

    /** Mid-run input (design §6 node B): takes effect when the loop drains at the next step. */
    fun steer(text: String) {
        runtime()?.queue?.enqueue(IncomingMessage.Steer(text))
    }

    private fun cancelCurrent() {
        generation++
        job?.cancel()
        job = null
    }

    private fun finish(token: Int) {
        if (generation != token) return
        job = null
        _state.value = RunState()
        onRunStopped()
    }

    private suspend fun dispatch(event: AgentStepEvent) {
        if (event is AgentStepEvent.ModelTurn) {
            _state.update { it.copy(iteration = event.iteration) }
        }
        _events.emit(event)
    }
}
