package com.itsme.amkush

import android.graphics.ImageFormat
import android.view.Surface
import android.view.SurfaceHolder
import com.itsme.amkush.utils.Logger

object CameraState {

    @Volatile var currentFormat: Int = ImageFormat.NV21
    @Volatile var currentWidth: Int = 640
    @Volatile var currentHeight: Int = 480
    @Volatile var requestedFps: Int = 30
    @Volatile var isPreviewActive: Boolean = false
    @Volatile var isCaptureActive: Boolean = false

    private val surfaceConfigs = java.util.concurrent.ConcurrentHashMap<Surface, SurfaceConfig>()
    private val allSurfaces = java.util.concurrent.CopyOnWriteArrayList<Surface>()

    @Volatile var camera1Instance: Any? = null
    @Volatile var camera1Holder: SurfaceHolder? = null
    @Volatile var camera2Device: Any? = null

    @Volatile var previewSurface: Surface? = null
    @Volatile var imageReaderSurface: Surface? = null
    @Volatile var recordingSurface: Surface? = null

    data class SurfaceConfig(
        val format: Int,
        val width: Int,
        val height: Int,
        val usage: String = "unknown"
    )

    fun addSurface(surface: Surface, config: SurfaceConfig) {
        surfaceConfigs[surface] = config
        allSurfaces.add(surface)

        when {
            config.usage.contains("preview") -> previewSurface = surface
            config.usage.contains("reader") -> imageReaderSurface = surface
            config.usage.contains("recording") -> recordingSurface = surface
        }

        Logger.d("Surface added: ${config.usage}, ${config.width}x${config.height}, format=${config.format}")
    }

    fun getSurfaceConfig(surface: Surface): SurfaceConfig? {
        return surfaceConfigs[surface]
    }

    fun removeSurface(surface: Surface) {
        surfaceConfigs.remove(surface)
        allSurfaces.remove(surface)

        if (surface == previewSurface) previewSurface = null
        if (surface == imageReaderSurface) imageReaderSurface = null
        if (surface == recordingSurface) recordingSurface = null
    }

    fun getActiveConfig(): SurfaceConfig {
        val best = surfaceConfigs.values.maxByOrNull { it.width * it.height }
        return best ?: SurfaceConfig(currentFormat, currentWidth, currentHeight, "default")
    }

    fun getBestResolution(): Pair<Int, Int> {
        val config = getActiveConfig()
        return Pair(config.width, config.height)
    }

    fun getSurfaceCount(): Int = allSurfaces.size

    fun hasPreviewSurface(): Boolean = previewSurface != null
    fun hasImageReaderSurface(): Boolean = imageReaderSurface != null
    fun hasRecordingSurface(): Boolean = recordingSurface != null

    fun getAllSurfaces(): List<Surface> = allSurfaces.toList()

    fun reset() {
        surfaceConfigs.clear()
        allSurfaces.clear()
        previewSurface = null
        imageReaderSurface = null
        recordingSurface = null
        camera1Instance = null
        camera1Holder = null
        camera2Device = null
        currentFormat = ImageFormat.NV21
        currentWidth = 640
        currentHeight = 480
        requestedFps = 30
        isPreviewActive = false
        isCaptureActive = false
        Logger.d("CameraState reset")
    }

    fun logState() {
        Logger.d("CameraState: format=$currentFormat, ${currentWidth}x${currentHeight}, fps=$requestedFps")
        Logger.d("All Surfaces Count: ${allSurfaces.size}")
        for ((surface, config) in surfaceConfigs) {
            Logger.d("  Surface: ${config.usage}, ${config.width}x${config.height}, format=${config.format}")
        }
    }
}