package com.yangz3720.simplywork.ui.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * HITL 审批协调器（agent-core-design.md §8.2 A4 / §12 Q1）：实现 `AuditApprovalHandler`
 * 的 UI 侧语义 —— `request` 挂起等待用户决策，决策经 [respond] 回灌。
 *
 * 单请求假设：Agent Loop 在 collector 协程里串行调用 `preToolAudit`，同一时刻至多
 * 一个审批挂起；运行被取消时 `await` 以 CancellationException 退出，`finally` 清空
 * 挂起状态（不会残留幽灵弹窗）。
 */
class ApprovalCoordinator {

    private val _requests = MutableStateFlow<ApprovalRequest?>(null)

    /** 当前挂起的审批请求；非空时 UI 显示审批对话框。 */
    val pending: StateFlow<ApprovalRequest?> = _requests.asStateFlow()

    private var deferred: CompletableDeferred<Boolean>? = null

    /** 由 AuditApprovalHandler 调用：挂起直至用户决策或运行取消。 */
    suspend fun request(toolName: String, argsJson: String): Boolean {
        val pendingDeferred = CompletableDeferred<Boolean>()
        deferred = pendingDeferred
        _requests.value = ApprovalRequest(toolName, argsJson)
        try {
            return pendingDeferred.await()
        } finally {
            deferred = null
            _requests.value = null
        }
    }

    /** 用户决策入口：true 允许 / false 拒绝。无挂起请求时为 no-op。 */
    fun respond(allowed: Boolean) {
        deferred?.complete(allowed)
    }
}