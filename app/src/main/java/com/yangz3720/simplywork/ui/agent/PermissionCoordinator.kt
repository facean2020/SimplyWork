package com.yangz3720.simplywork.ui.agent

import com.yangz3720.simplywork.infrastructure.systemops.SystemPermissionRequester
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行时权限协调器（v1.2，P4）：实现 `SystemPermissionRequester` 的 UI 侧语义 ——
 * [request] 挂起等待系统授权结果，结果经 [respond] 回灌。
 *
 * 与 [ApprovalCoordinator] 同构（同一"后台循环挂起等 UI 决策"的缝）：Agent Loop 串行
 * 执行工具，同一时刻至多一个授权挂起；运行被取消（含 Router 10s 超时）时 `await` 以
 * CancellationException 退出，`finally` 清空挂起态，不会残留幽灵弹窗。
 *
 * 注意 [request] 的返回值只是"用户点了什么"，调用方（执行器）必须重新 `checkSelfPermission`
 * 再决定放行 —— 一次性授权、部分同意都会让两者不一致。
 */
class PermissionCoordinator {

    private val _pending = MutableStateFlow<PermissionRequest?>(null)

    /** 当前挂起的授权请求；非空时 UI 触发系统权限弹窗。 */
    val pending: StateFlow<PermissionRequest?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<Boolean>? = null

    /** 由执行器调用：挂起直至用户决策或运行取消/超时。 */
    suspend fun request(permissions: List<String>, rationale: String): Boolean {
        val pendingDeferred = CompletableDeferred<Boolean>()
        // 重叠保护（v1.2 复核 S10）：正常情况下 Agent Loop 串行执行，至多一个挂起；
        // 但屏幕重建或竞态下二次 request 不能让第一个调用方永久悬空 —— 旧请求按
        // "未被授予"收敛，调用方复查权限后自然走 PermissionRequired（P2 显式失败）。
        deferred?.complete(false)
        deferred = pendingDeferred
        _pending.value = PermissionRequest(permissions, rationale)
        try {
            return pendingDeferred.await()
        } finally {
            // 仅当挂起态仍是本次请求时才清空：若已被更新的请求覆盖，
            // 清空会误伤新请求的 pending 状态。
            if (deferred === pendingDeferred) {
                deferred = null
                _pending.value = null
            }
        }
    }

    /**
     * 授权结果入口。无挂起请求时为 no-op —— 系统弹窗的结果可能晚于 Router 超时到达，
     * 此时挂起方已经按 Timeout 返回，不该再写任何状态。
     */
    fun respond(granted: Boolean) {
        deferred?.complete(granted)
    }

    /** Adapter handed to the infrastructure layer; keeps systemops free of UI imports. */
    fun asRequester(): SystemPermissionRequester = SystemPermissionRequester { permissions, rationale ->
        request(permissions, rationale)
    }
}
