#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include "libyuv.h"

#define LOG_TAG "LibYuvWrapper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Android ImageFormat constants (mirrors android.graphics.ImageFormat)
static const int FMT_RGBA_8888  = 0x01;   // ImageFormat.RGBA_8888
static const int FMT_RGB_565    = 0x04;   // ImageFormat.RGB_565
static const int FMT_NV16       = 0x10;   // ImageFormat.NV16
static const int FMT_NV21       = 0x11;   // ImageFormat.NV21
static const int FMT_NV12       = 0x15;   // (no constant in ImageFormat, but common)
static const int FMT_YUV_420_888 = 0x23;  // ImageFormat.YUV_420_888

// ── Helpers ───────────────────────────────────────────────────────────────────

// Scale I420 → I420 using libyuv, writing into pre-allocated dstY/dstU/dstV.
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
        dstY, dstStrY, dstU, dstStrU, dstV, dstStrV, dstW, dstH,
        libyuv::kFilterBilinear);
}

// ── JNI export ────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_itsme_amkush_libyuv_LibYuv_convertInto(
    JNIEnv* env, jclass,
    jobject srcYBuf, jobject srcUBuf, jobject srcVBuf,
    jint srcW, jint srcH,
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
        return -1;
    }

    const int srcStrY  = srcW;
    const int srcStrUV = srcW / 2;
    const int dstStrY  = dstW;
    const int dstStrUV = dstW / 2;

    int ret = 0;

    switch (dstFmt) {

    case FMT_YUV_420_888: {
        // I420 planar: Y | U | V
        uint8_t* dY = dst;
        uint8_t* dU = dst + (size_t)dstW * dstH;
        uint8_t* dV = dst + (size_t)dstW * dstH * 5 / 4;
        ret = scaleI420(
            srcY, srcStrY, srcU, srcStrUV, srcV, srcStrUV, srcW, srcH,
            dY, dstStrY, dU, dstStrUV, dV, dstStrUV, dstW, dstH);
        break;
    }

    case FMT_NV21: {
        // Scale to I420 first, then convert NV21 (Y | VU interleaved)
        const size_t tY = (size_t)dstW * dstH;
        const size_t tUV = tY / 4;
        auto* tmpY = static_cast<uint8_t*>(std::malloc(tY + tUV * 2));
        if (!tmpY) { LOGE("malloc failed"); return -1; }
        uint8_t* tmpU = tmpY + tY;
        uint8_t* tmpV = tmpU + tUV;
        scaleI420(srcY, srcStrY, srcU, srcStrUV, srcV, srcStrUV, srcW, srcH,
                  tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV, dstW, dstH);
        // NV21: Y plane then interleaved VU
        ret = libyuv::I420ToNV21(
            tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
            dst, dstStrY,
            dst + tY, dstStrY,
            dstW, dstH);
        std::free(tmpY);
        break;
    }

    case FMT_NV12: {
        // Scale to I420, then convert NV12 (Y | UV interleaved)
        const size_t tY = (size_t)dstW * dstH;
        const size_t tUV = tY / 4;
        auto* tmpY = static_cast<uint8_t*>(std::malloc(tY + tUV * 2));
        if (!tmpY) { LOGE("malloc failed"); return -1; }
        uint8_t* tmpU = tmpY + tY;
        uint8_t* tmpV = tmpU + tUV;
        scaleI420(srcY, srcStrY, srcU, srcStrUV, srcV, srcStrUV, srcW, srcH,
                  tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV, dstW, dstH);
        ret = libyuv::I420ToNV12(
            tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
            dst, dstStrY,
            dst + tY, dstStrY,
            dstW, dstH);
        std::free(tmpY);
        break;
    }

    case FMT_RGBA_8888: {
        const size_t tY = (size_t)dstW * dstH;
        const size_t tUV = tY / 4;
        auto* tmpY = static_cast<uint8_t*>(std::malloc(tY + tUV * 2));
        if (!tmpY) { LOGE("malloc failed"); return -1; }
        uint8_t* tmpU = tmpY + tY;
        uint8_t* tmpV = tmpU + tUV;
        scaleI420(srcY, srcStrY, srcU, srcStrUV, srcV, srcStrUV, srcW, srcH,
                  tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV, dstW, dstH);
        // Android RGBA_8888 memory order = R,G,B,A = ABGR in libyuv naming
        ret = libyuv::I420ToABGR(
            tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
            dst, dstW * 4,
            dstW, dstH);
        std::free(tmpY);
        break;
    }

    case FMT_RGB_565: {
        const size_t tY = (size_t)dstW * dstH;
        const size_t tUV = tY / 4;
        auto* tmpY = static_cast<uint8_t*>(std::malloc(tY + tUV * 2));
        if (!tmpY) { LOGE("malloc failed"); return -1; }
        uint8_t* tmpU = tmpY + tY;
        uint8_t* tmpV = tmpU + tUV;
        scaleI420(srcY, srcStrY, srcU, srcStrUV, srcV, srcStrUV, srcW, srcH,
                  tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV, dstW, dstH);
        ret = libyuv::I420ToRGB565(
            tmpY, dstStrY, tmpU, dstStrUV, tmpV, dstStrUV,
            dst, dstW * 2,
            dstW, dstH);
        std::free(tmpY);
        break;
    }

    default:
        LOGE("convertInto: unsupported format 0x%x", dstFmt);
        ret = -2;
    }

    return ret;
}
