package com.itsme.amkush.libyuv

import android.graphics.ImageFormat
import java.nio.ByteBuffer

/**
 * JNI bridge to libyuv_wrapper.so
 *
 * Provides I420 scaling and format conversion for the SurfaceRouter hot path.
 * All methods operate on pre-allocated DirectByteBuffers — zero JVM heap
 * allocation in the frame-push loop.
 */
object LibYuv {

    init {
        System.loadLibrary("libyuv_wrapper")
    }

    // ── Error Codes (must match C++ side exactly) ─────────────────────────────
    const val ERR_NULL_BUFFER     = -1
    const val ERR_UNSUPPORTED_FMT = -2
    const val ERR_DST_TOO_SMALL   = -3
    const val ERR_INVALID_DIMS    = -4
    const val ERR_INVALID_STRIDE  = -5
    const val ERR_SRC_TOO_SMALL   = -6
    const val ERR_OOM             = -7

    /**
     * Scale I420 source planes to [dstW]×[dstH] and convert to [dstFmt],
     * writing the result into the pre-allocated DirectByteBuffer [dst].
     *
     * Strides express the distance in bytes between the start of consecutive
     * rows.  For tightly-packed I420 frames from ffmpeg_decoder:
     *   srcStrideY = srcW
     *   srcStrideU = (srcW + 1) / 2
     *   srcStrideV = (srcW + 1) / 2
     *
     * Thread-safe — no internal state.
     *
     * @return 0 on success, negative [ERR_*] code on failure.
     */
    external fun convertInto(
        srcY: ByteBuffer,
        srcU: ByteBuffer,
        srcV: ByteBuffer,
        srcW: Int,
        srcH: Int,
        srcStrideY: Int,
        srcStrideU: Int,
        srcStrideV: Int,
        dstW: Int,
        dstH: Int,
        dstFmt: Int,
        dst: ByteBuffer
    ): Int

    /**
     * Compute the exact required byte size for [format] at [width]×[height].
     * Use this to pre-allocate the [dst] buffer passed to [convertInto].
     */
    fun outputSize(width: Int, height: Int, format: Int): Int {
        require(width > 0 && height > 0) { "Dimensions must be positive" }
        val ySize = width * height
        return when (format) {
            ImageFormat.RGBA_8888  -> ySize * 4
            ImageFormat.RGB_565    -> ySize * 2
            ImageFormat.NV16       -> {
                // YUV 4:2:2 — chroma subsampled horizontally only
                val uvSize = ((width + 1) / 2) * height
                ySize + 2 * uvSize
            }
            ImageFormat.NV21,
            ImageFormat.YUV_420_888,
            0x15 /* NV12 */        -> {
                // YUV 4:2:0 — chroma subsampled both axes
                val uvSize = ((width + 1) / 2) * ((height + 1) / 2)
                ySize + 2 * uvSize
            }
            else                   -> {
                val uvSize = ((width + 1) / 2) * ((height + 1) / 2)
                ySize + 2 * uvSize
            }
        }
    }

    /**
     * Translate a native error code into a human-readable string for logging.
     */
    fun getErrorMessage(code: Int): String = when (code) {
        ERR_NULL_BUFFER     -> "Null DirectByteBuffer address"
        ERR_UNSUPPORTED_FMT -> "Unsupported destination format"
        ERR_DST_TOO_SMALL   -> "Destination buffer is too small"
        ERR_INVALID_DIMS    -> "Invalid width/height dimensions"
        ERR_INVALID_STRIDE  -> "Source strides are smaller than widths"
        ERR_SRC_TOO_SMALL   -> "Source buffers are too small for declared strides"
        ERR_OOM             -> "Out of memory (malloc failed)"
        else                -> "Unknown native error: $code"
    }
}
