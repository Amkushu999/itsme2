#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <thread>
#include <string>
#include <mutex>
#include <chrono>
#include <cstring>
#include <cctype>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libavutil/frame.h>
#include <libavutil/buffer.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "FFmpegDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ── JNI global state ─────────────────────────────────────────────────────────

static JavaVM*   g_jvm              = nullptr;
static jmethodID g_onFrameAvailable = nullptr;
static jmethodID g_onError          = nullptr;
static jmethodID g_onEof            = nullptr;
static bool      g_methodsCached    = false;

// ── Decoder context ───────────────────────────────────────────────────────────

struct DecoderCtx {
    std::string          url;
    std::atomic<bool>    running{true};
    std::atomic<bool>    hotSwapping{false};
    std::string          hotSwapUrl;
    std::mutex           swapMu;
    jobject              callback = nullptr;

    std::atomic<int>     width{0};
    std::atomic<int>     height{0};

    AVRational           timeBase{0, 1};
    int                  srcFmt = -1;

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

// ── Protocol-aware demuxer options ───────────────────────────────────────────

static void buildDemuxerOpts(AVDictionary** opts, const std::string& url) {
    std::string scheme;
    const auto sep = url.find("://");
    const std::string raw = (sep != std::string::npos) ? url.substr(0, sep) : url;
    scheme.resize(raw.size());
    for (size_t i = 0; i < raw.size(); ++i)
        scheme[i] = static_cast<char>(::tolower(static_cast<unsigned char>(raw[i])));

    if (scheme == "rtsp" || scheme == "rtsps") {
        av_dict_set(opts, "rtsp_transport", "tcp",     0);
        av_dict_set(opts, "stimeout",       "5000000", 0);
    } else if (scheme == "http" || scheme == "https") {
        av_dict_set(opts, "timeout",            "5000000", 0);
        av_dict_set(opts, "reconnect",          "1",       0);
        av_dict_set(opts, "reconnect_streamed", "1",       0);
        av_dict_set(opts, "reconnect_delay_max","5",       0);
    } else if (scheme == "rtmp" || scheme == "rtmps") {
        av_dict_set(opts, "timeout", "5000000", 0);
    } else if (scheme == "srt") {
        av_dict_set(opts, "latency", "200000", 0);
    }

    av_dict_set(opts, "analyzeduration", "3000000", 0);
    av_dict_set(opts, "probesize",       "1000000", 0);
}

// ── Stream open / close ───────────────────────────────────────────────────────

static bool openStream(DecoderCtx* ctx) {
    ctx->fmtCtx = avformat_alloc_context();
    if (!ctx->fmtCtx) {
        LOGE("avformat_alloc_context failed");
        return false;
    }

    AVDictionary* opts = nullptr;
    buildDemuxerOpts(&opts, ctx->url);

    int ret = avformat_open_input(&ctx->fmtCtx, ctx->url.c_str(), nullptr, &opts);
    av_dict_free(&opts);

    if (ret < 0) {
        char err[256];
        av_strerror(ret, err, sizeof(err));
        LOGE("avformat_open_input: %s  url=%s", err, ctx->url.c_str());
        ctx->fmtCtx = nullptr;
        return false;
    }

    if (avformat_find_stream_info(ctx->fmtCtx, nullptr) < 0) {
        LOGE("avformat_find_stream_info failed");
        avformat_close_input(&ctx->fmtCtx);
        return false;
    }

    ctx->videoIdx = av_find_best_stream(ctx->fmtCtx, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (ctx->videoIdx < 0) {
        LOGE("no video stream found");
        avformat_close_input(&ctx->fmtCtx);
        return false;
    }

    AVStream*      stream = ctx->fmtCtx->streams[ctx->videoIdx];
    const AVCodec* codec  = avcodec_find_decoder(stream->codecpar->codec_id);
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
        if (ctx->frame)     av_frame_free(&ctx->frame);
        if (ctx->frameI420) av_frame_free(&ctx->frameI420);
        if (ctx->packet)    av_packet_free(&ctx->packet);
        avcodec_free_context(&ctx->codecCtx);
        avformat_close_input(&ctx->fmtCtx);
        return false;
    }

    ctx->width.store(ctx->codecCtx->width);
    ctx->height.store(ctx->codecCtx->height);
    ctx->timeBase = stream->time_base;
    ctx->srcFmt   = -1;

    LOGI("stream opened: %dx%d  codec=%s  url=%s",
         ctx->codecCtx->width, ctx->codecCtx->height,
         codec->name, ctx->url.c_str());
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
    ctx->width.store(0);
    ctx->height.store(0);
    ctx->srcFmt = -1;
}

// ── Frame delivery to Kotlin ──────────────────────────────────────────────────

static void fireOnFrame(JNIEnv* env, jobject cb, AVFrame* f, jlong ptsUs) {
    const int w      = f->width;
    const int h      = f->height;
    const int ySize  = w * h;
    const int uvW    = (w + 1) / 2;
    const int uvH    = (h + 1) / 2;
    const int uvSize = uvW * uvH;

    jobject yBuf = env->NewDirectByteBuffer(f->data[0], ySize);
    jobject uBuf = env->NewDirectByteBuffer(f->data[1], uvSize);
    jobject vBuf = env->NewDirectByteBuffer(f->data[2], uvSize);
    if (!yBuf || !uBuf || !vBuf) {
        LOGE("fireOnFrame: NewDirectByteBuffer failed");
        if (yBuf) env->DeleteLocalRef(yBuf);
        if (uBuf) env->DeleteLocalRef(uBuf);
        if (vBuf) env->DeleteLocalRef(vBuf);
        return;
    }

    env->CallVoidMethod(cb, g_onFrameAvailable, yBuf, uBuf, vBuf,
                        static_cast<jint>(w), static_cast<jint>(h), ptsUs);

    env->DeleteLocalRef(yBuf);
    env->DeleteLocalRef(uBuf);
    env->DeleteLocalRef(vBuf);

    if (env->ExceptionCheck()) {
        LOGE("fireOnFrame: onFrameAvailable threw a Java exception");
        env->ExceptionClear();
    }
}

// ── Convert decoded frame to tightly-packed I420 ─────────────────────────────

static AVFrame* ensureI420(DecoderCtx* ctx, AVFrame* src) {
    const int w      = src->width;
    const int h      = src->height;
    const int srcFmt = src->format;

    const bool needsRebuild = !ctx->swsCtx                   ||
                              ctx->frameI420->width  != w     ||
                              ctx->frameI420->height != h     ||
                              ctx->srcFmt            != srcFmt;

    if (needsRebuild) {
        if (ctx->swsCtx) {
            sws_freeContext(ctx->swsCtx);
            ctx->swsCtx = nullptr;
        }

        ctx->swsCtx = sws_getContext(
            w, h, static_cast<AVPixelFormat>(srcFmt),
            w, h, AV_PIX_FMT_YUV420P,
            SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
        if (!ctx->swsCtx) {
            LOGE("ensureI420: sws_getContext failed");
            return nullptr;
        }

        av_frame_unref(ctx->frameI420);

        const int uvW     = (w + 1) / 2;
        const int uvH     = (h + 1) / 2;
        const int ySize   = w * h;
        const int uvSize  = uvW * uvH;
        const int total   = ySize + 2 * uvSize;

        AVBufferRef* buf = av_buffer_alloc(total);
        if (!buf) {
            LOGE("ensureI420: av_buffer_alloc(%d) failed", total);
            return nullptr;
        }

        ctx->frameI420->buf[0]  = buf;
        ctx->frameI420->data[0] = buf->data;
        ctx->frameI420->data[1] = buf->data + ySize;
        ctx->frameI420->data[2] = buf->data + ySize + uvSize;

        ctx->frameI420->linesize[0] = w;
        ctx->frameI420->linesize[1] = uvW;
        ctx->frameI420->linesize[2] = uvW;

        ctx->frameI420->format = AV_PIX_FMT_YUV420P;
        ctx->frameI420->width  = w;
        ctx->frameI420->height = h;
        ctx->srcFmt            = srcFmt;

        ctx->width.store(w);
        ctx->height.store(h);
    }

    sws_scale(ctx->swsCtx,
              src->data,         src->linesize,  0, h,
              ctx->frameI420->data, ctx->frameI420->linesize);
    ctx->frameI420->pts = src->pts;
    return ctx->frameI420;
}

// ── Main decode loop ──────────────────────────────────────────────────────────

static void decodeLoop(DecoderCtx* ctx) {
    bool    didAttach = false;
    JNIEnv* env       = attachCurrentThread(didAttach);
    if (!env) {
        LOGE("decodeLoop: failed to attach to JVM");
        return;
    }

    while (ctx->running.load()) {

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

            while (ctx->running.load() && !openStream(ctx)) {
                LOGE("hot-swap openStream failed — retry in 2 s");
                std::this_thread::sleep_for(std::chrono::seconds(2));
            }
            if (!ctx->running.load()) break;
        }

        int ret = av_read_frame(ctx->fmtCtx, ctx->packet);

        if (ret == AVERROR_EOF) {
            env->CallVoidMethod(ctx->callback, g_onEof);
            if (env->ExceptionCheck()) env->ExceptionClear();

            const bool isLive =
                (ctx->fmtCtx->duration == AV_NOPTS_VALUE) ||
                (ctx->url.substr(0, 4) == "rtsp") ||
                (ctx->url.substr(0, 4) == "rtmp") ||
                (ctx->url.substr(0, 3) == "udp") ||
                (ctx->url.substr(0, 3) == "rtp") ||
                (ctx->url.substr(0, 3) == "srt");

            if (!isLive) {
                avformat_seek_file(ctx->fmtCtx, -1, INT64_MIN, 0, INT64_MAX, 0);
                avcodec_flush_buffers(ctx->codecCtx);
            } else {
                LOGI("live stream EOF — reconnecting in 2 s");
                std::this_thread::sleep_for(std::chrono::seconds(2));
                closeStream(ctx);
                while (ctx->running.load() && !openStream(ctx)) {
                    LOGE("reconnect failed — retry in 3 s");
                    std::this_thread::sleep_for(std::chrono::seconds(3));
                }
                if (!ctx->running.load()) break;
            }
            continue;
        }

        if (ret == AVERROR(EAGAIN)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        if (ret < 0) {
            char errBuf[256];
            av_strerror(ret, errBuf, sizeof(errBuf));
            LOGE("av_read_frame: %s", errBuf);
            jstring msg = env->NewStringUTF(errBuf);
            env->CallVoidMethod(ctx->callback, g_onError, static_cast<jint>(ret), msg);
            if (msg) env->DeleteLocalRef(msg);
            if (env->ExceptionCheck()) env->ExceptionClear();
            
            closeStream(ctx);
            while (ctx->running.load() && !openStream(ctx)) {
                LOGE("Reconnect failed — retry in 3 s");
                std::this_thread::sleep_for(std::chrono::seconds(3));
            }
            if (!ctx->running.load()) break;
            continue;
        }

        if (ctx->packet->stream_index != ctx->videoIdx) {
            av_packet_unref(ctx->packet);
            continue;
        }

        ret = avcodec_send_packet(ctx->codecCtx, ctx->packet);
        av_packet_unref(ctx->packet);

        if (ret == AVERROR(EAGAIN)) {
            // Codec input buffer full — drain first
        } else if (ret < 0) {
            char errBuf[256];
            av_strerror(ret, errBuf, sizeof(errBuf));
            LOGE("avcodec_send_packet: %s", errBuf);
            continue;
        }

        while (ctx->running.load()) {
            ret = avcodec_receive_frame(ctx->codecCtx, ctx->frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) {
                char errBuf[256];
                av_strerror(ret, errBuf, sizeof(errBuf));
                LOGE("avcodec_receive_frame: %s", errBuf);
                break;
            }

            AVFrame* i420 = ensureI420(ctx, ctx->frame);
            if (i420) {
                jlong ptsUs = 0;
                if (ctx->frame->pts != AV_NOPTS_VALUE) {
                    ptsUs = av_rescale_q(ctx->frame->pts, ctx->timeBase, {1, 1000000});
                }
                fireOnFrame(env, ctx->callback, i420, ptsUs);
            }

            av_frame_unref(ctx->frame);
        }
    }

    if (didAttach) detachCurrentThread();
}

// ── JNI exports ──────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_open(
    JNIEnv* env, jclass, jstring urlStr, jobject cb)
{
    if (!g_methodsCached) {
        jclass cbClass = env->GetObjectClass(cb);
        
        // FIXED: Corrected JNI signature (IIJ, not IIIJ)
        g_onFrameAvailable = env->GetMethodID(
            cbClass, "onFrameAvailable",
            "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIJ)V");
        g_onError = env->GetMethodID(cbClass, "onError", "(ILjava/lang/String;)V");
        g_onEof   = env->GetMethodID(cbClass, "onEof",   "()V");
        
        env->DeleteLocalRef(cbClass);
        g_methodsCached = (g_onFrameAvailable && g_onError && g_onEof);
        if (!g_methodsCached) {
            LOGE("open: failed to cache JNI method IDs");
            return 0;
        }
    }

    const char* url = env->GetStringUTFChars(urlStr, nullptr);
    if (!url) return 0;

    auto* ctx = new DecoderCtx();
    ctx->url      = url;
    ctx->callback = env->NewGlobalRef(cb);
    env->ReleaseStringUTFChars(urlStr, url);

    if (!openStream(ctx)) {
        LOGE("open: openStream failed for url=%s", ctx->url.c_str());
        env->DeleteGlobalRef(ctx->callback);
        delete ctx;
        return 0;
    }

    ctx->thread = std::thread(decodeLoop, ctx);
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_close(
    JNIEnv* env, jclass, jlong handle)
{
    auto* ctx = reinterpret_cast<DecoderCtx*>(handle);
    if (!ctx) return;

    ctx->running.store(false);
    if (ctx->thread.joinable()) ctx->thread.join();

    closeStream(ctx);

    if (ctx->callback) {
        env->DeleteGlobalRef(ctx->callback);
        ctx->callback = nullptr;
    }
    delete ctx;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_hotSwap(
    JNIEnv* env, jclass, jlong handle, jstring urlStr)
{
    auto* ctx = reinterpret_cast<DecoderCtx*>(handle);
    if (!ctx || !ctx->running.load()) return JNI_FALSE;

    const char* url = env->GetStringUTFChars(urlStr, nullptr);
    if (!url) return JNI_FALSE;

    {
        std::lock_guard<std::mutex> lk(ctx->swapMu);
        ctx->hotSwapUrl = url;
        ctx->hotSwapping.store(true);
    }
    env->ReleaseStringUTFChars(urlStr, url);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_getWidth(
    JNIEnv*, jclass, jlong handle)
{
    auto* ctx = reinterpret_cast<DecoderCtx*>(handle);
    return ctx ? ctx->width.load() : 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_itsme_amkush_ffmpeg_FFmpegDecoder_getHeight(
    JNIEnv*, jclass, jlong handle)
{
    auto* ctx = reinterpret_cast<DecoderCtx*>(handle);
    return ctx ? ctx->height.load() : 0;
}
