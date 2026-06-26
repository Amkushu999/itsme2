package com.itsme.amkush.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.itsme.amkush.model.AppInfo
import com.itsme.amkush.network.ApiClient
import com.itsme.amkush.network.models.TokenRequest
import com.itsme.amkush.utils.DeviceUtils
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import kotlinx.coroutines.*

// ─── Colors ───────────────────────────────────────────────────────────────────
private val BgDark   = Color(0xFF0D0D18)
private val Surface  = Color(0x12FFFFFF)
private val Border   = Color(0x1AFFFFFF)
private val Violet   = Color(0xFF6C63FF)
private val Pink     = Color(0xFFFF4D9D)
private val GreenOk  = Color(0xFF4ADE80)
private val TextSec  = Color(0x44FFFFFF)
private val TextMid  = Color(0x88FFFFFF)

class HomeScreen : ComponentActivity() {

    @SuppressLint("QueryPermissionsNeeded")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedPrefs.init(this)

        setContent {
            HomeScreenContent(
                onProceedToDashboard = { app ->
                    val intent = Intent(this, TabsScreen::class.java).apply {
                        putExtra("target_package", app.packageName)
                        putExtra("target_app_name", app.appName)
                    }
                    startActivity(intent)
                    finish()
                },
                onProceedToPayment = { app ->
                    val intent = Intent(this, PaymentScreen::class.java).apply {
                        putExtra("target_package", app.packageName)
                        putExtra("target_app_name", app.appName)
                    }
                    startActivity(intent)
                }
            )
        }
    }
}

@SuppressLint("QueryPermissionsNeeded")
@Composable
private fun HomeScreenContent(
    onProceedToDashboard: (AppInfo) -> Unit,
    onProceedToPayment: (AppInfo) -> Unit
) {
    val context = LocalContext.current

    var appList      by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading      by remember { mutableStateOf(true) }
    var selectedApp  by remember { mutableStateOf<AppInfo?>(null) }
    var showAppList  by remember { mutableStateOf(false) }
    var search       by remember { mutableStateOf("") }
    var locking      by remember { mutableStateOf(false) }

    val filteredApps = remember(appList, search) {
        val q = search.lowercase().trim()
        if (q.isEmpty()) appList
        else appList.filter { it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    // Load installed apps
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val pkgs = pm.getInstalledApplications(0)
                val apps = pkgs.map { pkg ->
                    val name = pm.getApplicationLabel(pkg).toString()
                    val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
                    val isSys = (pkg.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    AppInfo(pkg.packageName, name, icon, isSys)
                }.sortedBy { it.appName.lowercase() }

                withContext(Dispatchers.Main) {
                    appList = apps
                    loading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading = false
                    Logger.e("Error loading apps", e)
                }
            }
        }

        // Load saved target
        val pkg  = SharedPrefs.getTargetPackage()
        val name = SharedPrefs.getTargetAppName()
        if (!pkg.isNullOrEmpty() && !name.isNullOrEmpty()) {
            val found = appList.find { it.packageName == pkg }
            selectedApp = found ?: AppInfo(pkg, name, null)
        }
    }

    // Restore saved target once apps load
    LaunchedEffect(appList) {
        if (selectedApp == null) {
            val pkg  = SharedPrefs.getTargetPackage()
            val name = SharedPrefs.getTargetAppName()
            if (!pkg.isNullOrEmpty() && !name.isNullOrEmpty()) {
                val found = appList.find { it.packageName == pkg }
                selectedApp = found ?: AppInfo(pkg, name, null)
            }
        }
    }

    fun handleSelectApp(app: AppInfo) {
        selectedApp = app
        showAppList = false
        search = ""
        SharedPrefs.setTargetPackage(app.packageName)
        SharedPrefs.setTargetAppName(app.appName)
    }

    fun handleLock() {
        val app = selectedApp ?: run { showAppList = true; return }
        locking = true

        CoroutineScope(Dispatchers.IO).launch {
            val token = SharedPrefs.getActivationToken()
            if (!token.isNullOrEmpty()) {
                try {
                    val deviceId = DeviceUtils.getDeviceId(context)
                    val request  = TokenRequest(token, deviceId)
                    val response = ApiClient.getApiService().verifyToken(request).execute()
                    withContext(Dispatchers.Main) {
                        locking = false
                        if (response.isSuccessful && response.body()?.valid == true) {
                            onProceedToDashboard(app)
                        } else {
                            onProceedToPayment(app)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        locking = false
                        Logger.e("Error verifying token", e)
                        onProceedToPayment(app)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    locking = false
                    onProceedToPayment(app)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Header ──
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "FACEGATE",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp,
                    color = Color.White
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Color.Transparent, Violet, Color.Transparent))
                        )
                )
            }

            // ── Target App Card ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(24.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TARGET APP", fontSize = 10.sp, color = Violet,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.height(12.dp))

                    if (selectedApp != null) {
                        val app = selectedApp!!
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppIconCircle(app = app, size = 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(app.appName, color = Color.White, fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, color = TextSec, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x1AFFFFFF))
                                    .clickable { selectedApp = null; SharedPrefs.clearTarget() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✕", color = TextMid, fontSize = 13.sp)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAppList = !showAppList },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x14FFFFFF))
                                    .border(1.5.dp, Color(0x2AFFFFFF), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📷", fontSize = 18.sp)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Select target app", color = TextMid, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Tap to choose", color = TextSec, fontSize = 11.sp)
                            }
                            Text("›", color = TextSec, fontSize = 20.sp)
                        }
                    }
                }
            }

            // ── App List ──
            AnimatedVisibility(
                visible = showAppList,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0x0DFFFFFF))
                        .border(1.dp, Border, RoundedCornerShape(24.dp))
                ) {
                    // Search bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🔍", fontSize = 13.sp, color = TextMid)
                        BasicTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                            singleLine = true,
                            decorationBox = { inner ->
                                if (search.isEmpty()) Text("Search apps...", color = TextSec, fontSize = 14.sp)
                                inner()
                            },
                            cursorBrush = SolidColor(Violet)
                        )
                        if (search.isNotEmpty()) {
                            Text("✕", modifier = Modifier.clickable { search = "" }, color = TextSec, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(color = Color(0x14FFFFFF))

                    if (loading) {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Violet, modifier = Modifier.size(28.dp))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { handleSelectApp(app) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    AppIconCircle(app = app, size = 40.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(app.appName, color = Color.White, fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(app.packageName, color = TextSec, fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (selectedApp?.packageName == app.packageName) {
                                        Text("✔", color = Violet, fontSize = 14.sp)
                                    }
                                }
                                HorizontalDivider(color = Color(0x0AFFFFFF))
                            }
                        }
                    }
                }
            }

            // ── Lock Target Button ──
            val hasApp = selectedApp != null
            val btnBrush = if (hasApp) Brush.linearGradient(listOf(Violet, Pink))
                           else Brush.linearGradient(listOf(Color(0x1AFFFFFF), Color(0x1AFFFFFF)))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(btnBrush)
                    .clickable(enabled = !locking) { handleLock() },
                contentAlignment = Alignment.Center
            ) {
                if (locking) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Locking target...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔒", fontSize = 16.sp)
                        Text("Lock Target", color = if (hasApp) Color.White else TextSec,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            // ── Footer ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🛡", fontSize = 10.sp, color = TextSec)
                Spacer(Modifier.width(4.dp))
                Text("FaceGate v2.0 — Secure", color = TextSec, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun AppIconCircle(app: AppInfo, size: Dp, cornerRadius: Dp = 14.dp) {
    val icon = app.icon
    if (icon != null) {
        val bitmap = remember(app.packageName) {
            drawableToBitmap(icon)
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier.size(size).clip(RoundedCornerShape(cornerRadius))
            )
            return
        }
    }
    // Initials fallback
    val fallbackColor = Color(hashColor(app.packageName))
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(fallbackColor),
        contentAlignment = Alignment.Center
    ) {
        Text(app.initials, color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.33f).sp)
    }
}

fun drawableToBitmap(drawable: Drawable): Bitmap? {
    return try {
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bmp
    } catch (e: Exception) { null }
}

private fun hashColor(pkg: String): Int {
    val colors = intArrayOf(
        0xFF6C63FF.toInt(), 0xFFFF4D9D.toInt(), 0xFF4ADE80.toInt(),
        0xFF2CA5E0.toInt(), 0xFFFF0000.toInt(), 0xFF25D366.toInt(),
        0xFF1877F2.toInt(), 0xFF5865F2.toInt(), 0xFFFFFC00.toInt(),
        0xFF010101.toInt(), 0xFF2D8CFF.toInt(), 0xFF00897B.toInt()
    )
    return colors[Math.abs(pkg.hashCode()) % colors.size]
}
