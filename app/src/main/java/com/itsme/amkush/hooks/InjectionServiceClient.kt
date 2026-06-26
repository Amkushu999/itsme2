package com.itsme.amkush.hooks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.Surface
import com.itsme.amkush.AppState
import com.itsme.amkush.ipc.ISurfaceInjector
import com.itsme.amkush.utils.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * InjectionServiceClient — runs inside the hooked target-app process.
 *
 * Manages the Binder connection to InjectionService (module process) and
 * forwards camera surface deliveries via ISurfaceInjector AIDL.
 *
 * Replaces GStreamerSurfaceRouter with the same connection and queuing
 * pattern, but extended with fps-per-surface and startDecoder / hotSwap.
 *
 * Connection lifecycle:
 *   - First routeSurfaces() or hotSwap() call triggers connectToInjectionService().
 *   - Pending deliveries are queued and drained on onServiceConnected.
 *   - Automatic reconnect on next call after onServiceDisconnected.
 */
object InjectionServiceClient {

    private const val TAG             = "InjectionClient"
    private const val MODULE_PKG      = "com.itsme.amkush"
    private const val INJECTOR_ACTION = "com.itsme.amkush.action.SURFACE_INJECTOR"

    @Volatile private var injector: ISurfaceInjector? = null
    @Volatile private var bindPending = false

    // Maps device/camera handle → sessionId for lifecycle cleanup
    private val deviceSessions: ConcurrentHashMap<Any, String> = ConcurrentHashMap()

    private data class PendingDelivery(
        val surfaces: List<Surface>,
        val widths: IntArray,
        val heights: IntArray,
        val formats: IntArray,
        val fps: IntArray,
        val sessionId: String
    )
    private val pendingDeliveries: ArrayDeque<PendingDelivery> = ArrayDeque()

    // Queue for hotSwap / startDecoder calls that arrive before the service connects.
    private enum class UrlActionType { HOT_SWAP, START_DECODER }
    private data class PendingUrlAction(val type: UrlActionType, val url: String)
    private val pendingUrlActions: ArrayDeque<PendingUrlAction> = ArrayDeque()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = ISurfaceInjector.Stub.asInterface(binder)
            injector = svc
            AppState.injectorService = svc
            bindPending = false
            Logger.d("$TAG connected to InjectionService")
            drainPending()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
            AppState.injectorService = null
            Logger.d("$TAG disconnected — will reconnect on next call")
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Route collected camera surfaces to InjectionService (FFmpeg pipeline).
     * Thread-safe; safe to call from any Xposed hook thread.
     *
     * @param owner     Fake CameraDevice / Camera1 handle (for session tracking)
     * @param surfaces  Surfaces from createCaptureSession / setPreviewDisplay
     * @param widths    Per-surface expected width
     * @param heights   Per-surface expected height
     * @param formats   Per-surface ImageFormat constant
     * @param fps       Per-surface target frame rate
     * @param sessionId Unique identifier for this session
     */
    fun routeSurfaces(
        owner: Any,
        surfaces: List<Any>,
        widths: IntArray,
        heights: IntArray,
        formats: IntArray,
        fps: IntArray,
        sessionId: String
    ) {
        if (surfaces.isEmpty()) return
        deviceSessions[owner] = sessionId

        val filtered = surfaces.filterIsInstance<Surface>()
        if (filtered.isEmpty()) return

        val inj = injector
        if (inj != null) {
            deliverNow(inj, filtered, widths, heights, formats, fps, sessionId)
        } else {
            synchronized(pendingDeliveries) {
                pendingDeliveries.addLast(
                    PendingDelivery(filtered, widths, heights, formats, fps, sessionId)
                )
            }
            connectToInjectionService()
        }
    }

    /**
     * Tell InjectionService to stop writing to surfaces for this device.
     * Called when a fake CameraDevice.close() or Camera.release() is intercepted.
     */
    fun stopSession(owner: Any) {
        val sessionId = deviceSessions.remove(owner) ?: return
        try {
            injector?.unregisterSession(sessionId)
            Logger.d("$TAG unregisterSession: $sessionId")
        } catch (e: Throwable) {
            Logger.e("$TAG stopSession failed", e)
        }
    }

    /** Ask InjectionService to stop all sessions and close the decoder. */
    fun stopAll() {
        try { injector?.stopAll() } catch (_: Throwable) {}
        deviceSessions.clear()
    }

    /**
     * Ask InjectionService to hot-swap to a new stream URL.
     * If not yet connected, queues the URL and establishes the connection.
     * Previously the URL was silently dropped when not connected.
     */
    fun hotSwap(url: String) {
        try {
            val inj = injector
            if (inj != null) {
                inj.hotSwap(url)
            } else {
                synchronized(pendingUrlActions) {
                    pendingUrlActions.addLast(PendingUrlAction(UrlActionType.HOT_SWAP, url))
                }
                connectToInjectionService()
            }
        } catch (e: Throwable) {
            Logger.e("$TAG hotSwap failed", e)
        }
    }

    /** Ask InjectionService to start (or restart) the FFmpeg decoder with [url].
     *  If not yet connected, queues the call for delivery after connection. */
    fun startDecoder(url: String) {
        try {
            val inj = injector
            if (inj != null) {
                inj.startDecoder(url)
            } else {
                synchronized(pendingUrlActions) {
                    pendingUrlActions.addLast(PendingUrlAction(UrlActionType.START_DECODER, url))
                }
                connectToInjectionService()
            }
        } catch (e: Throwable) {
            Logger.e("$TAG startDecoder failed", e)
        }
    }

    // ── Binder connection ──────────────────────────────────────────────────────

    private fun connectToInjectionService() {
        if (bindPending || injector != null) return
        val ctx = AppState.context ?: run {
            Logger.e("$TAG cannot connect — no context"); return
        }
        synchronized(this) {
            if (bindPending || injector != null) return
            bindPending = true
            try {
                val intent = Intent(INJECTOR_ACTION).setPackage(MODULE_PKG)
                val bound  = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    bindPending = false
                    Logger.e("$TAG bindService returned false — InjectionService not reachable")
                } else {
                    Logger.d("$TAG bindService sent to InjectionService")
                }
            } catch (e: Throwable) {
                bindPending = false
                Logger.e("$TAG connectToInjectionService failed", e)
            }
        }
    }

    private fun deliverNow(
        inj: ISurfaceInjector,
        surfaces: List<Surface>,
        widths: IntArray,
        heights: IntArray,
        formats: IntArray,
        fps: IntArray,
        sessionId: String
    ) {
        try {
            inj.registerSurfaces(surfaces, widths, heights, formats, fps, sessionId)
            Logger.d("$TAG delivered ${surfaces.size} surface(s) session=$sessionId")
        } catch (e: Throwable) {
            Logger.e("$TAG deliverNow failed (session=$sessionId)", e)
        }
    }

    private fun drainPending() {
        val inj = injector ?: return

        // Snapshot the queues under their respective locks, then release the locks
        // BEFORE making any Binder IPC calls.  Holding a lock during IPC (which can
        // take tens of ms) would stall any thread trying to queue a new delivery.
        val deliveries = synchronized(pendingDeliveries) {
            pendingDeliveries.toList().also { pendingDeliveries.clear() }
        }
        val urlActions = synchronized(pendingUrlActions) {
            pendingUrlActions.toList().also { pendingUrlActions.clear() }
        }

        deliveries.forEach { d ->
            deliverNow(inj, d.surfaces, d.widths, d.heights, d.formats, d.fps, d.sessionId)
        }
        urlActions.forEach { action ->
            try {
                when (action.type) {
                    UrlActionType.HOT_SWAP    -> inj.hotSwap(action.url)
                    UrlActionType.START_DECODER -> inj.startDecoder(action.url)
                }
                Logger.d("$TAG drained pending ${action.type}: ${action.url}")
            } catch (e: Throwable) {
                Logger.e("$TAG drainPending URL action failed", e)
            }
        }
    }
}
