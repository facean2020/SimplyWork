package com.yangz3720.simplywork.infrastructure.model.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import com.yangz3720.simplywork.infrastructure.util.AgentLogger

/**
 * Execution tier decided by hardware capabilities.
 */
enum class AgentExecutionMode {
    /**
     * Tier A: On-device local small model (LiteRT/MNN) for offline routing & compaction + Cloud for reasoning.
     */
    HYBRID_LOCAL_CLOUD,

    /**
     * Tier B: Cloud-only for all operations.
     */
    CLOUD_ONLY
}

/**
 * Evaluated device hardware profile.
 */
data class DeviceProfile(
    val totalRamMb: Long,
    val availableStorageMb: Long,
    val vulkanVersion: Int,
    val hasVulkan12OrHigher: Boolean,
    val executionMode: AgentExecutionMode
)

/**
 * On-device capability and hardware tier detector.
 */
object DeviceCapabilityDetector {

    private const val TAG = "DeviceDetector"
    private const val MIN_LOCAL_RAM_MB = 7500L       // ~8GB physical RAM threshold
    private const val MIN_LOCAL_STORAGE_MB = 10240L   // 10GB free internal storage threshold
    private const val VULKAN_1_2_HEX = 0x40080000     // Vulkan 1.2 encoded version

    fun evaluate(context: Context): DeviceProfile {
        val totalRamMb = getTotalRamMb(context)
        val availableStorageMb = getAvailableStorageMb(context)
        val vulkanVersion = getVulkanVersion(context)
        val hasVulkan12 = vulkanVersion >= VULKAN_1_2_HEX

        val isEligibleForHybrid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                totalRamMb >= MIN_LOCAL_RAM_MB &&
                availableStorageMb >= MIN_LOCAL_STORAGE_MB &&
                hasVulkan12

        val mode = if (isEligibleForHybrid) {
            AgentExecutionMode.HYBRID_LOCAL_CLOUD
        } else {
            AgentExecutionMode.CLOUD_ONLY
        }

        val profile = DeviceProfile(
            totalRamMb = totalRamMb,
            availableStorageMb = availableStorageMb,
            vulkanVersion = vulkanVersion,
            hasVulkan12OrHigher = hasVulkan12,
            executionMode = mode
        )

        AgentLogger.i(TAG) { "Evaluated Device Profile: $profile" }
        return profile
    }

    private fun getTotalRamMb(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem / (1024 * 1024)
        } catch (e: Exception) {
            AgentLogger.w(TAG, e) { "Failed to read total RAM" }
            0L
        }
    }

    private fun getAvailableStorageMb(context: Context): Long {
        return try {
            val statFs = StatFs(context.filesDir.absolutePath)
            (statFs.availableBlocksLong * statFs.blockSizeLong) / (1024 * 1024)
        } catch (e: Exception) {
            AgentLogger.w(TAG, e) { "Failed to read available storage" }
            0L
        }
    }

    private fun getVulkanVersion(context: Context): Int {
        return try {
            val features = context.packageManager.systemAvailableFeatures
            for (feature in features) {
                if (feature.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) {
                    return feature.version
                }
            }
            0
        } catch (e: Exception) {
            AgentLogger.w(TAG, e) { "Failed to check Vulkan version" }
            0
        }
    }
}
