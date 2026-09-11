package com.yangz3720.simplywork.infrastructure.systemops

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Slowly-changing platform capabilities relevant to the Intent path.
 * Foreground state is deliberately NOT cached here — it changes per call and is
 * sampled at execution time by the intent executor.
 */
data class SystemCapabilities(
    /** C5: POST_NOTIFICATIONS runtime permission state. */
    val notificationsPermissionGranted: Boolean,

    /** C4: whether the app holds the SYSTEM_ALERT_WINDOW exemption (background activity launch). */
    val canLaunchActivityFromBackground: Boolean
)

interface SystemCapabilityProbe {

    /**
     * Cold flow: re-evaluated on every collect (both flags are cheap runtime checks),
     * so permission changes made in system settings are reflected on the next subscription.
     */
    fun observe(): Flow<SystemCapabilities>

    /** Fresh snapshot for fast routing decisions. */
    fun snapshot(): SystemCapabilities
}

class SystemCapabilityProbeImpl(
    private val notificationsGranted: () -> Boolean,
    private val overlayGranted: () -> Boolean
) : SystemCapabilityProbe {

    override fun observe(): Flow<SystemCapabilities> = flow { emit(current()) }

    override fun snapshot(): SystemCapabilities = current()

    private fun current() = SystemCapabilities(
        notificationsPermissionGranted = notificationsGranted(),
        canLaunchActivityFromBackground = overlayGranted()
    )

    companion object {
        /** Android binding for production use. */
        fun from(context: Context): SystemCapabilityProbeImpl = SystemCapabilityProbeImpl(
            notificationsGranted = {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            },
            overlayGranted = { Settings.canDrawOverlays(context) }
        )
    }
}
