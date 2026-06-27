#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <thread>
#include <string>
#include <mutex>
#include <chrono>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libavutil/frame.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "FFmpegDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ── JNI global state (set in JNI_OnLoad) ─────────────────────────────────────

static JavaVM*   g_jvm              = nullptr;
static jmethodID g_onFrameAvailable = nullptr;
static jmethodID g_onError          = nullptr;
static jmethodID g_onEof            = nullptr;

// ── Decoder context ───────────────────────────────────────────────────────────

struct DecoderCtx {
    std::string          url;
    std::atomic<bool>    running{true};
    std::atomic<bool>    hotSwapping{false};
    std::string          hotSwapUrl;
    std::mutex           swapMu;
    jobject              callback = nullptr;  // global JNI ref

    AVFormatContext*     fmtCtx    = nullptr;
    AVCodecContext*      codecCtx  = nullptr;
    SwsContext*          swsCtx    = nullptr;
    int                  videoIdx  = -1;
    AVFrame*             frame     = nullptr;
    AVFrame*             frameI420 = nullptr;
    AVPacket*            packet    = nullptr;

    std::thread          thread;
};

// ── Thread attach/detach helpers ──────────────────────────────────────────────

static JNIEnv* attachCurrentThread(bool& didAttach) {
    didAttach = false;
    JNIEnv* env = nullptr;
    jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            didAttach = true;
        } else {
            env = nullptr;
        }
    }
    return env;
}

static void detachCurrentThread() {
    g_jvm->DetachCurrentThread();
}

// ── Stream open / close ───────────────────────────────────────────────────────

static bool openStream(DecoderCtx* ctx) {
    ctx->fmtCtx = avformat_alloc_context();
    if (!ctx->fmtCtx) {
        LOGE("avformat_alloc_context failed");
        return false;
    }

    // Build protocol-specific demuxer options.
    //
    // avformat_open_input() treats the opts dictionary as format private options
    // and REJECTS any key it doesn't recognise for the detected demuxer.
    // Unconditionally passing rtsp_transport / stimeout to an HLS or RTMP URL
    // causes "Option rtsp_transport not found" errors and can prevent the stream
    // from opening.  Apply each option only to the protocols that support it.
    //
    // Scheme detection operates on the raw URL string; all well-formed URLs
    // (guaranteed by autoPrefixScheme() on the Kotlin side) contain "://".
    //
    //   rtsp_transport   — RTSP demuxer only
    //   stimeout         — RTSP socket timeout (µs); RTSP demuxer only
    //   timeout          — HTTP / HLS / DASH / RTMP / generic TCP timeout (µs)
    //   reconnect*       — HTTP-family demuxers only; ignored / rejected by others
    //   analyzeduration  — safe for all: hint to limit stream-info probe time

    auto startsWith = [](const std::string& s, const char* prefix) {
        return s.rfind(prefix, 0) == 0;
    };

    const bool isRtsp  = startsWith(ctx->url, "rtsp://")  || startsWith(ctx->url, "rtsps://");
    const bool isHttp  = startsWith(ctx->url, "http://")  || startsWith(ctx->url, "https://");
    const bool isRtmp  = startsWith(ctx->url, "rtmp://")  || startsWith(ctx->url, "rtmps://");
    const bool isSrt   = startsWith(ctx->url, "srt://");
    const bool isUdp   = startsWith(ctx->url, "udp://")   || startsWith(ctx->url, "rtp://");

    AVDictionary* opts = nullptr;

    if (isRtsp) {
        av_dict_set(&opts, "rtsp_transport", "tcp",     0);  // force TCP for reliability
        av_dict_set(&opts, "stimeout",       "5000000", 0);  // 5 s socket timeout (µs)
    }

    if (isHttp || isRtmp) {
        av_dict_set(&opts, "timeout",            "5000000", 0);  // 5 s connect timeout
        av_dict_set(&opts, "reconnect",          "1",       0);
        av_dict_set(&opts, "reconnect_streamed", "1",       0);
        av_dict_set(&opts, "reconnect_delay_max","5",       0);
    }

    if (isSrt || isUdp) {
        // SRT/UDP: latency hint in ms; avoids long probe on raw UDP streams
        av_dict_set(&opts, "latency", "200000", 0);  // 200 ms
    }

    // Universal: bound the stream-info probe so slow sources don't stall startup
    av_dict_set(&opts, "analyzeduration", "3000000", 0);  // 3 s max probe
    av_dict_set(&opts, "probesize",       "1000000", 0);  // 1 MB max probe data

    int ret = avformat_open_input(&ctx->fmtCtx, ctx->url.c_str(), nullptr, &opts);
    av_dict_free(&opts);
    if (ret < 0) {
        char err[256];
        av_strerror(ret, err, sizeof(err));
        LOGE("avformat_open_input: %s  url=%s", err, ctx->url.c_str());
        avformat_free_context(ctx->fmtCtx);
        ctx->fmtCtx = nullptr;
        return false;
    }

    if (avformat_find_stream_info(ctx->fmtCtx, nullptr) < 0) {
        LOGE("avformat_find_stream_info failed");
        avformat_close_input(&ctx->fmtCtx);
        return false;
    }

    ctx->videoIdx = av_find_best_stream(
        ctx->fmtCtx, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (ctx->videoIdx < 0) {
        LOGE("no video stream found");
        avformat_close_input(&ctx->fmtCtx);
        return false;
    }

    AVStream*       stream = ctx->fmtCtx->streams[ctx->videoIdx];
    const AVCodec*  codec  = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) {
        LOGE("decoder not found for codec_id=%d", stream->codecpar->codec_id);
        avformat_close_input(&ctx->fmtCtx);
        return false;
    }

    ctx->codecCtx = avcodec_alloc_context3(codec);
    if (!ctx->codecCtx) {
        LOGE("avcodec_alloc_context3 failed");
        avformat_close_input(&ctx->fmtCtx);
        return false;
    }

    if (avcodec_parameters_to_context(ctx->codecCtx, stream->codecpar) < 0) {
        LOGE("avcodec_parameters_to_context failed");
        avcodec_free_context(&ctx->codecCtx);
        avformat_close_input(&ctx->fmtCtx);
        return false;
    }
    ctx->codecCtx->thread_count = 2;
    ctx->codecCtx->thread_type  = FF_THREAD_SLICE;

    if (avcodec_open2(ctx->codecCtx, codec, nullptr) < 0) {
        LOGE("avcodec_open2 failed");
        avcodec_free_context(&ctx->codecCtx);
        avformat_close_input(&ctx->fmtCtx);
        return false;
    }

    ctx->frame     = av_frame_alloc();
    ctx->frameI420 = av_frame_alloc();
    ctx->packet    = av_packet_alloc();
    if (!ctx->frame || !ctx->frameI420 || !ctx->packet) {
        LOGE("frame/packet alloc failed");
        return false;
    }

    LOGI("stream opened: %dx%d codec=%s  url=%s",
         ctx->codecCtx->width, ctx->codecCtx->height, codec->name, ctx->url.c_str());
    return true;
}

static void closeStream(DecoderCtx* ctx) {
    if (ctx->packet)    { av_packet_free(&ctx->packet);    }
    if (ctx->frame)     { av_frame_free(&ctx->frame);      }
    if (ctx->frameI420) { av_frame_free(&ctx->frameI420);  }
    if (ctx->swsCtx)    { sws_freeContext(ctx->swsCtx); ctx->swsCtx = nullptr; }
    if (ctx->codecCtx)  { avcodec_free_context(&ctx->codecCtx); }
    if (ctx->fmtCtx)    { avformat_close_input(&ctx->fmtCtx);   }
    ctx->videoIdx = -1;
}

// ── Frame delivery to Kotlin ──────────────────────────────────────────────────

static void fireOnFrame(JNIEnv* env, jobject cb, AVFrame* f) {
    const int w      = f->width;
    const int h      = f->height;
    const int ySize  = w * h;
    const int uvSize = ySize / 4;

    // DirectByteBuffers view into AVFrame plane memory.
    // The Kotlin side MUST copy before returning from onFrameAvailable().
    jobject yBuf = env->NewDirectByteBuffer(f->data[0], ySize);
    jobject uBuf = env->NewDirectByteBuffer(f->data[1], uvSize);
    jobject vBuf = env->NewDirectByteBuffer(f->data[2], uvSize);
    if (!yBuf || !uBuf || !vBuf) { LOGE("NewDirectByteBuffer failed"); return; }

    const jlong ptsUs = (f->pts == AV_NOPTS_VALUE) ? 0LL : f->pts;

    env->CallVoidMethod(cb, g_onFrameAvailable, yBuf, uBuf, vBuf,
                        (jint)w, (jint)h, ptsUs);

    env->DeleteLocalRef(yBuf);
    env->DeleteLocalRef(uBuf);
    env->DeleteLocalRef(vBuf);

    if (env->ExceptionCheck()) {
        LOGE("onFrameAvailable threw exception");
        env->ExceptionClear();
    }
}

// ── Convert decoded frame to I420 if needed ───────────────────────────────────

static AVFrame* ensureI420(DecoderCtx* ctx, AVFrame* src) {
    if (src->format == AV_PIX_FMT_YUV420P) return src;

    const int w = src->width;
    const int h = src->height;

    // Rebuild SwsContext if dimensions changed
    if (!ctx->swsCtx
        || ctx->frameI420->width  != w
        || ctx->frameI420->height != h) {

        if (ctx->swsCtx) { sws_freeContext(ctx->swsCtx); ctx->swsCtx = nullptr; }

        ctx->swsCtx = sws_getContext(
            w, h, static_cast<AVPixelFormat>(src->format),
            w, h, AV_PIX_FMT_YUV420P,
            SWS_BILINEAR, nullptr, nullptr, nullptr);
        if (!ctx->swsCtx) { LOGE("sws_getContext failed"); return nullptr; }

        av_frame_unref(ctx->frameI420);
        ctx->frameI420->format = AV_PIX_FMT_YUV420P;
        ctx->frameI420->width  = w;
        ctx->frameI420->height = h;
        if (av_frame_get_buffer(ctx->frameI420, 32) < 0) {
            LOGE("av_frame_get_buffer failed"); return nullptr;
        }
    }

    sws_scale(ctx->swsCtx,
              src->data, src->linesize, 0, h,
              ctx->frameI420->data, ctx->frameI420->linesize);
    ctx->frameI420->pts = src->pts;
    return ctx->frameI420;
}

// ── Main decode loop (runs on ctx->thread) ────────────────────────────────────

static void decodeLoop(DecoderCtx* ctx) {
    bool    didAttach = false;
    JNIEnv* env       = attachCurrentThread(didAttach);
    if (!env) { LOGE("failed to attach decode thread to JVM"); return; }

    while (ctx->running.load()) {

        // ── Hot-swap ──────────────────────────────────────────────────────────
        if (ctx->hotSwapping.load()) {
            std::string newUrl;
            {
                std::lock_guard<std::mutex> lk(ctx->swapMu);
                newUrl = ctx->hotSwapUrl;
                ctx->hotSwapping.store(false);
            }
            LOGI("hot-swap → %s", newUrl.c_str());
            closeStream(ctx);
            ctx->url = newUrl;
            if (!openStream(ctx)) {
                LOGE("hot-swap openStream failed — retry in 2 s");
                std::this_thread::sleep_for(std::chrono::seconds(2));
                continue;
            }
        }

        // ── Read packet ───────────────────────────────────────────────────────
        int ret = av_read_frame(ctx->fmtCtx, ctx->packet);

        if (ret == AVERROR_EOF || ret == AVERROR(EAGAIN)) {
            // EOF: loop file sources; signal Kotlin for streaming sources
            env->CallVoidMethod(ctx->callback, g_onEof);
            if (env->ExceptionCheck()) env->ExceptionClear();

            // Seek to beginning so file sources loop seamlessly
            avformat_seek_file(ctx->fmtCtx, -1, INT64_MIN, 0, INT64_MAX, 0);
            avcodec_flush_buffers(ctx->codecCtx);
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        if (ret < 0) {
            char errBuf[256];
            av_strerror(ret, errBuf, sizeof(errBuf));
            LOGE("av_read_frame: %s", errBuf);
            jstring msg = env->NewStringUTF(errBuf);
            env->CallVoidMethod(ctx->callback, g_onError, (jint)ret, msg);
            env->DeleteLocalRef(msg);
            if (env->ExceptionCheck()) env->ExceptionClear();

            // Reconnect
            closeStream(ctx);
            std::this_thread::sleep_for(std::chrono::seconds(2));
            if (!openStream(ctx)) {
                std::this_thread::sleep_for(std::chrono::seconds(3));
            }
            continue;
        }

        // ── Decode video packets ──────────────────────────────────────────────
        if (ctx->packet->stream_index == ctx->videoIdx) {
            ret = avcodec_send_packet(ctx->codecCtx, ctx->packet);
            if (ret < 0 && ret != AVERROR(EAGAIN)) {
                av_packet_unref(ctx->packet);
                continue;
            }

            while (ctx->running.load()) {
                ret = avcodec_receive_frame(ctx->codecCtx, ctx->frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                if (ret < 0) break;

                AVFrame* outFrame = ensureI420(ctx, ctx->frame);
                if (outFrame) {
                    fireOnFrame(env, ctx->callback, outFrame);
                }
            }
        }

        av_packet_unref(ctx->packet);
    }

    if (didAttach) detachCurrentThread();
    LOGI("decode loop exited");
}

// ── JNI_OnLoad — cache method IDs ────────────────────────────────────────────

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return -1;

    // Inner interface: com.itsme.amkush.ffmpeg.FFmpegDecoder$FrameCallback
    jclass cls = env->FindClass("com/itsme/amkush/ffmpeg/FFmpegDecoder$FrameCallback");
    if (!cls) { LOGE("FrameCallback class not found"); return -1; }

    g_onFrameAvailable = env->GetMethodID(cls, "onFrameAvailable",
        "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIJ)V");
    g_onError = env->GetMethodID(cls, "onError", "(ILjava/lang/String;)V");
    g_onEof   = env->GetMethodID(cls, "onEof",   "()V");

    if (!g_onFrameAvailable || !g_onError || !g_onEof) {
        LOGE("FrameCallback method ID lookup failed");
        return -1;
    }

    LOGI("JNI_OnLoad OK");
    return JNI_VERSION_1_6;
}

// ── Exported JNI functions ────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_open(
    JNIEnv* env, jclass, jstring urlJ, jobject cb)
{
    const char* urlC = env->GetStringUTFChars(urlJ, nullptr);
    std::string url(urlC);
    env->ReleaseStringUTFChars(urlJ, urlC);

    auto* ctx       = new DecoderCtx();
    ctx->url        = url;
    ctx->callback   = env->NewGlobalRef(cb);

    if (!openStream(ctx)) {
        LOGE("openStream failed: %s", url.c_str());
        env->DeleteGlobalRef(ctx->callback);
        delete ctx;
        return 0L;
    }

    ctx->thread = std::thread(decodeLoop, ctx);
    LOGI("decoder opened handle=%p  url=%s", ctx, url.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_close(
    JNIEnv* env, jclass, jlong handle)
{
    auto* ctx = reinterpret_cast<DecoderCtx*>(handle);
    if (!ctx) return;

    ctx->running.store(false);
    if (ctx->thread.joinable()) ctx->thread.join();

    closeStream(ctx);
    if (ctx->callback) env->DeleteGlobalRef(ctx->callback);
    delete ctx;
    LOGI("decoder closed");
}

JNIEXPORT jboolean JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_hotSwap(
    JNIEnv* env, jclass, jlong handle, jstring urlJ)
{
    auto* ctx = reinterpret_cast<DecoderCtx*>(handle);
    if (!ctx) return JNI_FALSE;

    const char* urlC = env->GetStringUTFChars(urlJ, nullptr);
    {
        std::lock_guard<std::mutex> lk(ctx->swapMu);
        ctx->hotSwapUrl = urlC;
        ctx->hotSwapping.store(true);
    }
    env->ReleaseStringUTFChars(urlJ, urlC);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_getWidth(
    JNIEnv*, jclass, jlong handle)
{
    auto* ctx = reinterpret_cast<DecoderCtx*>(handle);
    return (ctx && ctx->codecCtx) ? ctx->codecCtx->width : 0;
}

JNIEXPORT jint JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_getHeight(
    JNIEnv*, jclass, jlong handle)
{
    auto* ctx = reinterpret_cast<DecoderCtx*>(handle);
    return (ctx && ctx->codecCtx) ? ctx->codecCtx->height : 0;
}

} // extern "C"
