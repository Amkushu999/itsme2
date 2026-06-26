package com.itsme.amkush.ui.fragments

  import androidx.compose.animation.core.*
  import androidx.compose.foundation.*
  import androidx.compose.foundation.layout.*
  import androidx.compose.foundation.shape.*
  import androidx.compose.material3.Text
  import androidx.compose.runtime.*
  import androidx.compose.ui.*
  import androidx.compose.ui.draw.*
  import androidx.compose.ui.graphics.*
  import androidx.compose.ui.text.font.*
  import androidx.compose.ui.text.style.TextOverflow
  import androidx.compose.ui.unit.*
  import com.itsme.amkush.model.HookStatus
  import com.itsme.amkush.model.HookStatusRegistry
  import com.itsme.amkush.utils.SharedPrefs

  private val Violet  = Color(0xFF6C63FF)
  private val GreenOk = Color(0xFF4ADE80)
  private val RedErr  = Color(0xFFFF4D6D)
  private val TextSec = Color(0x44FFFFFF)
  private val TextMid = Color(0x88FFFFFF)
  private val Border  = Color(0x1AFFFFFF)
  private val Surface = Color(0x12FFFFFF)

  @Composable
  fun StatsContent(
      targetPackage: String?,
      targetAppName: String?
  ) {
      val effName  = targetAppName ?: SharedPrefs.getTargetAppName()
      val hooks    = remember { HookStatusRegistry.getAllHooks() }
      val grouped  = remember(hooks) { hooks.groupBy { it.category } }
      val activeCount = remember(hooks) { hooks.count { it.isActive } }
      val totalCount  = hooks.size

      val pulse = rememberInfiniteTransition(label = "pulse")
      val blink by pulse.animateFloat(
          initialValue = 1f, targetValue = 0.3f,
          animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
          label = "blink"
      )

      Column(
          modifier = Modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
      ) {
          // Header — fixed: use start/end instead of mixing horizontal with top/bottom
          Row(
              modifier = Modifier
                  .fillMaxWidth()
                  .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
          ) {
              Column {
                  Text("Hook Status", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                  Text("Active module diagnostics", color = TextSec, fontSize = 11.sp)
              }
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Box(
                      modifier = Modifier
                          .size(8.dp)
                          .clip(CircleShape)
                          .background(GreenOk.copy(alpha = blink))
                  )
                  Text("ACTIVE", color = GreenOk, fontSize = 10.sp,
                      fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
              }
          }

          if (!effName.isNullOrEmpty()) {
              Row(
                  modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 16.dp)
                      .clip(RoundedCornerShape(12.dp))
                      .background(Surface)
                      .border(1.dp, Border, RoundedCornerShape(12.dp))
                      .padding(horizontal = 14.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                  Text("🎯", fontSize = 12.sp)
                  Text(effName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                      maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
              }
              Spacer(Modifier.height(10.dp))
          }

          // Summary card
          Box(
              modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp)
                  .clip(RoundedCornerShape(16.dp))
                  .background(Color(0x1A4ADE80))
                  .border(1.dp, GreenOk.copy(0.25f), RoundedCornerShape(16.dp))
                  .padding(horizontal = 16.dp, vertical = 14.dp)
          ) {
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  Column {
                      Text("$activeCount / $totalCount", color = GreenOk, fontSize = 28.sp,
                          fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                      Text("Hooks active", color = TextMid, fontSize = 12.sp)
                  }
                  Box(
                      modifier = Modifier
                          .size(56.dp)
                          .clip(CircleShape)
                          .background(GreenOk.copy(0.15f))
                          .border(2.dp, GreenOk.copy(0.4f), CircleShape),
                      contentAlignment = Alignment.Center
                  ) {
                      Text("${(activeCount * 100 / totalCount.coerceAtLeast(1))}%",
                          color = GreenOk, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                          fontFamily = FontFamily.Monospace)
                  }
              }
          }

          Spacer(Modifier.height(12.dp))

          grouped.forEach { (category, hooksInCat) ->
              Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                  Text(
                      category,
                      color = Violet,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold,
                      letterSpacing = 2.sp,
                      modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
                  )
                  Box(
                      modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(16.dp))
                          .background(Surface)
                          .border(1.dp, Border, RoundedCornerShape(16.dp))
                  ) {
                      Column {
                          hooksInCat.forEachIndexed { idx, hook ->
                              HookRow(hook = hook)
                              if (idx < hooksInCat.lastIndex) {
                                  Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0AFFFFFF)))
                              }
                          }
                      }
                  }
              }
          }

          Spacer(Modifier.height(24.dp))
      }
  }

  @Composable
  private fun HookRow(hook: HookStatus) {
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
      ) {
          Text(hook.name, color = Color.White, fontSize = 13.sp,
              fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Box(
                  modifier = Modifier
                      .size(8.dp)
                      .clip(CircleShape)
                      .background(if (hook.isActive) Color(0xFF4ADE80) else Color(0xFFFF4D6D))
              )
              Text(
                  if (hook.isActive) "Active" else "Inactive",
                  color = if (hook.isActive) Color(0xFF4ADE80) else Color(0xFFFF4D6D),
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace
              )
          }
      }
  }
  