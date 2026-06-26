package com.itsme.amkush.ui.fragments

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.itsme.amkush.services.InjectionService
import com.itsme.amkush.utils.SharedPrefs

private val Violet  = Color(0xFF6C63FF)
private val Pink    = Color(0xFFFF4D9D)
private val GreenOk = Color(0xFF4ADE80)
private val TextSec = Color(0x44FFFFFF)
private val TextMid = Color(0x88FFFFFF)
private val Border  = Color(0x26FFFFFF)
private val Surface = Color(0x1AFFFFFF)

private data class Protocol(val id: String, val label: String, val hint: String)
private val PROTOCOLS = listOf(
    Protocol("hls",    "HLS",    ".m3u8"),
    Protocol("rtmp",   "RTMP",   "rtmp://"),
    Protocol("rtsp",   "RTSP",   "rtsp://"),
    Protocol("dash",   "DASH",   ".mpd"),
    Protocol("rtp",    "RTP",    "rtp://"),
    Protocol("udp",    "UDP",    "udp://"),
    Protocol("srt",    "SRT",    "srt://"),
    Protocol("mms",    "MMS",    "mms://"),
    Protocol("ftp",    "FTP",    "ftp://"),
    Protocol("http",   "HTTP",   "http(s)://"),
    Protocol("direct", "Direct", "mp4/webm"),
)

@Composable
fun StreamSetupContent(
    targetPackage: String?,
    targetAppName: String?,
    initialUrl: String,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    var input    by remember { mutableStateOf(initialUrl) }
    var savedUrl by remember { mutableStateOf(initialUrl) }
    var protocol by remember { mutableStateOf("hls") }
    var saved    by remember { mutableStateOf(false) }
    var isInjecting by remember { mutableStateOf(InjectionService.isRunning) }

    LaunchedEffect(Unit) {
        val stored = SharedPrefs.getStreamUrl() ?: ""
        if (stored.isNotEmpty()) {
            input = stored
            savedUrl = stored
        }
    }

    fun handleSave() {
        val url = input.trim()
        if (url.isEmpty()) {
            Toast.makeText(context, "Please enter a stream URL", Toast.LENGTH_SHORT).show()
            return
        }
        savedUrl = url
        SharedPrefs.setStreamUrl(url)
        SharedPrefs.setStreamType(PROTOCOLS.find { it.id == protocol }?.label ?: protocol)
        onSaved(url)
        saved = true
    }

    LaunchedEffect(saved) {
        if (saved) {
            kotlinx.coroutines.delay(2200)
            saved = false
        }
    }

    fun startInjection() {
        val url = input.trim()
        if (url.isEmpty()) {
            Toast.makeText(context, "Configure a stream URL first", Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = targetPackage ?: SharedPrefs.getTargetPackage()
        if (pkg.isNullOrEmpty()) {
            Toast.makeText(context, "No target app selected", Toast.LENGTH_LONG).show()
            return
        }
        val mediaUri = SharedPrefs.getLastUsedUrl()
        if (!mediaUri.isNullOrEmpty()) {
            android.app.AlertDialog.Builder(context)
                .setTitle("Choose Injection Mode")
                .setMessage("Both a live stream URL and a local media file are configured.\nWhich source should be injected?")
                .setPositiveButton("Inject Live Stream") { _, _ ->
                    SharedPrefs.setLastUsedUrl(null)
                    InjectionService.start(context, pkg, streamUrl = url)
                    isInjecting = true
                    Toast.makeText(context, "Injection started", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Inject Local Media") { _, _ ->
                    InjectionService.start(context, pkg, mediaUri = mediaUri)
                    isInjecting = true
                    Toast.makeText(context, "Injection started", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Cancel", null)
                .show()
            return
        }
        InjectionService.start(context, pkg, streamUrl = url)
        isInjecting = true
        val appName = targetAppName ?: SharedPrefs.getTargetAppName()
        Toast.makeText(context, "Injection started for $appName", Toast.LENGTH_SHORT).show()
    }

    fun stopInjection() {
        InjectionService.stop(context)
        isInjecting = false
        Toast.makeText(context, "Injection stopped", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column {
            Text("Stream Configuration", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Enter your stream URL", color = TextSec, fontSize = 12.sp)
        }

        // Protocol grid
        val rows = PROTOCOLS.chunked(4)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        val isSelected = protocol == p.id
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Color(0x336C63FF) else Surface)
                                .border(1.5.dp, if (isSelected) Violet else Color.Transparent, RoundedCornerShape(16.dp))
                                .clickable { protocol = p.id }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(p.label, color = if (isSelected) Violet else Color(0xAAFFFFFF),
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(p.hint, color = TextSec, fontSize = 8.sp)
                            }
                        }
                    }
                    // Fill remaining cells if row is incomplete
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // URL input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .border(1.5.dp, Border, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📶", fontSize = 14.sp, color = Violet.copy(alpha = 0.8f))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                singleLine = true,
                decorationBox = { inner ->
                    if (input.isEmpty()) Text(
                        "Paste ${PROTOCOLS.find { it.id == protocol }?.label ?: ""} stream URL...",
                        color = TextSec, fontSize = 14.sp)
                    inner()
                },
                cursorBrush = SolidColor(Violet)
            )
        }

        // Save button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (input.trim().isNotEmpty()) Brush.linearGradient(listOf(Violet, Pink))
                    else Brush.linearGradient(listOf(Color(0x1AFFFFFF), Color(0x1AFFFFFF)))
                )
                .clickable(enabled = input.trim().isNotEmpty()) { handleSave() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(targetState = saved, label = "saveBtn") { isSaved ->
                if (isSaved) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✔", color = Color.White, fontSize = 14.sp)
                        Text("Saved!", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("💾", fontSize = 14.sp)
                        Text("Save & Apply", color = if (input.trim().isNotEmpty()) Color.White else TextSec,
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }

        // Injection controls
        if (!isInjecting) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (input.trim().isNotEmpty()) Color(0x334ADE80) else Color(0x1AFFFFFF))
                    .border(1.dp, if (input.trim().isNotEmpty()) GreenOk.copy(0.4f) else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable(enabled = input.trim().isNotEmpty()) { startInjection() },
                contentAlignment = Alignment.Center
            ) {
                Text("▶  Start Injection",
                    color = if (input.trim().isNotEmpty()) GreenOk else TextSec,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                Text("⏹  Stop Injection", color = Color(0xFFFF4D6D),
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}
