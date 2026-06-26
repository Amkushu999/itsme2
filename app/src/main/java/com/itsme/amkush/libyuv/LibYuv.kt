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

    /**
     * Scale I420 source planes to [dstW]×[dstH] and convert to [dstFmt],
     * writing the result into the pre-allocated DirectByteBuffer [dst].
     *
     * The caller is responsible for allocating [dst] large enough via
     * [outputSize]. Thread-safe; no internal state.
     *
     * @param srcY    I420 Y plane (DirectByteBuffer, position=0)
     * @param srcU    I420 U plane (DirectByteBuffer, position=0)
     * @param srcV    I420 V plane (DirectByteBuffer, position=0)
     * @param srcW    Source frame width
     * @param srcH    Source frame height
     * @param dstW    Target output width
     * @param dstH    Target output height
     * @param dstFmt  Android ImageFormat constant (NV21=17, YUV_420_888=35, RGBA_8888=1)
     * @param dst     Pre-allocated DirectByteBuffer; content is replaced on success
     * @return 0 on success, negative code on failure
     */
    external fun convertInto(
        srcY: ByteBuffer,
        srcU: ByteBuffer,
        srcV: ByteBuffer,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        dstFmt: Int,
        dst: ByteBuffer
    ): Int

    /**
     * Compute the required byte size for [format] at [width]×[height].
     * Use this to pre-allocate the [dst] buffer passed to [convertInto].
     */
    fun outputSize(width: Int, height: Int, format: Int): Int = when (format) {
        ImageFormat.RGBA_8888              -> width * height * 4
        ImageFormat.RGB_565                -> width * height * 2
        ImageFormat.NV21,
        ImageFormat.NV16,
        ImageFormat.YUV_420_888            -> width * height * 3 / 2
        0x15 /* NV12 */                    -> width * height * 3 / 2
        else                               -> width * height * 3 / 2
    }
}
