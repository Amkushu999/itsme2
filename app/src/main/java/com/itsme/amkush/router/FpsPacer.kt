package com.itsme.amkush.router

/**
 * FpsPacer — gates a frame-push loop to a target frame rate.
 *
 * Measures elapsed wall time since the last pace() call and sleeps
 * to fill the remaining frame budget.  Not thread-safe; each
 * SurfaceFeedThread owns its own instance.
 */
class FpsPacer(targetFps: Int) {

    private val frameNs: Long =
        if (targetFps > 0) (1_000_000_000L / targetFps) else 33_333_333L  // default 30 fps

    private var lastNs: Long = 0L

    /**
     * Call at the start of each frame processing cycle.
     * Sleeps until the correct time has elapsed since the last call.
     * Returns immediately on the first call (no previous reference time).
     */
    fun pace() {
        val now = System.nanoTime()
        if (lastNs > 0L) {
            val elapsed   = now - lastNs
            val remaining = frameNs - elapsed
            if (remaining > 1_000_000L) {  // only sleep if > 1 ms remains
                try {
                    Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        lastNs = System.nanoTime()
    }

    /** Reset timing state — call after a hot-swap or pause. */
    fun reset() { lastNs = 0L }
}
