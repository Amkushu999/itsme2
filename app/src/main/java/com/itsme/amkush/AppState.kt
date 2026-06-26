package com.itsme.amkush

import android.content.Context
import com.itsme.amkush.ipc.ISurfaceInjector

/**
 * Neutral shared-state singleton — ZERO Xposed imports.
 *
 * Safe to reference from both runtime contexts:
 *   • Xposed process  — MainHook writes here after hooking Application.onCreate()
 *   • App own process — InjectionService writes context; decoders write frames
 *
 * [VideoFrame] bundles data + source dimensions into a single volatile reference
 * so hooks always read a consistent snapshot — no separate width/height reads
 * that could race with a concurrent frame write.
 */
object AppState {

    @Volatile var context: Context? = null
    @Volatile var isPlaying: Boolean = false
    @Volatile var targetPackage: String? = null
    @Volatile var isHookingActive: Boolean = false
    const val TAG = "FaceGate"

    // ── Binder reference to InjectionService ────────────────────────────────
    // Set by InjectionServiceClient when the Binder connection is established.
    // Read by Camera1Hooks / Camera2Hooks to deliver surfaces.
    @Volatile var injectorService: ISurfaceInjector? = null

    // ── Frame buffer ─────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of one decoded video frame.
     * Replacing [currentFrame] with a new instance is a single volatile write,
     * so readers always see either the old or the new frame — never a mix.
     */
    data class VideoFrame(
        val data: ByteArray,
        val width: Int,
        val height: Int
    ) {
        val isEmpty: Boolean get() = data.isEmpty()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VideoFrame) return false
            return width == other.width && height == other.height && data.contentEquals(other.data)
        }
        override fun hashCode(): Int = 31 * (31 * data.contentHashCode() + width) + height
    }

    @Volatile var currentFrame: VideoFrame = VideoFrame(ByteArray(0), 0, 0)

    /** Write a new frame atomically — single volatile reference swap. */
    fun putFrame(data: ByteArray, width: Int, height: Int) {
        currentFrame = VideoFrame(data, width, height)
    }

    /**
     * Backward-compatible accessor — reads the current frame's data byte array.
     * Prefer [currentFrame] in new code so width/height come from the same snapshot.
     */
    val dataBuffer: ByteArray get() = currentFrame.data
}
