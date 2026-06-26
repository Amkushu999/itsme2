package com.itsme.amkush.ui.fragments

import android.content.Intent
import android.database.Cursor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.itsme.amkush.services.InjectionService
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs

private val Violet  = Color(0xFF6C63FF)
private val Pink    = Color(0xFFFF4D9D)
private val GreenOk = Color(0xFF4ADE80)
private val RedErr  = Color(0xFFFF4D6D)
private val TextSec = Color(0x44FFFFFF)
private val TextMid = Color(0x88FFFFFF)
private val Border  = Color(0x1AFFFFFF)
private val Surface = Color(0x12FFFFFF)

@Composable
fun MediaContent(
    targetPackage: String?,
    targetAppName: String?
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var mediaUri  by remember { mutableStateOf<Uri?>(null) }
    var fileName  by remember { mutableStateOf<String?>(null) }
    var isVideo   by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var zoom      by remember { mutableFloatStateOf(1f) }
    var panX      by remember { mutableFloatStateOf(0f) }
    var panY      by remember { mutableFloatStateOf(0f) }
    var isInjecting by remember { mutableStateOf(InjectionService.isRunning) }

    var videoViewRef: VideoView? by remember { mutableStateOf(null) }

    // Load saved media on start
    LaunchedEffect(Unit) {
        val saved = SharedPrefs.getLastUsedUrl()
        if (!saved.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(saved)
                mediaUri = uri
                fileName = getFileName(context, uri)
                val mime = context.contentResolver.getType(uri)
                isVideo = mime?.startsWith("video/") == true
            } catch (e: Exception) { Logger.e("Error loading saved media", e) }
        }
    }

    // Lifecycle observer for video pause/resume
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { videoViewRef?.takeIf { it.isPlaying }?.pause() }
                Lifecycle.Event.ON_RESUME -> { if (isPlaying) videoViewRef?.start() }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) { Logger.e("Permission", e) }
                }
                mediaUri = uri
                fileName = getFileName(context, uri)
                val mime = context.contentResolver.getType(uri)
                isVideo = mime?.startsWith("video/") == true
                isPlaying = false
                zoom = 1f; panX = 0f; panY = 0f
                SharedPrefs.setLastUsedUrl(uri.toString())
                Toast.makeText(context, "Media selected: $fileName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun pickMedia() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
        }
        pickLauncher.launch(intent)
    }

    fun clearMedia() {
        videoViewRef?.stopPlayback()
        mediaUri = null; fileName = null; isPlaying = false
        zoom = 1f; panX = 0f; panY = 0f
        SharedPrefs.setLastUsedUrl(null)
        Toast.makeText(context, "Media cleared", Toast.LENGTH_SHORT).show()
    }

    fun togglePlay() {
        val vv = videoViewRef ?: return
        if (isPlaying) { vv.pause(); isPlaying = false } else { vv.start(); isPlaying = true }
    }

    fun startInjection() {
        val pkg = targetPackage ?: SharedPrefs.getTargetPackage()
        if (pkg.isNullOrEmpty()) {
            Toast.makeText(context, "No target app selected", Toast.LENGTH_LONG).show()
            return
        }
        val uri = mediaUri ?: run {
            Toast.makeText(context, "Upload media first", Toast.LENGTH_SHORT).show()
            return
        }
        val streamUrl = SharedPrefs.getStreamUrl()
        if (!streamUrl.isNullOrEmpty()) {
            android.app.AlertDialog.Builder(context)
                .setTitle("Choose Injection Mode")
                .setMessage("Both a stream URL and local media are configured.\nWhich source?")
                .setPositiveButton("Inject Local Media") { _, _ ->
                    SharedPrefs.setStreamUrl(null)
                    InjectionService.start(context, pkg, mediaUri = uri.toString())
                    isInjecting = true
                    Toast.makeText(context, "Injection started", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Inject Live Stream") { _, _ ->
                    mediaUri = null; SharedPrefs.setLastUsedUrl(null)
                    InjectionService.start(context, pkg, streamUrl = streamUrl)
                    isInjecting = true
                    Toast.makeText(context, "Injection started", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Cancel", null).show()
            return
        }
        InjectionService.start(context, pkg, mediaUri = uri.toString())
        isInjecting = true
        Toast.makeText(context, "Injection started", Toast.LENGTH_SHORT).show()
    }

    fun stopInjection() {
        InjectionService.stop(context)
        isInjecting = false
        Toast.makeText(context, "Injection stopped", Toast.LENGTH_SHORT).show()
    }

    val JoyBtn: @Composable (String, () -> Unit) -> Unit = { icon, onClick ->
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(10.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 13.sp, color = TextMid) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Upload row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Violet, Pink)))
                    .clickable { pickMedia() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⬆", fontSize = 12.sp, color = Color.White)
                    Text("Upload Media", color = Color.White, fontSize = 12.sp)
                }
            }

            if (fileName != null) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface)
                        .border(1.dp, Border, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("🎬", fontSize = 10.sp, color = Violet)
                    Text(fileName ?: "", color = TextMid, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FF4D6D))
                            .clickable { clearMedia() },
                        contentAlignment = Alignment.Center
                    ) { Text("✕", color = RedErr, fontSize = 9.sp) }
                }
            }
        }

        // Preview box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0A0A14))
                .border(1.dp, Border, RoundedCornerShape(16.dp))
        ) {
            val uri = mediaUri
            if (uri != null) {
                if (isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                videoViewRef = this
                                setVideoURI(uri)
                                val mc = MediaController(ctx)
                                mc.setAnchorView(this)
                                setMediaController(mc)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start(); isPlaying = true
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY)
                    )
                } else {
                    // Image via coil/glide would need extra dep; use AndroidView ImageView
                    AndroidView(
                        factory = { ctx ->
                            android.widget.ImageView(ctx).apply {
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                com.bumptech.glide.Glide.with(ctx).load(uri).into(this)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY)
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🎞", fontSize = 22.sp, color = TextSec)
                    Spacer(Modifier.height(8.dp))
                    Text("No media selected", color = TextSec, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Controls
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pan D-pad
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    JoyBtn("▲") { panY -= 16f }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        JoyBtn("◀") { panX -= 16f }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x0AFFFFFF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0x33FFFFFF)))
                        }
                        JoyBtn("▶") { panX += 16f }
                    }
                    JoyBtn("▼") { panY += 16f }
                }

                Box(Modifier.width(1.dp).height(80.dp).background(Color(0x1AFFFFFF)))

                // Zoom
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Zoom", color = TextSec, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    JoyBtn("🔍+") { zoom = (zoom + 0.2f).coerceIn(0.5f, 3f) }
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x1AFFFFFF))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(((zoom - 0.5f) / 2.5f).coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(3.dp))
                                .background(Brush.horizontalGradient(listOf(Violet, Pink)))
                        )
                    }
                    JoyBtn("🔍-") { zoom = (zoom - 0.2f).coerceIn(0.5f, 3f) }
                }

                Box(Modifier.width(1.dp).height(80.dp).background(Color(0x1AFFFFFF)))

                // Play/Stop (video only)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(48.dp)) {
                    if (isVideo && mediaUri != null) {
                        Text(if (isPlaying) "Stop" else "Play", color = TextSec, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isPlaying) Brush.linearGradient(listOf(RedErr, Color(0xFFFF8C00)))
                                    else Brush.linearGradient(listOf(Violet, Pink))
                                )
                                .clickable { togglePlay() },
                            contentAlignment = Alignment.Center
                        ) { Text(if (isPlaying) "⏹" else "▶", color = Color.White, fontSize = 18.sp) }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x0AFFFFFF))
                                .border(1.dp, Border, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("▶", color = Color(0x30FFFFFF), fontSize = 16.sp) }
                        Text("video only", color = TextSec, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (panX != 0f || panY != 0f || zoom != 1f) {
                // This would need absolute positioning - handled below
            }
        }

        // Reset pan/zoom
        if (panX != 0f || panY != 0f || zoom != 1f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .clickable { zoom = 1f; panX = 0f; panY = 0f }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Reset Pan & Zoom", color = TextMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // Injection controls
        if (!isInjecting) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (mediaUri != null) Color(0x334ADE80) else Color(0x1AFFFFFF))
                    .border(1.dp, if (mediaUri != null) GreenOk.copy(0.4f) else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable(enabled = mediaUri != null) { startInjection() },
                contentAlignment = Alignment.Center
            ) {
                Text("▶  Start Injection",
                    color = if (mediaUri != null) GreenOk else TextMid,
                    fontSize = 14.sp)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x33FF4D6D))
                    .border(1.dp, Color(0x55FF4D6D), RoundedCornerShape(16.dp))
                    .clickable { stopInjection() },
                contentAlignment = Alignment.Center
            ) {
                Text("⏹  Stop Injection", color = RedErr, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String {
    var name = "Unknown"
    val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) name = it.getString(idx) ?: "Unknown"
        }
    }
    return name
}
