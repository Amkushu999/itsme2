package com.itsme.amkush.model

/**
 * Represents the current state of the camera hook
 */
enum class HookState {
    /**
     * No hook active
     */
    IDLE,

    /**
     * Hook is initialized but waiting for target app to start camera
     */
    WAITING,

    /**
     * Hook is active and injecting frames
     */
    INJECTING,

    /**
     * Hook failed or encountered an error
     */
    ERROR
}

/**
 * Data class for camera hook status information (injection state tracking)
 */
data class CameraHookInfo(
    val state: HookState = HookState.IDLE,
    val targetPackage: String? = null,
    val targetAppName: String? = null,
    val errorMessage: String? = null,
    val framesInjected: Long = 0,
    val startTime: Long = 0
) {
    fun isActive(): Boolean = state == HookState.INJECTING

    fun isWaiting(): Boolean = state == HookState.WAITING

    fun getIdle(): Boolean = state == HookState.IDLE

    fun getDurationMs(): Long {
        return if (startTime > 0) System.currentTimeMillis() - startTime else 0
    }

    fun getFormattedDuration(): String {
        val ms = getDurationMs()
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%d:%02d", minutes, seconds % 60)
            else -> String.format("%d seconds", seconds)
        }
    }

    companion object {
        fun waiting(packageName: String, appName: String): CameraHookInfo {
            return CameraHookInfo(
                state = HookState.WAITING,
                targetPackage = packageName,
                targetAppName = appName
            )
        }

        fun injecting(packageName: String, appName: String): CameraHookInfo {
            return CameraHookInfo(
                state = HookState.INJECTING,
                targetPackage = packageName,
                targetAppName = appName,
                startTime = System.currentTimeMillis()
            )
        }

        fun error(message: String): CameraHookInfo {
            return CameraHookInfo(
                state = HookState.ERROR,
                errorMessage = message
            )
        }
    }
}