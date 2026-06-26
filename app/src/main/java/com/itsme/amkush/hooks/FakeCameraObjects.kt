package com.itsme.amkush.hooks

import android.hardware.camera2.CameraDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * FakeCameraObjects
 *
 * Allocates uninitialized instances of CameraDeviceImpl and
 * CameraCaptureSessionImpl via sun.misc.Unsafe (skips the JNI-backed
 * constructors) and tracks them so that class-level Xposed hooks can
 * distinguish fake instances from real ones.
 *
 * Lifecycle managed by Camera2Hooks / Camera1Hooks.
 */
object FakeCameraObjects {

    private const val TAG = "FakeCamera"

    // ── Fake-instance registries ─────────────────────────────────────────────
    val fakeCamera2Devices: MutableSet<Any> =
        Collections.newSetFromMap(ConcurrentHashMap())
    val fakeCaptureSessionsMap: MutableMap<Any, SessionMeta> = ConcurrentHashMap()

    // Per-device: the callback that originally came in with openCamera()
    val deviceCallbacks: MutableMap<Any, Any?> = ConcurrentHashMap()

    // Per-device: the cameraId string ("0", "1", …)
    val deviceIds: MutableMap<Any, String> = ConcurrentHashMap()

    // Per-session: the repeating-capture scheduler
    private val captureSchedulers: MutableMap<Any, ScheduledFuture<*>> = ConcurrentHashMap()

    // Shared executor for fake capture-completed heartbeats
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "FakeCapturePump").apply { isDaemon = true }
    }
    private val frameCounter = AtomicLong(0)

    data class SessionMeta(
        val surfaces: List<Any>,          // android.view.Surface objects
        val stateCallback: Any?,          // CameraCaptureSession.StateCallback
        val captureCallback: Any?,        // CameraCaptureSession.CaptureCallback
        val handler: Any?,                // android.os.Handler (nullable)
        val cameraDevice: Any,            // the owning fake CameraDevice
        val sessionId: String
    )

    // ── Unsafe accessor ──────────────────────────────────────────────────────

    private val unsafe: Any by lazy {
        val f: Field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        f.isAccessible = true
        f.get(null)!!
    }

    private fun unsafeAllocateInstance(cls: Class<*>): Any {
        val method = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, cls)!!
    }

    // ── Fake CameraDevice (Camera2) ──────────────────────────────────────────

    fun allocateFakeCameraDevice(classLoader: ClassLoader, cameraId: String): Any? {
        return try {
            val cls = XposedHelpers.findClass(
                "android.hardware.camera2.impl.CameraDeviceImpl", classLoader
            )
            val instance = unsafeAllocateInstance(cls)
            fakeCamera2Devices.add(instance)
            deviceIds[instance] = cameraId
            // Inject id field so getId() works without a hook
            injectField(instance, "mCameraId", cameraId)
            Logger.d("$TAG allocateFakeCameraDevice: id=$cameraId instance=${System.identityHashCode(instance)}")
            instance
        } catch (e: Throwable) {
            Logger.e("$TAG allocateFakeCameraDevice failed", e)
            null
        }
    }

    // ── Fake CameraCaptureSession ────────────────────────────────────────────

    fun allocateFakeCaptureSession(
        classLoader: ClassLoader,
        owningDevice: Any,
        surfaces: List<Any>,
        stateCallback: Any?,
        captureCallback: Any?,
        handler: Any?,
        sessionId: String
    ): Any? {
        return try {
            val cls = XposedHelpers.findClass(
                "android.hardware.camera2.impl.CameraCaptureSessionImpl", classLoader
            )
            val instance = unsafeAllocateInstance(cls)
            val meta = SessionMeta(surfaces, stateCallback, captureCallback, handler, owningDevice, sessionId)
            fakeCaptureSessionsMap[instance] = meta
            // Inject device reference so getDevice() works without a hook
            injectField(instance, "mDevice", owningDevice)
            Logger.d("$TAG allocateFakeCaptureSession: sessionId=$sessionId")
            instance
        } catch (e: Throwable) {
            Logger.e("$TAG allocateFakeCaptureSession failed", e)
            null
        }
    }

    // ── Callback firing ──────────────────────────────────────────────────────

    /**
     * Fire StateCallback.onOpened(fakeDevice) on the supplied handler (or main
     * thread if null).  Called by Camera2Hooks after returning from openCamera().
     */
    fun fireOnOpened(fakeDevice: Any, stateCallback: Any?, handler: Any?) {
        if (stateCallback == null) return
        val runnable = Runnable {
            try {
                val cb = stateCallback as? CameraDevice.StateCallback
                    ?: return@Runnable
                cb.onOpened(fakeDevice as CameraDevice)
            } catch (e: Throwable) {
                Logger.e("$TAG fireOnOpened failed", e)
            }
        }
        postOnHandler(handler, runnable)
    }

    /**
     * Fire StateCallback.onConfigured(fakeSession) on the handler embedded in
     * the session meta.
     */
    fun fireOnConfigured(fakeSession: Any) {
        val meta = fakeCaptureSessionsMap[fakeSession] ?: return
        val callback = meta.stateCallback ?: return
        val runnable = Runnable {
            try {
                val method = callback.javaClass.getMethod(
                    "onConfigured",
                    android.hardware.camera2.CameraCaptureSession::class.java
                )
                method.invoke(callback, fakeSession)
            } catch (e: Throwable) {
                Logger.e("$TAG fireOnConfigured failed", e)
            }
        }
        postOnHandler(meta.handler, runnable)
    }

    /**
     * Start a periodic heartbeat that fires CaptureCallback.onCaptureCompleted()
     * at ~30 FPS so the target app keeps receiving "new frame" signals.
     */
    fun startCaptureHeartbeat(fakeSession: Any, captureCallback: Any?, requestObject: Any?) {
        if (captureCallback == null) return
        stopCaptureHeartbeat(fakeSession)

        val future = scheduler.scheduleAtFixedRate({
            try {
                val frameNum = frameCounter.incrementAndGet()
                val completeMethod = captureCallback.javaClass.methods
                    .firstOrNull { it.name == "onCaptureCompleted" && it.parameterCount == 3 }
                    ?: return@scheduleAtFixedRate
                val sessionParam = fakeSession
                val requestParam  = requestObject
                val resultParam   = buildFakeCaptureResult(fakeSession) ?: return@scheduleAtFixedRate
                completeMethod.invoke(captureCallback, sessionParam, requestParam, resultParam)
            } catch (_: Throwable) {}
        }, 33, 33, TimeUnit.MILLISECONDS)

        captureSchedulers[fakeSession] = future
    }

    fun stopCaptureHeartbeat(fakeSession: Any) {
        captureSchedulers.remove(fakeSession)?.cancel(false)
    }

    fun cleanupDevice(fakeDevice: Any) {
        fakeCamera2Devices.remove(fakeDevice)
        deviceCallbacks.remove(fakeDevice)
        deviceIds.remove(fakeDevice)
        // stop all sessions owned by this device
        val deadSessions = fakeCaptureSessionsMap.entries
            .filter { it.value.cameraDevice === fakeDevice }
            .map { it.key }
        deadSessions.forEach { session ->
            stopCaptureHeartbeat(session)
            fakeCaptureSessionsMap.remove(session)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun postOnHandler(handler: Any?, runnable: Runnable) {
        try {
            if (handler is Handler) {
                handler.post(runnable)
                return
            }
            // fallback: main thread
            Handler(Looper.getMainLooper()).post(runnable)
        } catch (_: Throwable) {
            Handler(Looper.getMainLooper()).post(runnable)
        }
    }

    private fun injectField(target: Any, fieldName: String, value: Any?) {
        try {
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField(fieldName)
                    f.isAccessible = true
                    f.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {}
                cls = cls.superclass
            }
        } catch (e: Throwable) {
            Logger.d("$TAG injectField($fieldName) skipped: ${e.message}")
        }
    }

    /**
     * Build a minimal TotalCaptureResult using Unsafe so that apps expecting a
     * non-null result from onCaptureCompleted() don't crash.
     */
    private fun buildFakeCaptureResult(fakeSession: Any): Any? {
        return try {
            val cls = Class.forName("android.hardware.camera2.TotalCaptureResult")
            unsafeAllocateInstance(cls)
        } catch (e: Throwable) {
            null
        }
    }

    // ── Camera1 fake Camera ──────────────────────────────────────────────────

    val fakeCamera1Instances: MutableSet<Any> =
        Collections.newSetFromMap(ConcurrentHashMap())

    fun allocateFakeCamera1(classLoader: ClassLoader, cameraId: Int): Any? {
        return try {
            val cls = XposedHelpers.findClass("android.hardware.Camera", classLoader)
            val instance = unsafeAllocateInstance(cls)
            fakeCamera1Instances.add(instance)
            Logger.d("$TAG allocateFakeCamera1: id=$cameraId")
            instance
        } catch (e: Throwable) {
            Logger.e("$TAG allocateFakeCamera1 failed", e)
            null
        }
    }

    fun cleanupCamera1(instance: Any) {
        fakeCamera1Instances.remove(instance)
    }
}
