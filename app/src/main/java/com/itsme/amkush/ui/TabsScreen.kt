package com.itsme.amkush.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.itsme.amkush.R
import com.itsme.amkush.ui.fragments.*
import com.itsme.amkush.utils.SharedPrefs

private val BgDark  = Color(0xFF0E0E1C)
private val Violet  = Color(0xFF6C63FF)

enum class TabScreen { HOME, STREAM, DENYLIST, MEDIA, STATS, SETTINGS }

data class TabItem(val id: TabScreen, val title: String, val iconRes: Int)

class TabsScreen : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPackage = intent.getStringExtra("target_package")
        val targetAppName = intent.getStringExtra("target_app_name")

        SharedPrefs.init(this)

        if (intent.getBooleanExtra("from_payment", false)) {
            targetPackage?.let { SharedPrefs.setTargetPackage(it) }
            targetAppName?.let { SharedPrefs.setTargetAppName(it) }
        }

        setContent {
            DashboardContent(
                targetPackage = targetPackage,
                targetAppName = targetAppName,
                onBack = { finish() }
            )
        }
    }
}

@Composable
fun DashboardContent(
    targetPackage: String?,
    targetAppName: String?,
    onBack: () -> Unit
) {
    var currentTab   by remember { mutableStateOf(TabScreen.HOME) }
    var streamUrl    by remember { mutableStateOf(SharedPrefs.getStreamUrl() ?: "") }
    var playerOpen   by remember { mutableStateOf(false) }

    val tabs = listOf(
        TabItem(TabScreen.HOME,     "Home",     R.drawable.ic_home),
        TabItem(TabScreen.STREAM,   "Stream",   R.drawable.ic_stream),
        TabItem(TabScreen.DENYLIST, "Deny",     R.drawable.ic_shield),
        TabItem(TabScreen.MEDIA,    "Media",    R.drawable.ic_media),
        TabItem(TabScreen.STATS,    "Stats",    R.drawable.ic_stats),
        TabItem(TabScreen.SETTINGS, "Settings", R.drawable.ic_settings),
    )

    BackHandler {
        if (currentTab != TabScreen.HOME) currentTab = TabScreen.HOME
        else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D18))
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── Status bar sim ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x1A6C63FF))
                            .clickable { onBack() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("‹ Back", color = Violet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(12.dp)
                            .border(1.dp, Color(0x88FFFFFF), RoundedCornerShape(3.dp))
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.7f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF4ADE80))
                        )
                    }
                }
            }

            // ── Content ──
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        fadeIn(tween(220)) + slideInHorizontally { it / 10 } togetherWith
                        fadeOut(tween(180)) + slideOutHorizontally { -it / 10 }
                    },
                    label = "tab_content"
                ) { tab ->
                    when (tab) {
                        TabScreen.HOME     -> HomeTabContent(
                            targetPackage = targetPackage,
                            targetAppName = targetAppName,
                            savedUrl = streamUrl,
                            onViewStream = { playerOpen = true }
                        )
                        TabScreen.STREAM   -> StreamSetupContent(
                            targetPackage = targetPackage,
                            targetAppName = targetAppName,
                            initialUrl = streamUrl,
                            onSaved = { url -> streamUrl = url }
                        )
                        TabScreen.DENYLIST -> DenyListContent()
                        TabScreen.MEDIA    -> MediaContent(
                            targetPackage = targetPackage,
                            targetAppName = targetAppName
                        )
                        TabScreen.STATS    -> StatsContent(
                            targetPackage = targetPackage,
                            targetAppName = targetAppName
                        )
                        TabScreen.SETTINGS -> DeviceSpoofContent()
                    }
                }
            }

            // ── Bottom Navigation ──
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp, top = 8.dp), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xD1000000))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEach { tab ->
                        val isSelected = currentTab == tab.id
                        val scale by animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 1f,
                            animationSpec = spring(stiffness = 400f, dampingRatio = 0.6f),
                            label = "scale_${tab.id}"
                        )
                        Column(
                            modifier = Modifier
                                .scale(scale)
                                .width(40.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) { currentTab = tab.id },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(tab.iconRes),
                                contentDescription = tab.title,
                                tint = if (isSelected) Color.White else Color(0xFF888888),
                                modifier = Modifier.size(24.dp)
                            )
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Text(
                                    tab.title,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Stream Player Overlay ──
        if (playerOpen && streamUrl.isNotEmpty()) {
            StreamPreviewDialog(url = streamUrl, onDismiss = { playerOpen = false })
        }
    }
}
