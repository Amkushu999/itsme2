package com.itsme.amkush.router

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.itsme.amkush.ffmpeg.FFmpegDecoder
import com.itsme.amkush.utils.Logger
import java.nio.ByteBuffer

/**
 * ImageLoopSource — static-image fallback for the injection pipeline.
 *
 * Loads a single image from a content/file [Uri], converts it to I420,
 * and delivers it to a [FFmpegDecoder.FrameCallback] at the specified [fps].
 * Loops indefinitely until [stop] is called.
 *
 * Use when the configured source is a local image rather than a video stream,
 * or as a placeholder while a stream URL is being configured.
 */
class ImageLoopSource(
    private val context: Context,
    private val uri: Uri,
    private val fps: Int,
    private val callback: FFmpegDecoder.FrameCallback
) {
    companion object {
        private const val TAG = "ImageLoopSource"
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                loop()
            } catch (e: Throwable) {
                Logger.e("$TAG loop error: ${e.message}")
                callback.onError(-1, e.message ?: "unknown error")
            }
            running = false
        }, TAG).also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun loop() {
        val bitmap = loadBitmap() ?: run {
            Logger.e("$TAG failed to load image: $uri")
            callback.onError(-1, "cannot load image: $uri")
            return
        }

        val w    = bitmap.width
        val h    = bitmap.height
        val i420 = bitmapToI420(bitmap)
        bitmap.recycle()

        // Fixed-position views into the I420 byte array — avoids allocation per frame
        val yBuf = ByteBuffer.wrap(i420, 0,                      w * h)
        val uBuf = ByteBuffer.wrap(i420, w * h,                  w * h / 4)
        val vBuf = ByteBuffer.wrap(i420, w * h + w * h / 4,      w * h / 4)

        val pacer   = FpsPacer(fps)
        val frameUs = if (fps > 0) 1_000_000L / fps else 33_333L
        var pts     = 0L

        while (running) {
            pacer.pace()
            yBuf.rewind(); uBuf.rewind(); vBuf.rewind()
            callback.onFrameAvailable(yBuf, uBuf, vBuf, w, h, pts)
            pts += frameUs
        }

        callback.onEof()
    }

    private fun loadBitmap(): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val raw = BitmapFactory.decodeStream(stream) ?: return null
            if (raw.config == Bitmap.Config.ARGB_8888) raw
            else raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
        }
    } catch (e: Throwable) {
        Logger.e("$TAG loadBitmap: ${e.message}")
        null
    }

    /**
     * Software ARGB → I420 (BT.601 full range).
     * Allocates once; result is held for the lifetime of the loop.
     */
    private fun bitmapToI420(bmp: Bitmap): ByteArray {
        val w      = bmp.width
        val h      = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val i420   = ByteArray(w * h * 3 / 2)
        val uvOff  = w * h

        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = pixels[y * w + x]
                val r  = (px shr 16) and 0xFF
                val g  = (px shr  8) and 0xFF
                val b  =  px         and 0xFF
                // BT.601 limited range
                i420[y * w + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
                    .coerceIn(16, 235).toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    val ui = uvOff + (y / 2) * (w / 2) + x / 2
                    val vi = uvOff + w * h / 4 + (y / 2) * (w / 2) + x / 2
                    i420[ui] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                        .coerceIn(16, 240).toByte()
                    i420[vi] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                        .coerceIn(16, 240).toByte()
                }
            }
        }
        return i420
    }
}
