package com.itsme.amkush.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.itsme.amkush.AppState
import com.itsme.amkush.R
import com.itsme.amkush.ffmpeg.FFmpegDecoder
import com.itsme.amkush.hooks.ConfigUpdateReceiver
import com.itsme.amkush.ipc.ISurfaceInjector
import com.itsme.amkush.ipc.RemoteConfig
import com.itsme.amkush.router.SurfaceRouter
import com.itsme.amkush.utils.Logger
import java.nio.ByteBuffer

/**
 * InjectionService — module-process owner of the FFmpeg pipeline.
 *
 * Responsibilities:
 *   1. Run as a foreground service (camera + mediaPlayback type).
 *   2. Expose [ISurfaceInjector] Binder so Xposed hooks inside any hooked
 *      target-app process can deliver Surface objects for frame injection.
 *   3. Own and manage the FFmpeg decode context (via [FFmpegDecoder] JNI).
 *   4. Forward decoded I420 frames to [SurfaceRouter] which scales via LibYuv
 *      and pushes to each registered Surface via ImageWriter.
 *   5. Broadcast config changes to running hooked processes for live URL swaps.
 *
 * Threading:
 *   - Binder calls → Binder thread pool → forward to SurfaceRouter (thread-safe).
 *   - FFmpeg frames → native decode thread → SurfaceRouter.onFrameAvailable()
 *     → per-surface push threads (each with their own ImageWriter).
 */
class InjectionService : Service() {

    companion object {
        private const val TAG             = "InjectionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "facegate_injection_channel"
        private const val CHANNEL_NAME    = "FaceGate Injection Service"

        @Volatile var isRunning = false
            private set

        fun start(
            context: Context,
            targetPackage: String,
            streamUrl: String? = null,
            mediaUri: String?  = null
        ) {
            if (!isRunning) {
                val intent = Intent(context, InjectionService::class.java).apply {
                    putExtra("target_package", targetPackage)
                    putExtra("stream_url",     streamUrl)
                    putExtra("media_uri",      mediaUri)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            if (isRunning) {
                context.stopService(Intent(context, InjectionService::class.java))
                isRunning = false
            }
        }
    }

    // Native decoder handle; 0 = not running
    @Volatile private var decoderHandle: Long = 0L

    // ── ISurfaceInjector Binder stub ─────────────────────────────────────────

    private val binderImpl = object : ISurfaceInjector.Stub() {

        override fun registerSurfaces(
            surfaces: List<Surface>,
            widths: IntArray,
            heights: IntArray,
            formats: IntArray,
            fps: IntArray,
            sessionId: String
        ) {
            Logger.d("$TAG registerSurfaces: ${surfaces.size} surface(s) session=$sessionId")
            SurfaceRouter.registerSession(sessionId, surfaces, widths, heights, formats, fps)
            ensureDecoderRunning()
        }

        override fun unregisterSession(sessionId: String) {
            Logger.d("$TAG unregisterSession: $sessionId")
            SurfaceRouter.unregisterSession(sessionId)
        }

        override fun startDecoder(url: String) {
            Logger.d("$TAG startDecoder: $url")
            if (url.isNotBlank()) startOrRestartDecoder(url)
        }

        override fun hotSwap(url: String) {
            Logger.d("$TAG hotSwap: $url")
            if (url.isBlank()) {
                stopDecoder()
            } else {
                val h = decoderHandle
                if (h != 0L) {
                    FFmpegDecoder.hotSwap(h, url)
                } else {
                    startOrRestartDecoder(url)
                }
            }
        }

        override fun stopAll() {
            Logger.d("$TAG stopAll")
            SurfaceRouter.unregisterAll()
            stopDecoder()
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AppState.context = applicationContext
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Logger.d("$TAG created — FFmpeg native decoder ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetPackage = intent?.getStringExtra("target_package")
        val streamUrl     = intent?.getStringExtra("stream_url")
        val mediaUri      = intent?.getStringExtra("media_uri")

        if (!targetPackage.isNullOrEmpty()) {
            Logger.d("$TAG config → pkg=$targetPackage stream=$streamUrl media=$mediaUri")
            RemoteConfig.setTargetPackage(this, targetPackage)
            RemoteConfig.setStreamUrl(this, streamUrl)
            RemoteConfig.setMediaUri(this, mediaUri)
            RemoteConfig.setInjectionActive(this, true)
            sendConfigBroadcast(streamUrl = streamUrl, mediaUri = mediaUri, active = true)
            val url = streamUrl ?: mediaUri
            if (!url.isNullOrEmpty()) startOrRestartDecoder(url)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binderImpl

    override fun onDestroy() {
        SurfaceRouter.unregisterAll()
        stopDecoder()
        RemoteConfig.clearAll(this)
        sendConfigBroadcast(streamUrl = null, mediaUri = null, active = false)
        isRunning = false
        Logger.d("$TAG destroyed")
        super.onDestroy()
    }

    // ── Decoder management ────────────────────────────────────────────────────

    private val frameCallback = object : FFmpegDecoder.FrameCallback {
        override fun onFrameAvailable(
            yBuf: ByteBuffer, uBuf: ByteBuffer, vBuf: ByteBuffer,
            width: Int, height: Int, ptsUs: Long
        ) {
            // Forward to SurfaceRouter — it copies the planes before this returns
            SurfaceRouter.onFrameAvailable(yBuf, uBuf, vBuf, width, height, ptsUs)
        }
        override fun onError(code: Int, msg: String) {
            Logger.e("$TAG decoder error $code: $msg")
        }
        override fun onEof() {
            Logger.d("$TAG decoder EOF (file will loop)")
        }
    }

    private fun ensureDecoderRunning() {
        if (decoderHandle != 0L) return
        val url = RemoteConfig.getStreamUrl(this) ?: RemoteConfig.getMediaUri(this)
        if (!url.isNullOrEmpty()) {
            startOrRestartDecoder(url)
        } else {
            Logger.d("$TAG no URL configured yet — decoder deferred until URL is set")
        }
    }

    private fun startOrRestartDecoder(url: String) {
        stopDecoder()
        Logger.d("$TAG opening FFmpeg decoder: $url")
        val handle = FFmpegDecoder.open(url, frameCallback)
        if (handle == 0L) {
            Logger.e("$TAG FFmpegDecoder.open failed for: $url")
        } else {
            decoderHandle = handle
            Logger.d("$TAG decoder running (handle=$handle)")
        }
    }

    private fun stopDecoder() {
        val h = decoderHandle
        if (h != 0L) {
            decoderHandle = 0L
            try { FFmpegDecoder.close(h) }
            catch (e: Throwable) { Logger.e("$TAG stopDecoder: ${e.message}") }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendConfigBroadcast(streamUrl: String?, mediaUri: String?, active: Boolean) {
        try {
            sendBroadcast(Intent(ConfigUpdateReceiver.ACTION).apply {
                putExtra(ConfigUpdateReceiver.EXTRA_STREAM_URL, streamUrl)
                putExtra(ConfigUpdateReceiver.EXTRA_MEDIA_URI,  mediaUri)
                putExtra(ConfigUpdateReceiver.EXTRA_ACTIVE,     active)
            })
        } catch (e: Throwable) {
            Logger.e("$TAG sendConfigBroadcast failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FaceGate FFmpeg injection service"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FaceGate")
            .setContentText("FFmpeg camera injection active")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
}
