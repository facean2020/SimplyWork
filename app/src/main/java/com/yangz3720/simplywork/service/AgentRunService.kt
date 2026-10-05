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
 * Foreground service for Agent tasks (v1.6, `specialUse` type).
 *
 * **It executes nothing** -- the run is driven by the process-level
 * [com.yangz3720.simplywork.core.AgentRunSupervisor]. This service does only two things:
 *  1. `startForeground` pins the process to the foreground so the run is not reclaimed after the screen locks, the app goes to the background, or the Activity is destroyed;
 *  2. projects the run state into a single notification (with "stop" and "return to handling" actions).
 *
 * Why execution is not put inside the service: a Service is an Android component and cannot be unit-tested, and once execution logic comes in,
 * the mainline can never be verified on the JVM again. The boundary is guarded here, so both sides stay testable on their own.
 *
 * The lifecycle is driven by the supervisor's start/stop callbacks (injected by AppContainer): run starts -> startForegroundService,
 * run ends -> stopService.
 */
class AgentRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** `onStartCommand` is called on every start; the collector is attached only once. */
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = SimplyWorkApp.container

        if (intent?.action == ACTION_STOP) {
            container.runSupervisor.stop()
            return START_NOT_STICKY
        }

        // Must reach the foreground within 5s: first post one synchronously with the current state, then hand off to the collector to refresh.
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

    /** No binder is exposed: state travels through the supervisor's StateFlow, so cross-process/component binding is unnecessary. */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The three shapes of the notification body. Hitting a HITL during a run is the only "pit" in this mechanism: approval and authorization both suspend waiting for the UI,
     * and with nobody in the background to tap them they wait forever, so these two states must be stated explicitly (and the whole notification must be tappable back to the UI).
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
     * IMPORTANCE_LOW (no sound, no heads-up banner): this is a persistent process indicator, not an alert. That is why "waiting for your confirmation"
     * does not ring either -- whether to open a separate high-priority channel for states needing human intervention is a product decision, and the default takes the conservative value.
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
