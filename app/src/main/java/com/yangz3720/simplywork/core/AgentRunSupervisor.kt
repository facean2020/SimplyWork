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
 * 运行状态的唯一事实来源（UI 与前台服务共用）。
 *
 * [contextPromptTokens] 不在其中：那是"最近一轮请求真正送进去的上下文"，UI 从
 * [AgentStepEvent.ModelTurn] 自行投影，复位时机（换会话）也由 UI 决定，放这里会与
 * UI 的显式清零互相覆盖。
 */
data class RunState(
    val sessionId: String? = null,
    val running: Boolean = false,
    val iteration: Int = 0
)

/**
 * 进程级运行驱动（v1.6）：把 Agent Loop 的执行权从 `viewModelScope` 里搬出来。
 *
 * 为什么必须搬：`AgentLoopCoordinator.run` 是冷 Flow，谁 collect 谁就持有它 ——
 * 之前是 `AgentViewModel` 的 `viewModelScope`，于是 Activity 一销毁（或被系统回收）
 * run 就断，前台服务也就无从保活（服务活着、run 已经死了）。搬到这里之后：
 * 驱动归 app 进程，[AgentRunService] 只负责把进程钉在前台，UI 退化成订阅者。
 *
 * 不持任何 Android 依赖（前台服务的启停经构造注入的回调），所以整类可在 JVM 上直接测。
 */
class AgentRunSupervisor(
    private val scope: CoroutineScope,
    private val runtime: () -> AgentRuntime?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** run 真正开始前触发（AppContainer 用它拉起前台服务）。 */
    private val onRunStarted: (String) -> Unit = {},
    /** run 结束（完成/失败/被取消）后触发，用于收掉前台服务。 */
    private val onRunStopped: () -> Unit = {}
) {

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    /**
     * UI 投影信号：原样转发循环事件，让 UI 自己决定刷新什么（会话重投影 / 待办 /
     * 会话列表）。没有订阅者时事件自然丢弃 —— 事实都在 SessionRepository 里，
     * 重新订阅后靠重投影拿回来，不依赖事件本身。
     */
    private val _events = MutableSharedFlow<AgentStepEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentStepEvent> = _events.asSharedFlow()

    private var job: Job? = null

    /**
     * 递增的代次：取消旧 run 后，它的收尾回调会带着过期代次返回，据此丢弃 ——
     * 否则旧 run 的 `running = false` 会盖掉刚启动的新 run（两处都是 `_state.update`）。
     */
    private var generation = 0

    /**
     * 单任务假设（与旧 `runJob?.cancel()` 同义）：已有 run 在跑时先取消，再启动新的。
     * 模型未配置（[runtime] 为 null）时是 no-op，调用方负责给出"未配置"提示。
     */
    fun start(sessionId: String, initialPrompt: String?) {
        val runtime = runtime() ?: return
        cancelCurrent()
        val token = ++generation
        _state.value = RunState(sessionId = sessionId, running = true)
        // 先拉起前台服务再启动循环：服务必须在进程被降到后台之前进入前台。
        // 循环本身在 dispatcher 上排队，不会抢在这行之前跑（见 dispatcher 缺省 Dispatchers.IO）。
        onRunStarted(sessionId)
        job = scope.launch(dispatcher) {
            try {
                runtime.coordinator.run(sessionId, initialPrompt).collect { event -> dispatch(event) }
                finish(token)
            } catch (e: CancellationException) {
                finish(token)
                throw e
            } catch (e: Exception) {
                // 循环把 LLM 失败/超时都收编成 AgentStepEvent.Failed（不抛出），走到这里的是
                // 装配层异常（如会话不存在）—— 仍然要让服务收掉，否则通知会永远挂着。
                _events.tryEmit(AgentStepEvent.Failed(sessionId, e))
                finish(token)
            }
        }
    }

    /** 用户中止：已落库节点保留，pending 节点由下次 run 清扫。 */
    fun stop() {
        val wasRunning = job != null
        cancelCurrent()
        _state.value = RunState()
        if (wasRunning) onRunStopped()
    }

    /** 运行中输入（design §6 节点 B）：下一步循环 drain 时生效。 */
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
