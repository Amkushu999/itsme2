// ISurfaceInjector.aidl
// Binder interface — exposed by InjectionService in the module process.
// Called from Xposed hooks running inside the hooked target-app process.
//
// Surfaces are Parcelable and cross process boundaries transparently;
// they wrap a BufferQueue producer reference any process can write to.
package com.itsme.amkush.ipc;

import android.view.Surface;

interface ISurfaceInjector {

    /**
     * Register a set of camera surfaces with their per-surface requirements.
     * InjectionService attaches an ImageWriter to each surface and begins
     * pushing FFmpeg-decoded frames into them.
     *
     * @param surfaces  Surface objects from createCaptureSession / setPreviewDisplay
     * @param widths    Per-surface output width  (index-matched to surfaces)
     * @param heights   Per-surface output height
     * @param formats   Per-surface ImageFormat constant (NV21, YUV_420_888, etc.)
     * @param fps       Per-surface target frame rate (used to pace ImageWriter)
     * @param sessionId Unique tag for lifecycle management
     */
    void registerSurfaces(
        in List<Surface> surfaces,
        in int[]         widths,
        in int[]         heights,
        in int[]         formats,
        in int[]         fps,
        String           sessionId
    );

    /** Remove all surfaces for a session and stop the associated ImageWriters. */
    void unregisterSession(String sessionId);

    /**
     * Start (or restart) the FFmpeg decoder with the given URL.
     * InjectionService opens an AVFormatContext on the URL and begins
     * calling SurfaceRouter.onFrameAvailable() for each decoded I420 frame.
     *
     * @param url  RTSP / HLS / HTTP(S) / local file URL
     */
    void startDecoder(String url);

    /**
     * Hot-swap the stream source without touching registered surfaces.
     * The decode thread restarts with the new URL within ≈200 ms.
     * Pass an empty string to pause without unregistering surfaces.
     *
     * @param url  New stream URL, or "" to pause.
     */
    void hotSwap(String url);

    /** Hard stop — tears down all sessions and closes the FFmpeg decoder. */
    void stopAll();
}
