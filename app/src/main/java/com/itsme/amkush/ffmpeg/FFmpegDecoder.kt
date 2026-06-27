package com.itsme.amkush.ffmpeg

import androidx.annotation.Keep
import java.nio.ByteBuffer

/**
 * JNI bridge to ffmpeg_decoder.so
 *
 * Wraps a native AVFormatContext + AVCodecContext decode loop.
 * Each open() call starts a background native thread that reads/decodes
 * the stream and calls FrameCallback.onFrameAvailable() for every I420 frame.
 *
 * Threading:
 *   - open() / close() / hotSwap() — caller's thread (Binder or main thread)
 *   - FrameCallback methods        — native decode thread (NOT the UI thread)
 */
@Keep
object FFmpegDecoder {

    /**
     * Callback fired on the native decode thread for each decoded video frame.
     *
     * IMPORTANT: Do NOT retain references to yBuf / uBuf / vBuf beyond this
     * call — they are DirectByteBuffers backed by AVFrame native memory that
     * is reused (or freed) as soon as onFrameAvailable() returns.
     * Copy the bytes immediately if you need to hold them.
     *
     * Frame format: tightly-packed I420 (no padding between rows).
     *   Y plane: width × height bytes,       stride = width
     *   U plane: uvWidth × uvHeight bytes,   stride = uvWidth  (uvWidth = (width+1)/2)
     *   V plane: uvWidth × uvHeight bytes,   stride = uvWidth
     */
    @Keep
    interface FrameCallback {
        fun onFrameAvailable(
            yBuf: ByteBuffer,
            uBuf: ByteBuffer,
            vBuf: ByteBuffer,
            width: Int,
            height: Int,
            ptsUs: Long
        )
        fun onError(code: Int, msg: String)
        fun onEof()
    }

    init {
        System.loadLibrary("ffmpeg_decoder")
    }

    /**
     * Open a stream and start the native decode loop.
     *
     * @param url   RTSP / RTSPS / HLS / HTTP(S) / RTMP / SRT / local file URL
     * @param cb    Frame callback (held as a JNI global ref until [close])
     * @return Opaque native handle (pointer to DecoderCtx), or 0 on failure.
     */
    @JvmStatic external fun open(url: String, cb: FrameCallback): Long

    /**
     * Stop the decode loop, join the decode thread, and free all native resources.
     * The handle must not be used after this call.
     */
    @JvmStatic external fun close(handle: Long)

    /**
     * Signal the decode thread to close the current stream and reopen with [url].
     * Returns true if the signal was posted; false if the handle is invalid.
     * The actual swap happens asynchronously on the decode thread (~200 ms).
     */
    @JvmStatic external fun hotSwap(handle: Long, url: String): Boolean

    /** Returns the decoded stream width (0 if the handle is invalid or stream not yet opened). */
    @JvmStatic external fun getWidth(handle: Long): Int

    /** Returns the decoded stream height (0 if the handle is invalid or stream not yet opened). */
    @JvmStatic external fun getHeight(handle: Long): Int
}
