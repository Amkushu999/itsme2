package com.itsme.amkush.hooks

import android.graphics.ImageFormat
import android.hardware.camera2.CameraDevice
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * Camera2Hooks — FFmpeg/physical-camera-block architecture
 *
 * Every Camera2 (and by extension CameraX) interaction is fully intercepted:
 *
 *   Step 1  CameraManager.openCamera()
 *           → Block the real HAL camera open (set result = null on void method)
 *           → Allocate a fake CameraDeviceImpl via Unsafe (no constructor called)
 *           → Fire StateCallback.onOpened(fakeDevice) asynchronously
 *
 *   Step 2  CameraDeviceImpl.createCaptureSession() variants
 *           → Intercepted on fake devices only (membership check in fakeCamera2Devices)
 *           → Extract the List<Surface> and their resolutions
 *           → Route surfaces to module process (InjectionService / FFmpeg) via Binder
 *           → Allocate a fake CameraCaptureSessionImpl via Unsafe
 *           → Fire StateCallback.onConfigured(fakeSession) asynchronously
 *
 *   Step 3  CameraCaptureSession.setRepeatingRequest() / capture()
 *           → Fake sessions only → start 30-FPS onCaptureCompleted heartbeat
 *
 *   Step 4  close() on either fake object → cleanup + stop heartbeat
 *
 * Result: the physical camera HAL never opens (light stays off, no buffer
 * collision), while the target app believes everything is normal.
 */
object Camera2Hooks {

    private const val TAG = "Camera2Hooks"
    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * Surface → (width, height, ImageFormat) captured at ImageReader.newInstance() time.
     *
     * Android Camera2 does NOT expose getWidth()/getHeight() on android.view.Surface.
     * The canonical way to know a surface's resolution is to track it from the ImageReader
     * (or SurfaceTexture) that created it.  We hook:
     *   • ImageReader.newInstance(w, h, fmt, maxImages)         — most common
     *   • ImageReader.newInstance(w, h, fmt, maxImages, usage)  — API 29+ HardwareBuffer
     *   • SurfaceTexture.setDefaultBufferSize(w, h)             — TextureView preview
     *
     * The map is keyed by Surface object identity and cleared when the surface is
     * returned by createCaptureSession (single write / many reads — no GC pressure).
     */
    val surfaceDimensions: ConcurrentHashMap<Surface, Triple<Int, Int, Int>> = ConcurrentHashMap()

    /**
     * Dimensions stored by SurfaceTexture identity from setDefaultBufferSize().
     * When the app later calls Surface(surfaceTexture), hookSurfaceConstructor() copies
     * the dimensions into surfaceDimensions under the real Surface object.
     */
    private val surfaceTextureDimensions: ConcurrentHashMap<android.graphics.SurfaceTexture, Triple<Int, Int, Int>> =
        ConcurrentHashMap()

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            hookImageReader(lpparam)
            hookSurfaceTexture(lpparam)
            hookSurfaceConstructor(lpparam)
            hookOpenCamera(lpparam)
            hookCameraDeviceMethods(lpparam)
            hookCaptureSessionMethods(lpparam)
            Logger.d("$TAG installed")
        } catch (e: Throwable) {
            Logger.e("$TAG hookAll failed", e)
        }
    }

    // ── ImageReader surface dimension tracking ────────────────────────────────

    /**
     * Hook ImageReader.newInstance() to capture the surface dimensions/format
     * BEFORE createCaptureSession is called.
     *
     * Per the Camera2 docs:
     *   "The Surface returned by [ImageReader.getSurface] can be used as an output
     *    target for the camera capture requests."  The dimensions are set when the
     *    ImageReader is constructed and are immutable thereafter.
     */
    private fun hookImageReader(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = tryFindClass("android.media.ImageReader", lpparam.classLoader) ?: return

        val trackSurface = fun(reader: Any?, w: Int, h: Int, fmt: Int) {
            try {
                val surface = reader?.javaClass?.getMethod("getSurface")?.invoke(reader)
                    as? Surface ?: return
                surfaceDimensions[surface] = Triple(w, h, fmt)
                Logger.d("$TAG ImageReader surface tracked: ${w}x${h} fmt=$fmt")
            } catch (e: Throwable) {
                Logger.d("$TAG ImageReader surface tracking: ${e.message}")
            }
        }

        // API 21+ — newInstance(int width, int height, int format, int maxImages)
        safeHook {
            XposedHelpers.findAndHookMethod(
                cls, "newInstance",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        trackSurface(param.result, param.args[0] as Int,
                            param.args[1] as Int, param.args[2] as Int)
                    }
                }
            )
        }

        // API 29+ — newInstance(int width, int height, int format, int maxImages, long usage)
        safeHook {
            XposedHelpers.findAndHookMethod(
                cls, "newInstance",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        trackSurface(param.result, param.args[0] as Int,
                            param.args[1] as Int, param.args[2] as Int)
                    }
                }
            )
        }
    }

    /**
     * Hook SurfaceTexture.setDefaultBufferSize(width, height).
     *
     * TextureView-based previews wrap a SurfaceTexture.  The app calls
     * setDefaultBufferSize() to set the preview resolution, then passes
     * Surface(surfaceTexture) to createCaptureSession.  We capture the
     * dimensions here so handleSessionCreation() can look them up.
     */
    private fun hookSurfaceTexture(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = tryFindClass("android.graphics.SurfaceTexture", lpparam.classLoader) ?: return
        safeHook {
            XposedHelpers.findAndHookMethod(
                cls, "setDefaultBufferSize",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val st = param.thisObject as? android.graphics.SurfaceTexture ?: return
                        val w = param.args[0] as Int
                        val h = param.args[1] as Int
                        // Store by SurfaceTexture identity, NOT by a new Surface(st) object.
                        // Creating Surface(st) here produces a different object than the one
                        // the app will pass to createCaptureSession, so a lookup by that
                        // temporary Surface would always miss.  hookSurfaceConstructor() will
                        // bridge the SurfaceTexture → Surface mapping when the app's own
                        // Surface(SurfaceTexture) constructor call is intercepted.
                        surfaceTextureDimensions[st] = Triple(w, h, ImageFormat.YUV_420_888)
                        Logger.d("$TAG SurfaceTexture buffer size recorded: ${w}x${h}")
                    }
                }
            )
        }
    }

    /**
     * Hook Surface(SurfaceTexture) constructor.
     *
     * When the app creates its own Surface backed by a SurfaceTexture whose dimensions
     * we already recorded via setDefaultBufferSize(), we transfer those dimensions into
     * surfaceDimensions keyed by the real Surface object.  This ensures
     * handleSessionCreation() finds the correct resolution when the app passes this
     * Surface to createCaptureSession().
     */
    private fun hookSurfaceConstructor(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = tryFindClass("android.view.Surface", lpparam.classLoader) ?: return
        safeHook {
            XposedHelpers.findAndHookConstructor(
                cls,
                android.graphics.SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val st = param.args[0] as? android.graphics.SurfaceTexture ?: return
                        val dims = surfaceTextureDimensions[st] ?: return
                        val surface = param.thisObject as? Surface ?: return
                        surfaceDimensions[surface] = dims
                        Logger.d("$TAG Surface(SurfaceTexture) tracked: ${dims.first}x${dims.second}")
                    }
                }
            )
        }
    }

    // ── Step 1: Block CameraManager.openCamera() ─────────────────────────────

    private fun hookOpenCamera(lpparam: XC_LoadPackage.LoadPackageParam) {
        val managerClass = XposedHelpers.findClass(
            "android.hardware.camera2.CameraManager", lpparam.classLoader
        )

        // openCamera(String, StateCallback, Handler)
        safeHook {
            XposedHelpers.findAndHookMethod(
                managerClass, "openCamera",
                String::class.java,
                CameraDevice.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!AppState.isHookingActive) return
                        blockOpenCamera(
                            param,
                            cameraId    = param.args[0] as? String ?: "0",
                            callback    = param.args[1],
                            handler     = param.args[2],
                            classLoader = lpparam.classLoader
                        )
                    }
                }
            )
        }

        // openCamera(String, Executor, StateCallback) — API 29+ executor overload
        safeHook {
            XposedHelpers.findAndHookMethod(
                managerClass, "openCamera",
                String::class.java,
                java.util.concurrent.Executor::class.java,
                CameraDevice.StateCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!AppState.isHookingActive) return
                        blockOpenCamera(
                            param,
                            cameraId    = param.args[0] as? String ?: "0",
                            callback    = param.args[2],
                            handler     = null,
                            classLoader = lpparam.classLoader
                        )
                    }
                }
            )
        }
    }

    private fun blockOpenCamera(
        param: XC_MethodHook.MethodHookParam,
        cameraId: String,
        callback: Any?,
        handler: Any?,
        classLoader: ClassLoader
    ) {
        Logger.d("$TAG openCamera($cameraId) → blocking physical camera")
        val fakeDevice = FakeCameraObjects.allocateFakeCameraDevice(classLoader, cameraId)
            ?: run { Logger.e("$TAG could not allocate fake CameraDevice — pass-through"); return }

        FakeCameraObjects.deviceCallbacks[fakeDevice] = callback

        // Block the real openCamera — setting result on a void method skips the original
        param.result = null

        // Fire onOpened after the call stack unwinds
        uiHandler.postDelayed({
            FakeCameraObjects.fireOnOpened(fakeDevice, callback, handler)
        }, 40)
    }

    // ── Step 2 & 4: Hook CameraDeviceImpl methods ────────────────────────────

    private fun hookCameraDeviceMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        val implClass = tryFindClass(
            "android.hardware.camera2.impl.CameraDeviceImpl", lpparam.classLoader
        ) ?: return

        // getId() → return the stored camera id string
        safeHook {
            XposedHelpers.findAndHookMethod(implClass, "getId",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !in FakeCameraObjects.fakeCamera2Devices) return
                        param.result = FakeCameraObjects.deviceIds[param.thisObject] ?: "0"
                    }
                }
            )
        }

        // isClosed() → fake devices are never closed until we say so
        safeHook {
            XposedHelpers.findAndHookMethod(implClass, "isClosed",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !in FakeCameraObjects.fakeCamera2Devices) return
                        param.result = false
                    }
                }
            )
        }

        // createCaptureSession(List<Surface>, StateCallback, Handler)
        safeHook {
            XposedHelpers.findAndHookMethod(
                implClass, "createCaptureSession",
                List::class.java,
                android.hardware.camera2.CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val dev = param.thisObject
                        if (dev !in FakeCameraObjects.fakeCamera2Devices) return
                        param.result = null
                        val surfaces = (param.args[0] as? List<*>)?.filterNotNull() ?: return
                        handleSessionCreation(dev, surfaces, param.args[1], param.args[2], lpparam.classLoader)
                    }
                }
            )
        }

        // createCaptureSessionByOutputConfigurations(List<OutputConfig>, StateCallback, Handler)
        safeHook {
            XposedHelpers.findAndHookMethod(
                implClass, "createCaptureSessionByOutputConfigurations",
                List::class.java,
                android.hardware.camera2.CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val dev = param.thisObject
                        if (dev !in FakeCameraObjects.fakeCamera2Devices) return
                        param.result = null
                        val surfaces = extractSurfacesFromOutputConfigs(param.args[0] as? List<*>)
                        handleSessionCreation(dev, surfaces, param.args[1], param.args[2], lpparam.classLoader)
                    }
                }
            )
        }

        // createCaptureSession(SessionConfiguration) — API 28+
        safeHook {
            val sessionConfigClass = tryFindClass(
                "android.hardware.camera2.params.SessionConfiguration", lpparam.classLoader
            ) ?: return@safeHook
            XposedHelpers.findAndHookMethod(
                implClass, "createCaptureSession",
                sessionConfigClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val dev = param.thisObject
                        if (dev !in FakeCameraObjects.fakeCamera2Devices) return
                        param.result = null
                        val sessionConfig = param.args[0] ?: return
                        val outputConfigs = safeCall {
                            sessionConfig.javaClass.getMethod("getOutputConfigurations")
                                .invoke(sessionConfig) as? List<*>
                        }
                        val surfaces = extractSurfacesFromOutputConfigs(outputConfigs)
                        val stateCallback = safeCall {
                            sessionConfig.javaClass.getMethod("getStateCallback").invoke(sessionConfig)
                        }
                        handleSessionCreation(dev, surfaces, stateCallback, null, lpparam.classLoader)
                    }
                }
            )
        }

        // createCaptureRequest(int templateType) → stub out a fake Builder
        safeHook {
            XposedHelpers.findAndHookMethod(implClass, "createCaptureRequest",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !in FakeCameraObjects.fakeCamera2Devices) return
                        param.result = allocateFakeCaptureRequestBuilder(lpparam.classLoader)
                    }
                }
            )
        }

        // close() → cleanup the fake device + tell InjectionService to stop this session
        safeHook {
            XposedHelpers.findAndHookMethod(implClass, "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val dev = param.thisObject
                        if (dev !in FakeCameraObjects.fakeCamera2Devices) return
                        param.result = null
                        InjectionServiceClient.stopSession(dev)
                        FakeCameraObjects.cleanupDevice(dev)
                        Logger.d("$TAG fake CameraDevice closed")
                    }
                }
            )
        }
    }

    // ── Step 3 & 4: Hook CameraCaptureSessionImpl methods ────────────────────

    private fun hookCaptureSessionMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        val implClass = tryFindClass(
            "android.hardware.camera2.impl.CameraCaptureSessionImpl", lpparam.classLoader
        ) ?: return

        val captureCallbackClass =
            android.hardware.camera2.CameraCaptureSession.CaptureCallback::class.java
        val captureRequestClass = android.hardware.camera2.CaptureRequest::class.java

        // setRepeatingRequest → start 30-fps heartbeat
        safeHook {
            XposedHelpers.findAndHookMethod(
                implClass, "setRepeatingRequest",
                captureRequestClass, captureCallbackClass, Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !in FakeCameraObjects.fakeCaptureSessionsMap) return
                        param.result = 0  // fake sequence id
                        FakeCameraObjects.startCaptureHeartbeat(param.thisObject, param.args[1], param.args[0])
                    }
                }
            )
        }

        // capture → single-shot callback (also start heartbeat for apps that use capture loops)
        safeHook {
            XposedHelpers.findAndHookMethod(
                implClass, "capture",
                captureRequestClass, captureCallbackClass, Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !in FakeCameraObjects.fakeCaptureSessionsMap) return
                        param.result = 1
                        FakeCameraObjects.startCaptureHeartbeat(param.thisObject, param.args[1], param.args[0])
                    }
                }
            )
        }

        // setRepeatingBurst / captureBurst — same treatment
        safeHook {
            XposedHelpers.findAndHookMethod(
                implClass, "setRepeatingBurst",
                List::class.java, captureCallbackClass, Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !in FakeCameraObjects.fakeCaptureSessionsMap) return
                        param.result = 0
                        val firstRequest = (param.args[0] as? List<*>)?.firstOrNull()
                        FakeCameraObjects.startCaptureHeartbeat(param.thisObject, param.args[1], firstRequest)
                    }
                }
            )
        }

        // stopRepeating → stop heartbeat
        safeHook {
            XposedHelpers.findAndHookMethod(implClass, "stopRepeating",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !in FakeCameraObjects.fakeCaptureSessionsMap) return
                        param.result = null
                        FakeCameraObjects.stopCaptureHeartbeat(param.thisObject)
                    }
                }
            )
        }

        // close() → cleanup
        safeHook {
            XposedHelpers.findAndHookMethod(implClass, "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val session = param.thisObject
                        if (session !in FakeCameraObjects.fakeCaptureSessionsMap) return
                        param.result = null
                        FakeCameraObjects.stopCaptureHeartbeat(session)
                        FakeCameraObjects.fakeCaptureSessionsMap.remove(session)
                        Logger.d("$TAG fake CaptureSession closed")
                    }
                }
            )
        }

        // getDevice() → return owning fake CameraDevice
        safeHook {
            XposedHelpers.findAndHookMethod(implClass, "getDevice",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val meta = FakeCameraObjects.fakeCaptureSessionsMap[param.thisObject] ?: return
                        param.result = meta.cameraDevice
                    }
                }
            )
        }

        // isReprocessable() → false
        safeHook {
            XposedHelpers.findAndHookMethod(implClass, "isReprocessable",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !in FakeCameraObjects.fakeCaptureSessionsMap) return
                        param.result = false
                    }
                }
            )
        }

        // prepare(Surface) → no-op on fake sessions
        safeHook {
            XposedHelpers.findAndHookMethod(implClass, "prepare",
                android.view.Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !in FakeCameraObjects.fakeCaptureSessionsMap) return
                        param.result = null
                    }
                }
            )
        }
    }

    // ── Session construction helper ───────────────────────────────────────────

    private fun handleSessionCreation(
        dev: Any,
        surfaces: List<Any>,
        stateCallback: Any?,
        handler: Any?,
        classLoader: ClassLoader
    ) {
        val sessionId = "${System.identityHashCode(dev)}_${System.currentTimeMillis()}"
        Logger.d("$TAG handleSessionCreation: ${surfaces.size} surfaces session=$sessionId")

        // Look up per-surface dimensions captured from ImageReader.newInstance() /
        // SurfaceTexture.setDefaultBufferSize().  Fall back to 1280x720 if unknown.
        val widths  = IntArray(surfaces.size) { i ->
            (surfaces[i] as? Surface)?.let { surfaceDimensions[it]?.first } ?: 1280
        }
        val heights = IntArray(surfaces.size) { i ->
            (surfaces[i] as? Surface)?.let { surfaceDimensions[it]?.second } ?: 720
        }
        val formats = IntArray(surfaces.size) { i ->
            (surfaces[i] as? Surface)?.let { surfaceDimensions[it]?.third }
                ?: ImageFormat.YUV_420_888
        }

        // Deliver surfaces to InjectionService → FFmpeg in the module process
        val fps = IntArray(surfaces.size) { 30 }
        InjectionServiceClient.routeSurfaces(dev, surfaces, widths, heights, formats, fps, sessionId)

        // Build the fake session proxy
        val fakeSession = FakeCameraObjects.allocateFakeCaptureSession(
            classLoader, dev, surfaces, stateCallback, null, handler, sessionId
        ) ?: return

        // Fire onConfigured after call-stack unwind
        uiHandler.postDelayed({ FakeCameraObjects.fireOnConfigured(fakeSession) }, 60)
    }

    // ── Allocation helpers ────────────────────────────────────────────────────

    private fun allocateFakeCaptureRequestBuilder(classLoader: ClassLoader): Any? = try {
        val cls = XposedHelpers.findClass(
            "android.hardware.camera2.CaptureRequest\$Builder", classLoader
        )
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        f.isAccessible = true
        val unsafe = f.get(null)!!
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls)
    } catch (e: Throwable) {
        Logger.e("$TAG allocateFakeBuilder: ${e.message}"); null
    }

    private fun extractSurfacesFromOutputConfigs(configs: List<*>?): List<Any> =
        configs?.mapNotNull { oc ->
            safeCall { oc?.javaClass?.getMethod("getSurface")?.invoke(oc) }
        } ?: emptyList()

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun safeHook(block: () -> Unit) {
        try { block() } catch (e: Throwable) { Logger.d("$TAG hook skipped: ${e.message}") }
    }

    private fun <T> safeCall(block: () -> T?): T? = try { block() } catch (_: Throwable) { null }

    private fun tryFindClass(name: String, classLoader: ClassLoader): Class<*>? = try {
        XposedHelpers.findClass(name, classLoader)
    } catch (e: Throwable) {
        Logger.e("$TAG class not found: $name"); null
    }
}
