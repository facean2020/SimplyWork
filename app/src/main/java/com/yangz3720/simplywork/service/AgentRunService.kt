package com.yangz3720.simplywork.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.yangz3720.simplywork.MainActivity
import com.yangz3720.simplywork.R
import com.yangz3720.simplywork.SimplyWorkApp
import com.yangz3720.simplywork.core.RunState
import com.yangz3720.simplywork.ui.agent.ApprovalRequest
import com.yangz3720.simplywork.ui.agent.PermissionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Agent 任务的前台服务（v1.6，`specialUse` 类型）。
 *
 * **它不执行任何东西** —— run 由进程级的 [com.yangz3720.simplywork.core.AgentRunSupervisor]
 * 驱动。这个服务只做两件事：
 *  1. `startForeground` 把进程钉在前台，使 run 在锁屏/退到后台/Activity 销毁后不被回收；
 *  2. 把运行状态投影成一条通知（含"停止"与"返回处理"动作）。
 *
 * 之所以不把执行放进服务：Service 是 Android 组件、单测跑不了，一旦执行逻辑进来，
 * 主干就再也没法在 JVM 上验证了。边界守在这里，两侧各自可测。
 *
 * 生命周期由 supervisor 的启停回调驱动（AppContainer 注入）：run 开始 → startForegroundService，
 * run 结束 → stopService。
 */
class AgentRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** `onStartCommand` 每次 start 都会被调用，收集器只挂一次。 */
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as SimplyWorkApp).container

        if (intent?.action == ACTION_STOP) {
            container.runSupervisor.stop()
            return START_NOT_STICKY
        }

        // 必须在 5s 内进前台：先用当前状态同步挂一条，之后交给收集器刷新。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(noticeOf(container.runSupervisor.state.value, container.permissionCoordinator.pending.value, container.approvalCoordinator.pending.value)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        if (!observing) {
            observing = true
            scope.launch {
                combine(
                    container.runSupervisor.state,
                    container.permissionCoordinator.pending,
                    container.approvalCoordinator.pending
                ) { run, permission, approval -> noticeOf(run, permission, approval) }
                    .collect { notice ->
                        NotificationManagerCompat.from(this@AgentRunService)
                            .notify(NOTIFICATION_ID, buildNotification(notice))
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** 不对外暴露 binder：状态走 supervisor 的 StateFlow，不需要跨进程/组件绑定。 */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 通知正文的三种形态。运行中撞上 HITL 是这套机制唯一的"坑"：审批/授权都挂起等 UI，
     * 后台没人点就永远等下去，所以这两种状态必须显式说出来（并让整条通知可点回界面）。
     */
    private fun noticeOf(run: RunState, permission: PermissionRequest?, approval: ApprovalRequest?): String = when {
        permission != null -> "需要授权：${permission.permissions.first().substringAfterLast('.')}"
        approval != null -> "等待你的确认：${approval.toolName}"
        run.iteration > 0 -> "第 ${run.iteration} 步 · 正在执行"
        else -> "正在执行"
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AgentRunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_run)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, "返回处理", contentIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * IMPORTANCE_LOW（不出声、不横幅）：这是常驻的过程指示，不是提醒。因此"等待你的确认"
     * 也不会响铃 —— 是否要为需人工介入的状态另开一条高优先级通道，属于产品决策，默认取保守值。
     */
    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "任务执行", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_STOP = "com.yangz3720.simplywork.action.STOP_AGENT_RUN"

        private const val CHANNEL_ID = "agent_run"
        private const val NOTIFICATION_ID = 1001

        private const val REQUEST_OPEN = 0
        private const val REQUEST_STOP = 1
    }
}
