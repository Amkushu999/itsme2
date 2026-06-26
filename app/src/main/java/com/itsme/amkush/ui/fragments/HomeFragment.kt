package com.itsme.amkush.ui.fragments

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
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.itsme.amkush.model.AppInfo
import com.itsme.amkush.ui.AppIconCircle
import com.itsme.amkush.utils.SharedPrefs

private val BgGrad   = Color(0xFF1A1A2E)
private val BgGrad2  = Color(0xFF16213E)
private val Violet   = Color(0xFF6C63FF)
private val Pink     = Color(0xFFFF4D9D)
private val GreenOk  = Color(0xFF4ADE80)
private val TextSec  = Color(0x44FFFFFF)
private val TextMid  = Color(0x88FFFFFF)
private val Border   = Color(0x1AFFFFFF)

@Composable
fun HomeTabContent(
    targetPackage: String?,
    targetAppName: String?,
    savedUrl: String,
    onViewStream: () -> Unit
) {
    val context = LocalContext.current
    val effPkg  = targetPackage ?: SharedPrefs.getTargetPackage()
    val effName = targetAppName ?: SharedPrefs.getTargetAppName()
    val hasTarget = !effName.isNullOrEmpty()
    val hasStream = savedUrl.isNotEmpty()

    // Try to resolve AppInfo for icon
    val targetApp = if (hasTarget) AppInfo(effPkg ?: "", effName ?: "") else null

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "pulseA"
    )
    val glowAnim by pulse.animateFloat(
        initialValue = 0.27f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "glow"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // ── Locked Target Card ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(listOf(BgGrad, BgGrad2),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(1000f, 1000f))
                )
                .border(1.dp, Border, RoundedCornerShape(24.dp))
        ) {
            // Radial glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                (if (hasTarget && targetApp != null) Color(0xFF6C63FF) else Violet).copy(alpha = if (hasTarget) glowAnim else 0.27f),
                                Color.Transparent
                            ),
                            radius = 600f
                        )
                    )
            )

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔒", fontSize = 10.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("LOCKED TARGET", color = Violet, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (hasTarget) GreenOk.copy(alpha = pulseAlpha) else Color(0x55FFFFFF))
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (hasTarget && targetApp != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        AppIconCircle(app = targetApp, size = 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(effName ?: "", color = Color.White, fontWeight = FontWeight.Bold,
                                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(effPkg ?: "", color = TextSec, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("✔", color = GreenOk, fontSize = 12.sp)
                                Text("Active interception", color = GreenOk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x1AFFFFFF))
                                .border(1.5.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("🔒", fontSize = 20.sp, color = Color(0x55FFFFFF)) }
                        Column {
                            Text("No target selected", color = TextMid, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Select a target app to begin", color = TextSec, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Stream Section ──
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Live Stream Viewer", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (hasStream) "Stream configured — ready to watch" else "Go to Stream tab to configure a URL",
                color = TextSec, fontSize = 12.sp
            )

            if (hasStream) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brush.linearGradient(listOf(Violet, Pink)))
                        .clickable { onViewStream() }
                        .padding(horizontal = 32.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("▶", color = Color.White, fontSize = 16.sp)
                        Text("View Live Stream", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x1AFFFFFF))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📶", fontSize = 14.sp)
                    Text("No stream configured yet", color = TextMid, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
