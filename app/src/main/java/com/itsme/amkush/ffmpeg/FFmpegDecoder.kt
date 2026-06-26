package com.itsme.amkush.ffmpeg

import java.nio.ByteBuffer

/**
 * JNI bridge to ffmpeg_decoder.so
 *
 * Wraps a native AVFormatContext + AVCodecContext decode loop.
 * Each open() call starts a background native thread that reads/decodes
 * the stream and calls FrameCallback.onFrameAvailable() for every I420 frame.
 *
 * Threading:
 *   - open()  / close() / hotSwap() — caller's thread (Binder or main thread)
 *   - FrameCallback methods         — native decode thread (not UI thread)
 */
object FFmpegDecoder {

    /**
     * Callback fired on the native decode thread for each decoded video frame.
     *
     * IMPORTANT: Do NOT retain references to yBuf / uBuf / vBuf beyond this
     * call — they are DirectByteBuffers backed by AVFrame native memory that
     * is reused (or freed) as soon as onFrameAvailable() returns.
     * Copy the bytes if you need to hold them.
     */
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
     * Open a stream and start the decode loop.
     *
     * @param url  RTSP / HLS / HTTP(S) / local file URL
     * @param cb   Frame callback (held as a global JNI ref until close())
     * @return Opaque native handle, or 0 on failure.
     */
    external fun open(url: String, cb: FrameCallback): Long

    /**
     * Stop the decode loop, join the decode thread, free all native resources.
     * Must be called on the same handle returned by open().
     */
    external fun close(handle: Long)

    /**
     * Signal the decode thread to close the current stream and reopen with [url].
     * Returns true if the signal was posted, false if the handle is invalid.
     * The actual swap happens asynchronously within ~200 ms.
     */
    external fun hotSwap(handle: Long, url: String): Boolean

    /** Returns the stream width (0 if handle is invalid or not yet decoded). */
    external fun getWidth(handle: Long): Int

    /** Returns the stream height (0 if handle is invalid or not yet decoded). */
    external fun getHeight(handle: Long): Int
}
