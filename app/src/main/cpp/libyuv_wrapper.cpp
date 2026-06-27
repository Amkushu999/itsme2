#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <memory>
#include "libyuv.h"

#define LOG_TAG "LibYuvWrapper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ── Android ImageFormat Constants ─────────────────────────────────────────────
static const int FMT_RGBA_8888   = 0x01;   // ImageFormat.RGBA_8888
static const int FMT_RGB_565     = 0x04;   // ImageFormat.RGB_565
static const int FMT_NV16        = 0x10;   // ImageFormat.NV16  (YUV 4:2:2)
static const int FMT_NV21        = 0x11;   // ImageFormat.NV21
static const int FMT_NV12        = 0x15;   // (no public constant — common value)
static const int FMT_YUV_420_888 = 0x23;   // ImageFormat.YUV_420_888

// ── Explicit Error Codes (mirrored in LibYuv.kt) ──────────────────────────────
static const int ERR_NULL_BUFFER     = -1;
static const int ERR_UNSUPPORTED_FMT = -2;
static const int ERR_DST_TOO_SMALL   = -3;
static const int ERR_INVALID_DIMS    = -4;
static const int ERR_INVALID_STRIDE  = -5;
static const int ERR_SRC_TOO_SMALL   = -6;
static const int ERR_OOM             = -7;

// ── Helpers ───────────────────────────────────────────────────────────────────

struct FreeDeleter { void operator()(void* p) const { std::free(p); } };
using ScopedMalloc = std::unique_ptr<uint8_t, FreeDeleter>;

static int scaleI420(
    const uint8_t* srcY, int srcStrY,
    const uint8_t* srcU, int srcStrU,
    const uint8_t* srcV, int srcStrV,
    int srcW, int srcH,
    uint8_t* dstY, int dstStrY,
    uint8_t* dstU, int dstStrU,
    uint8_t* dstV, int dstStrV,
    int dstW, int dstH)
{
    return libyuv::I420Scale(
        srcY, srcStrY, srcU, srcStrU, srcV, srcStrV, srcW, srcH,
        dstY, dstStrY, dstU, dstStrU, dstV, dstStrV,
        dstW, dstH, libyuv::kFilterBilinear);
}

// I420 plane sizes (4:2:0)
static void getI420Sizes(int w, int h, size_t& ySize, size_t& uvSize) {
    ySize  = static_cast<size_t>(w) * h;
    uvSize = static_cast<size_t>((w + 1) / 2) * ((h + 1) / 2);
}

// NV16 plane sizes (4:2:2 — chroma NOT subsampled vertically)
static void getNV16Sizes(int w, int h, size_t& ySize, size_t& uvSize) {
    ySize  = static_cast<size_t>(w) * h;
    // UV is interleaved, half width but FULL height
    uvSize = static_cast<size_t>((w + 1) / 2) * 2 * h;
}

// ── JNI Export ────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_itsme_amkush_libyuv_LibYuv_convertInto(
    JNIEnv* env, jclass,
    jobject srcYBuf, jobject srcUBuf, jobject srcVBuf,
    jint srcW, jint srcH,
    jint srcStrideY, jint srcStrideU, jint srcStrideV,
    jint dstW, jint dstH,
    jint dstFmt,
    jobject dstBuf)
{
    auto* srcY = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcYBuf));
    auto* srcU = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcUBuf));
    auto* srcV = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcVBuf));
    auto* dst  = static_cast<uint8_t*>(env->GetDirectBufferAddress(dstBuf));

    if (!srcY || !srcU || !srcV || !dst) {
        LOGE("convertInto: null DirectByteBuffer address");
        return ERR_NULL_BUFFER;
    }

    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
        LOGE("convertInto: invalid dimensions %dx%d → %dx%d", srcW, srcH, dstW, dstH);
        return ERR_INVALID_DIMS;
    }

    if (std::abs(srcStrideY) < srcW ||
        std::abs(srcStrideU) < (srcW + 1) / 2 ||
        std::abs(srcStrideV) < (srcW + 1) / 2) {
        LOGE("convertInto: source strides (%d,%d,%d) < widths (%d,%d,%d)",
             srcStrideY, srcStrideU, srcStrideV,
             srcW, (srcW + 1) / 2, (srcW + 1) / 2);
        return ERR_INVALID_STRIDE;
    }

    const bool hasNegativeStride = (srcStrideY < 0 || srcStrideU < 0 || srcStrideV < 0);
    if (!hasNegativeStride) {
        jlong capY = env->GetDirectBufferCapacity(srcYBuf);
        jlong capU = env->GetDirectBufferCapacity(srcUBuf);
        jlong capV = env->GetDirectBufferCapacity(srcVBuf);
        const int srcHUV = (srcH + 1) / 2;

        if (capY < static_cast<jlong>(srcStrideY) * srcH  ||
            capU < static_cast<jlong>(srcStrideU) * srcHUV ||
            capV < static_cast<jlong>(srcStrideV) * srcHUV) {
            LOGE("convertInto: source buffer(s) too small");
            return ERR_SRC_TOO_SMALL;
        }
    }

    // Output strides
    const int dstStrY     = dstW;
    const int dstStrUV    = (dstW + 1) / 2;
    const int dstStrUV_NV = ((dstW + 1) / 2) * 2;  // interleaved UV stride

    size_t ySize = 0, uvSize = 0;
    size_t required = 0;

    // ── Format-specific size calculation ──────────────────────────────────────
    switch (dstFmt) {
        case FMT_YUV_420_888:
        case FMT_NV21:
        case FMT_NV12: {
            getI420Sizes(dstW, dstH, ySize, uvSize);
            required = ySize + 2 * uvSize;
            break;
        }
        case FMT_NV16: {
            // NV16 is 4:2:2 — UV plane is full height, interleaved
            getNV16Sizes(dstW, dstH, ySize, uvSize);
            required = ySize + uvSize;  // ySize + (width * height)
            break;
        }
        case FMT_RGBA_8888:
            required = static_cast<size_t>(dstW) * dstH * 4;
            ySize = required;  // reuse ySize as total size for offset math
            break;
        case FMT_RGB_565:
            required = static_cast<size_t>(dstW) * dstH * 2;
            ySize = required;
            break;
        default:
            LOGE("convertInto: unsupported format 0x%x", dstFmt);
            return ERR_UNSUPPORTED_FMT;
    }

    jlong dstCap = env->GetDirectBufferCapacity(dstBuf);
    if (dstCap < static_cast<jlong>(required)) {
        LOGE("convertInto: dst buffer too small (need %zu, have %lld)",
             required, (long long)dstCap);
        return ERR_DST_TOO_SMALL;
    }

    int ret = 0;
    const bool needsScale = (srcW != dstW || srcH != dstH);

    switch (dstFmt) {

    // ── YUV_420_888 (tightly packed planar I420) ─────────────────────────────
    case FMT_YUV_420_888: {
        uint8_t* dY = dst;
        uint8_t* dU = dst + ySize;
        uint8_t* dV = dU  + uvSize;

        if (!needsScale && srcStrideY == srcW &&
            srcStrideU == (srcW + 1) / 2 && srcStrideV == (srcW + 1) / 2) {
            std::memcpy(dY, srcY, ySize);
            std::memcpy(dU, srcU, uvSize);
            std::memcpy(dV, srcV, uvSize);
            ret = 0;
        } else if (!needsScale) {
            ret = libyuv::I420Copy(
                srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV, srcW, srcH,
                dY, dstStrY, dU, dstStrUV, dV, dstStrUV, dstW, dstH);
        } else {
            ret = scaleI420(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                            srcW, srcH,
                            dY, dstStrY, dU, dstStrUV, dV, dstStrUV,
                            dstW, dstH);
        }
        break;
    }

    // ── NV21, NV12 (4:2:0 interleaved) ────────────────────────────────────────
    case FMT_NV21:
    case FMT_NV12: {
        if (!needsScale) {
            if (dstFmt == FMT_NV21) {
                ret = libyuv::I420ToNV21(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                                         dst, dstStrY, dst + ySize, dstStrUV_NV, dstW, dstH);
            } else {
                ret = libyuv::I420ToNV12(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                                         dst, dstStrY, dst + ySize, dstStrUV_NV, dstW, dstH);
            }
        } else {
            ScopedMalloc tmp(static_cast<uint8_t*>(std::malloc(ySize + 2 * uvSize)));
            if (!tmp) { LOGE("malloc failed"); return ERR_OOM; }

            uint8_t* tmpY = tmp.get();
            uint8_t* tmpU = tmpY + ySize;
            uint8_t* tmpV = tmpU + uvSize;

            ret = scaleI420(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                            srcW, srcH,
                            tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
                            dstW, dstH);

            if (ret == 0) {
                if (dstFmt == FMT_NV21) {
                    ret = libyuv::I420ToNV21(tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
                                             dst, dstStrY, dst + ySize, dstStrUV_NV, dstW, dstH);
                } else {
                    ret = libyuv::I420ToNV12(tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
                                             dst, dstStrY, dst + ySize, dstStrUV_NV, dstW, dstH);
                }
            }
        }
        break;
    }

    // ── NV16 (4:2:2 interleaved — chroma NOT subsampled vertically) ───────────
    case FMT_NV16: {
        if (!needsScale) {
            // Direct conversion: I420 → NV16
            ret = libyuv::I420ToNV16(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                                     dst, dstStrY, dst + ySize, dstStrUV_NV, dstW, dstH);
        } else {
            // Scale to I420 first, then convert to NV16
            size_t i420YSize, i420UVSize;
            getI420Sizes(dstW, dstH, i420YSize, i420UVSize);
            size_t i420Total = i420YSize + 2 * i420UVSize;

            ScopedMalloc tmp(static_cast<uint8_t*>(std::malloc(i420Total)));
            if (!tmp) { LOGE("malloc failed"); return ERR_OOM; }

            uint8_t* tmpY = tmp.get();
            uint8_t* tmpU = tmpY + i420YSize;
            uint8_t* tmpV = tmpU + i420UVSize;

            ret = scaleI420(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                            srcW, srcH,
                            tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
                            dstW, dstH);

            if (ret == 0) {
                ret = libyuv::I420ToNV16(tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
                                         dst, dstStrY, dst + ySize, dstStrUV_NV, dstW, dstH);
            }
        }
        break;
    }

    // ── RGBA_8888 ─────────────────────────────────────────────────────────────
    case FMT_RGBA_8888: {
        if (!needsScale) {
            ret = libyuv::I420ToABGR(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                                     dst, dstW * 4, dstW, dstH);
        } else {
            size_t i420YSize, i420UVSize;
            getI420Sizes(dstW, dstH, i420YSize, i420UVSize);
            size_t i420Total = i420YSize + 2 * i420UVSize;

            ScopedMalloc tmp(static_cast<uint8_t*>(std::malloc(i420Total)));
            if (!tmp) { LOGE("malloc failed"); return ERR_OOM; }

            uint8_t* tmpY = tmp.get();
            uint8_t* tmpU = tmpY + i420YSize;
            uint8_t* tmpV = tmpU + i420UVSize;

            ret = scaleI420(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                            srcW, srcH,
                            tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
                            dstW, dstH);

            if (ret == 0) {
                ret = libyuv::I420ToABGR(tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
                                         dst, dstW * 4, dstW, dstH);
            }
        }
        break;
    }

    // ── RGB_565 ───────────────────────────────────────────────────────────────
    case FMT_RGB_565: {
        if (!needsScale) {
            ret = libyuv::I420ToRGB565(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                                       dst, dstW * 2, dstW, dstH);
        } else {
            size_t i420YSize, i420UVSize;
            getI420Sizes(dstW, dstH, i420YSize, i420UVSize);
            size_t i420Total = i420YSize + 2 * i420UVSize;

            ScopedMalloc tmp(static_cast<uint8_t*>(std::malloc(i420Total)));
            if (!tmp) { LOGE("malloc failed"); return ERR_OOM; }

            uint8_t* tmpY = tmp.get();
            uint8_t* tmpU = tmpY + i420YSize;
            uint8_t* tmpV = tmpU + i420UVSize;

            ret = scaleI420(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                            srcW, srcH,
                            tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
                            dstW, dstH);

            if (ret == 0) {
                ret = libyuv::I420ToRGB565(tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
                                           dst, dstW * 2, dstW, dstH);
            }
        }
        break;
    }

    } // end switch(dstFmt)

    return ret;
}
