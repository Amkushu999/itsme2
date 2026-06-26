package com.itsme.amkush.hooks

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Camera1Hooks — FFmpeg/physical-camera-block architecture
 *
 * Completely blocks the Camera1 (android.hardware.Camera) API:
 *
 *   open()  / open(int)
 *     → Block real camera open (result = null skips the JNI native call)
 *     → Allocate a fake Camera instance via Unsafe (skips JNI constructor)
 *     → Return the fake instance to the app
 *
 *   setPreviewDisplay(SurfaceHolder) / setPreviewTexture(SurfaceTexture)
 *     → Capture the preview Surface
 *     → Route it to InjectionService (FFmpeg) via InjectionServiceClient
 *
 *   startPreview()
 *     → Start a 30-fps PreviewCallback heartbeat if callback was registered
 *     → FFmpeg starts pushing frames to the surface in the module process
 *
 *   stopPreview() / release()
 *     → Stop heartbeat, notify InjectionService to stop
 *
 *   takePicture(shutter, raw, jpeg)
 *     → Intercept jpeg callback, return a JPEG of the current FFmpeg frame
 *     → The JPEG is synthesized from AppState.currentFrame (set by FfmpegStreamer
 *       fallback or by FFmpeg frame callback forwarded over SharedPrefs/IPC)
 *
 *   setPreviewCallback / setPreviewCallbackWithBuffer
 *     → Wrap the original callback; inject fake NV21 data each frame
 *
 * NOTE: AppState.currentFrame is still used here for the setPreviewCallback
 * path (when the app reads frames via callback rather than from a Surface).
 * The FFmpeg pipeline fills AppState.currentFrame in the module process and
 * the data is made available via the IPC channel.
 */
object Camera1Hooks {

    private const val TAG = "Camera1Hooks"
    private val uiHandler = Handler(Looper.getMainLooper())

    // Per fake-Camera instance state
    private data class Cam1State(
        var width: Int  = 640,
        var height: Int = 480,
        var format: Int = ImageFormat.NV21,
        var fps: Int    = 30,
        var surface: Surface? = null,
        var previewRunning: Boolean = false,
        var sessionId: String = ""
    )
    private val camStates: ConcurrentHashMap<Any, Cam1State> = ConcurrentHashMap()

    // Heartbeat scheduler for PreviewCallback-based apps
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Cam1PreviewPump").apply { isDaemon = true }
    }
    private val heartbeats: ConcurrentHashMap<Any, ScheduledFuture<*>> = ConcurrentHashMap()
    private val previewCallbacks: ConcurrentHashMap<Any, Camera.PreviewCallback> = ConcurrentHashMap()

    // ── Install ───────────────────────────────────────────────────────────────

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val camClass = XposedHelpers.findClass("android.hardware.Camera", lpparam.classLoader)
            hookOpen(camClass, lpparam.classLoader)
            hookSetParameters(camClass)
            hookGetParameters(camClass, lpparam.classLoader)
            hookSetPreviewDisplay(camClass)
            hookSetPreviewTexture(camClass)
            hookSetPreviewCallback(camClass)
            hookSetPreviewCallbackWithBuffer(camClass)
            hookSetOneShotPreviewCallback(camClass)
            hookStartPreview(camClass)
            hookStopPreview(camClass)
            hookTakePicture(camClass)
            hookRelease(camClass)
            hookAutoFocus(camClass)
            hookGetNumberOfCameras(camClass)
            hookGetCameraInfo(camClass, lpparam.classLoader)
            Logger.d("$TAG hooks installed")
        } catch (e: Throwable) {
            Logger.e("$TAG hookAll failed", e)
        }
    }

    // ── open() / open(int) ────────────────────────────────────────────────────

    private fun hookOpen(camClass: Class<*>, classLoader: ClassLoader) {
        val openHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!AppState.isHookingActive) return
                val cameraId = param.args.getOrNull(0) as? Int ?: 0
                Logger.d("$TAG Camera.open($cameraId) → blocking physical camera")
                val fake = FakeCameraObjects.allocateFakeCamera1(classLoader, cameraId)
                    ?: run { Logger.e("$TAG allocateFakeCamera1 failed"); return }
                camStates[fake] = Cam1State()
                // Block the real native open; return the fake instance
                param.result = fake
            }
        }
        // open()
        try { XposedHelpers.findAndHookMethod(camClass, "open", openHook) } catch (_: Throwable) {}
        // open(int cameraId)
        try { XposedHelpers.findAndHookMethod(camClass, "open", Int::class.javaPrimitiveType, openHook) } catch (_: Throwable) {}
    }

    // ── setParameters ─────────────────────────────────────────────────────────

    private fun hookSetParameters(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "setParameters", Camera.Parameters::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null  // block the JNI call
                        val state = camStates[fake] ?: return
                        val p = param.args[0] as? Camera.Parameters ?: return
                        val sz = p.previewSize
                        if (sz != null) { state.width = sz.width; state.height = sz.height }
                        state.format = p.previewFormat
                        val fpsRange = IntArray(2)
                        p.getPreviewFpsRange(fpsRange)
                        if (fpsRange[1] > 0) state.fps = fpsRange[1] / 1000
                        Logger.d("$TAG setParameters: ${state.width}x${state.height} fmt=${state.format} fps=${state.fps}")
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG setParameters hook: ${e.message}") }
    }

    // ── getParameters ─────────────────────────────────────────────────────────

    private fun hookGetParameters(camClass: Class<*>, classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "getParameters",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        // Return a basic Camera.Parameters with sane defaults
                        param.result = buildFakeParameters(camClass, classLoader, camStates[fake])
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG getParameters hook: ${e.message}") }
    }

    private fun buildFakeParameters(
        camClass: Class<*>,
        classLoader: ClassLoader,
        state: Cam1State?
    ): Camera.Parameters? = try {
        // Camera.Parameters can only be constructed from a native Camera.
        // We grab it via Unsafe, then inject a minimal flat string representation.
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        f.isAccessible = true
        val unsafe = f.get(null)!!
        val paramsClass = Camera.Parameters::class.java
        val params = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, paramsClass) as Camera.Parameters
        // Inject flat map via the hidden params field if possible
        try {
            val mapField = paramsClass.getDeclaredField("mMap").also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val map = mapField.get(params) as? java.util.HashMap<String, String>
                ?: java.util.HashMap<String, String>().also { mapField.set(params, it) }
            val w = state?.width  ?: 640
            val h = state?.height ?: 480
            map["preview-size"] = "${w}x${h}"
            map["picture-size"] = "${w}x${h}"
            map["preview-format"] = "yuv420sp"  // NV21
            map["preview-frame-rate"] = "${state?.fps ?: 30}"
            map["preview-fps-range"] = "${(state?.fps ?: 30) * 1000},${(state?.fps ?: 30) * 1000}"
            map["preview-size-values"] = "640x480,1280x720,1920x1080"
            map["flash-mode"] = "off"
            map["focus-mode"] = "fixed"
        } catch (_: Throwable) {}
        params
    } catch (e: Throwable) {
        Logger.e("$TAG buildFakeParameters: ${e.message}")
        null
    }

    // ── setPreviewDisplay ─────────────────────────────────────────────────────

    private fun hookSetPreviewDisplay(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "setPreviewDisplay", SurfaceHolder::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null
                        val holder = param.args[0] as? SurfaceHolder ?: return
                        val surface = holder.surface ?: return
                        val state = camStates[fake] ?: Cam1State().also { camStates[fake] = it }
                        state.surface = surface
                        routeCamera1Surface(fake, surface, state)
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG setPreviewDisplay: ${e.message}") }
    }

    // ── setPreviewTexture ─────────────────────────────────────────────────────

    private fun hookSetPreviewTexture(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "setPreviewTexture",
                android.graphics.SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null
                        val texture = param.args[0] as? android.graphics.SurfaceTexture ?: return
                        val surface = Surface(texture)
                        val state = camStates[fake] ?: Cam1State().also { camStates[fake] = it }
                        state.surface = surface
                        routeCamera1Surface(fake, surface, state)
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG setPreviewTexture: ${e.message}") }
    }

    private fun routeCamera1Surface(fake: Any, surface: Surface, state: Cam1State) {
        val sessionId = "cam1_${System.identityHashCode(fake)}"
        state.sessionId = sessionId
        InjectionServiceClient.routeSurfaces(
            fake,
            listOf(surface),
            intArrayOf(state.width),
            intArrayOf(state.height),
            intArrayOf(state.format),
            intArrayOf(state.fps),
            sessionId
        )
        Logger.d("$TAG preview surface routed: ${state.width}x${state.height}")
    }

    // ── setPreviewCallback variants ───────────────────────────────────────────

    private fun hookSetPreviewCallback(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "setPreviewCallback",
                Camera.PreviewCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null  // block native setPreviewCallback
                        val cb = param.args[0] as? Camera.PreviewCallback
                        if (cb != null) previewCallbacks[fake] = cb
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG setPreviewCallback: ${e.message}") }
    }

    private fun hookSetPreviewCallbackWithBuffer(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "setPreviewCallbackWithBuffer",
                Camera.PreviewCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null
                        val cb = param.args[0] as? Camera.PreviewCallback
                        if (cb != null) previewCallbacks[fake] = cb
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG setPreviewCallbackWithBuffer: ${e.message}") }
    }

    private fun hookSetOneShotPreviewCallback(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "setOneShotPreviewCallback",
                Camera.PreviewCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null
                        val cb = param.args[0] as? Camera.PreviewCallback
                        if (cb != null) previewCallbacks[fake] = cb
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG setOneShotPreviewCallback: ${e.message}") }
    }

    // ── startPreview / stopPreview ────────────────────────────────────────────

    private fun hookStartPreview(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "startPreview",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null
                        val state = camStates[fake] ?: return
                        state.previewRunning = true
                        Logger.d("$TAG startPreview")
                        startPreviewHeartbeat(fake, state)
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG startPreview: ${e.message}") }
    }

    private fun hookStopPreview(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "stopPreview",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null
                        camStates[fake]?.previewRunning = false
                        stopPreviewHeartbeat(fake)
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG stopPreview: ${e.message}") }
    }

    /**
     * Start a periodic timer that invokes the registered PreviewCallback
     * with a fresh NV21 frame from AppState.currentFrame.
     * This path only fires when the app uses setPreviewCallback (not Surface).
     * When a Surface is used, FFmpeg pushes directly via ImageWriter.
     */
    private fun startPreviewHeartbeat(fake: Any, state: Cam1State) {
        stopPreviewHeartbeat(fake)
        val intervalMs = (1000L / state.fps.coerceIn(1, 60))
        val future = scheduler.scheduleAtFixedRate({
            if (!state.previewRunning) return@scheduleAtFixedRate
            val cb = previewCallbacks[fake] ?: return@scheduleAtFixedRate
            val frame = AppState.currentFrame
            if (frame.isEmpty) return@scheduleAtFixedRate
            try {
                val nv21 = scaleNv21(frame.data, frame.width, frame.height, state.width, state.height)
                cb.onPreviewFrame(nv21, fake as? Camera)
            } catch (_: Throwable) {}
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        heartbeats[fake] = future
    }

    private fun stopPreviewHeartbeat(fake: Any) {
        heartbeats.remove(fake)?.cancel(false)
    }

    // ── takePicture ───────────────────────────────────────────────────────────

    private fun hookTakePicture(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "takePicture",
                Camera.ShutterCallback::class.java,
                Camera.PictureCallback::class.java,
                Camera.PictureCallback::class.java,
                Camera.PictureCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null
                        val shutterCb = param.args[0] as? Camera.ShutterCallback
                        val jpegCb    = param.args[3] as? Camera.PictureCallback ?: return
                        val state     = camStates[fake] ?: Cam1State()
                        uiHandler.postDelayed({
                            shutterCb?.onShutter()
                            val jpeg = buildFakeJpeg(state) ?: return@postDelayed
                            jpegCb.onPictureTaken(jpeg, fake as? Camera)
                        }, 200)
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG takePicture: ${e.message}") }
    }

    // ── release ───────────────────────────────────────────────────────────────

    private fun hookRelease(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "release",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null
                        val state = camStates[fake]
                        if (state != null) InjectionServiceClient.stopSession(fake)
                        stopPreviewHeartbeat(fake)
                        previewCallbacks.remove(fake)
                        camStates.remove(fake)
                        FakeCameraObjects.cleanupCamera1(fake)
                        Logger.d("$TAG Camera.release()")
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG release: ${e.message}") }
    }

    // ── autoFocus ─────────────────────────────────────────────────────────────

    private fun hookAutoFocus(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "autoFocus", Camera.AutoFocusCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fake = param.thisObject
                        if (fake !in FakeCameraObjects.fakeCamera1Instances) return
                        param.result = null
                        val cb = param.args[0] as? Camera.AutoFocusCallback
                        // Report focus success immediately
                        uiHandler.postDelayed({ cb?.onAutoFocus(true, fake as? Camera) }, 100)
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG autoFocus: ${e.message}") }
    }

    // ── getNumberOfCameras ────────────────────────────────────────────────────

    private fun hookGetNumberOfCameras(camClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(camClass, "getNumberOfCameras",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!AppState.isHookingActive) return
                        // Pretend there are 2 cameras (front + rear)
                        param.result = 2
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG getNumberOfCameras: ${e.message}") }
    }

    // ── getCameraInfo ─────────────────────────────────────────────────────────

    private fun hookGetCameraInfo(camClass: Class<*>, classLoader: ClassLoader) {
        try {
            val infoClass = XposedHelpers.findClass("android.hardware.Camera\$CameraInfo", classLoader)
            XposedHelpers.findAndHookMethod(camClass, "getCameraInfo",
                Int::class.javaPrimitiveType, infoClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!AppState.isHookingActive) return
                        param.result = null
                        val cameraId = param.args[0] as? Int ?: 0
                        val info = param.args[1] ?: return
                        try {
                            info.javaClass.getField("facing").also { it.isAccessible = true }
                                .setInt(info, if (cameraId == 1) Camera.CameraInfo.CAMERA_FACING_FRONT
                                              else Camera.CameraInfo.CAMERA_FACING_BACK)
                            info.javaClass.getField("orientation").also { it.isAccessible = true }
                                .setInt(info, 90)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (e: Throwable) { Logger.d("$TAG getCameraInfo: ${e.message}") }
    }

    // ── Pixel utilities ───────────────────────────────────────────────────────

    private fun buildFakeJpeg(state: Cam1State): ByteArray? {
        val frame = AppState.currentFrame
        if (frame.isEmpty) return null
        return try {
            val nv21 = scaleNv21(frame.data, frame.width, frame.height, state.width, state.height)
            val yuv  = YuvImage(nv21, ImageFormat.NV21, state.width, state.height, null)
            val out  = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, state.width, state.height), 90, out)
            out.toByteArray()
        } catch (e: Throwable) { null }
    }

    /** Nearest-neighbour NV21 scaler (shared with SurfaceRouter in module process). */
    private fun scaleNv21(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        if (srcW == dstW && srcH == dstH) return src
        val dst    = ByteArray(dstW * dstH * 3 / 2)
        val xRatio = (srcW shl 16) / dstW
        val yRatio = (srcH shl 16) / dstH
        for (y in 0 until dstH) {
            val srcRow = ((y * yRatio) shr 16).coerceIn(0, srcH - 1) * srcW
            for (x in 0 until dstW) {
                dst[y * dstW + x] = src[srcRow + ((x * xRatio) shr 16).coerceIn(0, srcW - 1)]
            }
        }
        val srcUvOff = srcW * srcH; val dstUvOff = dstW * dstH
        val dstUvW = dstW / 2; val srcUvW = srcW / 2
        for (y in 0 until dstH / 2) {
            val srcY = ((y * yRatio) shr 16).coerceIn(0, srcH / 2 - 1)
            for (x in 0 until dstUvW) {
                val srcX = ((x * xRatio) shr 16).coerceIn(0, srcW / 2 - 1)
                val di = dstUvOff + (y * dstUvW + x) * 2
                val si = srcUvOff + (srcY * srcUvW + srcX) * 2
                dst[di] = src[si]; dst[di + 1] = src[si + 1]
            }
        }
        return dst
    }
}
