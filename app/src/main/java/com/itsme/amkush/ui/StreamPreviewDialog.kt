package com.itsme.amkush.ui

import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

private val Violet  = Color(0xFF6C63FF)
private val BgDark  = Color(0xFF0D0D18)
private val Surface = Color(0x12FFFFFF)
private val Border  = Color(0x1AFFFFFF)
private val TextMid = Color(0x88FFFFFF)

// Output resolution for the preview (balance quality vs. memory)
private const val PREVIEW_W = 640
private const val PREVIEW_H = 360

/**
 * Full-screen stream preview dialog powered by FFmpeg Kit.
 *
 * Pipeline:
 *   FFmpegKit → rawvideo/rgba pipe → FileInputStream reader thread
 *   → Bitmap.copyPixelsFromBuffer → SurfaceHolder.lockCanvas/post
 *
 * FFmpeg Kit handles RTSP, HLS, RTMP, SRT, UDP, HTTP(S) and virtually
 * every other protocol — far wider support than Android's MediaPlayer.
 *
 * Note: FFmpeg Kit runs here in the MODULE's own process where its native
 * libraries are available. It is intentionally NOT used inside FfmpegStreamer
 * which runs inside the hooked target-app process via Xposed.
 */
@Composable
fun StreamPreviewDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current

    var buffering by remember { mutableStateOf(true) }
    var error     by remember { mutableStateOf<String?>(null) }
    var liveTag   by remember { mutableStateOf(false) }

    // Mutable ref so the reader thread can always get the latest holder
    val holderRef = remember { mutableStateOf<SurfaceHolder?>(null) }

    DisposableEffect(url) {
        // Create a named pipe that FFmpeg Kit writes to
        val pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)

        var session: FFmpegSession? = null
        @Volatile var stopped = false

        // ── Reader thread: pull RGBA frames from the pipe → render to SurfaceView ──
        val readThread = Thread({
            try {
                val frameBytes = PREVIEW_W * PREVIEW_H * 4   // RGBA = 4 bytes/pixel
                val rawBuf     = ByteArray(frameBytes)
                val pixelBuf   = ByteBuffer.allocate(frameBytes)

                FileInputStream(File(pipePath)).use { fis ->
                    while (!stopped && !Thread.currentThread().isInterrupted) {
                        // Read exactly one complete RGBA frame from the pipe
                        var read = 0
                        while (read < frameBytes && !stopped) {
                            val n = fis.read(rawBuf, read, frameBytes - read)
                            if (n < 0) { stopped = true; break }
                            read += n
                        }
                        if (read < frameBytes || stopped) break

                        // Wrap raw bytes → Bitmap (ARGB_8888 matches FFmpeg rgba layout on Android)
                        pixelBuf.clear()
                        pixelBuf.put(rawBuf)
                        pixelBuf.rewind()

                        val bmp = Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(pixelBuf)

                        // Draw to the SurfaceView
                        val holder = holderRef.value
                        if (holder != null && holder.surface.isValid) {
                            val canvas = holder.lockCanvas()
                            if (canvas != null) {
                                try {
                                    val dst = android.graphics.Rect(0, 0, canvas.width, canvas.height)
                                    canvas.drawBitmap(bmp, null, dst, null)
                                } finally {
                                    holder.unlockCanvasAndPost(canvas)
                                }
                            }
                        }
                        bmp.recycle()

                        // Mark as live on first successful frame
                        if (!liveTag) {
                            buffering = false
                            liveTag   = true
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Throwable) {
                if (!stopped) error = "Stream error: ${e.message}"
            }
        }, "FfmpegPreviewReader").also {
            it.isDaemon = true
            it.start()
        }

        // ── FFmpeg Kit session: decode stream → RGBA rawvideo → pipe ──
        //
        // Protocol-specific input flags are chosen from the URL scheme so that
        // non-RTSP protocols don't receive RTSP-only options (which FFmpeg would
        // reject or silently mishandle in some builds).
        //
        // Common flags:
        //   -fflags +nobuffer    — minimal buffering for live streams
        //   -flags low_delay     — reduce latency
        //   -f rawvideo          — raw uncompressed output (no container overhead)
        //   -pix_fmt rgba        — Android ARGB_8888 compatible byte layout
        //   -vf scale=WxH        — scale to preview resolution
        //   -r 30                — cap output rate so slow devices don't drop frames
        //
        // Protocol-specific flags:
        //   RTSP  → -rtsp_transport tcp      (avoid UDP packet loss)
        //   SRT   → -protocol_whitelist      (SRT needs explicit whitelist)
        //   UDP/RTP → -protocol_whitelist    (raw UDP/RTP needs whitelist)
        //   HLS/HTTP/RTMP/others → no special input flags needed
        val scheme = url.substringBefore("://").lowercase().trimStart('-')
        val inputFlags = when (scheme) {
            "rtsp"       -> "-rtsp_transport tcp "
            "srt"        -> "-protocol_whitelist file,crypto,data,srt,udp "
            "udp", "rtp" -> "-protocol_whitelist file,crypto,data,udp,rtp "
            else         -> ""   // hls, http, https, rtmp, mms, ftp — no special flags
        }
        val cmd = "${inputFlags}-fflags +nobuffer -flags low_delay " +
                  "-i \"$url\" " +
                  "-vf scale=$PREVIEW_W:$PREVIEW_H " +
                  "-f rawvideo -pix_fmt rgba -r 30 " +
                  "\"$pipePath\""

        session = FFmpegKit.executeAsync(cmd, { s ->
            if (!stopped) {
                when {
                    ReturnCode.isSuccess(s.returnCode) -> { /* stream ended cleanly */ }
                    ReturnCode.isCancel(s.returnCode)  -> { /* user dismissed */        }
                    else -> error = "FFmpeg ended (code ${s.returnCode})"
                }
            }
        }, null, null)

        onDispose {
            stopped = true
            session?.cancel()
            readThread.interrupt()
            FFmpegKitConfig.closeFFmpegPipe(pipePath)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(20.dp))
                .background(BgDark)
                .border(1.dp, Border, RoundedCornerShape(20.dp))
        ) {
            Column {
                // ── Title bar ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Stream Preview", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            url.take(40) + if (url.length > 40) "…" else "",
                            color = TextMid, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0x1AFFFFFF))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = TextMid, fontSize = 12.sp)
                    }
                }

                // ── Video surface ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder)  { holderRef.value = h }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { holderRef.value = h }
                                    override fun surfaceDestroyed(h: SurfaceHolder) { holderRef.value = null }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Buffering overlay
                    if (buffering && error == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Violet,
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 2.dp
                            )
                            Text("Connecting via FFmpeg Kit…", color = TextMid, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    // Error overlay
                    if (error != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("⚠", fontSize = 28.sp)
                            Text(error!!, color = Color(0xFFFF4D6D), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x1AFF4D6D))
                                    .clickable { onDismiss() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text("Close", color = Color(0xFFFF4D6D), fontSize = 12.sp)
                            }
                        }
                    }
                }

                // ── Bottom controls ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x1AFFFFFF))
                            .border(1.dp, Border, RoundedCornerShape(10.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Stop & Close", color = TextMid, fontSize = 12.sp)
                    }

                    val statusColor = when {
                        error != null -> Color(0xFFFF4D6D)
                        buffering     -> Color(0xFFFACC15)
                        else          -> Color(0xFF4ADE80)
                    }
                    val statusText = when {
                        error != null -> "ERROR"
                        buffering     -> "BUFFERING"
                        else          -> "LIVE"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(statusColor.copy(0.15f))
                            .border(1.dp, statusColor.copy(0.3f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            statusText,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
