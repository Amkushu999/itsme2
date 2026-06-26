package com.itsme.amkush.ui

  import android.content.ClipData
  import android.content.ClipboardManager
  import android.content.Context
  import android.content.Intent
  import android.net.Uri
  import android.os.Bundle
  import android.widget.Toast
  import androidx.activity.ComponentActivity
  import androidx.activity.compose.setContent
  import androidx.compose.animation.*
  import androidx.compose.animation.core.*
  import androidx.compose.foundation.*
  import androidx.compose.foundation.layout.*
  import androidx.compose.foundation.shape.*
  import androidx.compose.foundation.text.BasicTextField
  import androidx.compose.material3.*
  import androidx.compose.runtime.*
  import androidx.compose.ui.*
  import androidx.compose.ui.draw.*
  import androidx.compose.ui.graphics.*
  import androidx.compose.ui.platform.LocalContext
  import androidx.compose.ui.text.font.*
  import androidx.compose.ui.text.input.PasswordVisualTransformation
  import androidx.compose.ui.text.input.VisualTransformation
  import androidx.compose.ui.text.style.TextOverflow
  import androidx.compose.ui.unit.*
  import com.itsme.amkush.network.ApiClient
  import com.itsme.amkush.network.models.ValidateRequest
  import com.itsme.amkush.utils.DeviceUtils
  import com.itsme.amkush.utils.Logger
  import com.itsme.amkush.utils.SharedPrefs
  import kotlinx.coroutines.*

  private val BgDark  = Color(0xFF0D0D18)
  private val Surface = Color(0x12FFFFFF)
  private val Border  = Color(0x1AFFFFFF)
  private val Violet  = Color(0xFF6C63FF)
  private val Pink    = Color(0xFFFF4D9D)
  private val RedErr  = Color(0xFFFF4D6D)
  private val YellWarn= Color(0xFFFACC15)
  private val TextSec = Color(0x44FFFFFF)
  private val TextMid = Color(0x88FFFFFF)

  class PaymentScreen : ComponentActivity() {
      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          val targetPackage = intent.getStringExtra("target_package")
          val targetAppName = intent.getStringExtra("target_app_name")
          SharedPrefs.init(this)
          val isTrial = SharedPrefs.isTrial()
          val expiry  = SharedPrefs.getTrialExpiry()
          if (isTrial && expiry > System.currentTimeMillis()) {
              proceedToDashboard(targetPackage, targetAppName); return
          }
          setContent {
              PaymentContent(
                  targetPackage = targetPackage,
                  targetAppName = targetAppName,
                  onBack    = { finish() },
                  onSuccess = { proceedToDashboard(targetPackage, targetAppName) }
              )
          }
      }
      private fun proceedToDashboard(pkg: String?, name: String?) {
          startActivity(Intent(this, TabsScreen::class.java).apply {
              putExtra("target_package", pkg)
              putExtra("target_app_name", name)
              putExtra("from_payment", true)
          })
          finish()
      }
  }

  @Composable
  private fun PaymentContent(
      targetPackage: String?,
      targetAppName: String?,
      onBack: () -> Unit,
      onSuccess: () -> Unit
  ) {
      val context  = LocalContext.current
      val deviceId = remember { DeviceUtils.getFormattedDeviceId(context) }
      var key      by remember { mutableStateOf("") }
      var showKey  by remember { mutableStateOf(false) }
      var loading  by remember { mutableStateOf(false) }
      var errorMsg by remember { mutableStateOf("") }

      LaunchedEffect(Unit) {
          SharedPrefs.init(context)
          SharedPrefs.setDeviceId(DeviceUtils.getFormattedDeviceId(context))
      }

      fun copyDeviceId() {
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
          Toast.makeText(context, "Device ID copied!", Toast.LENGTH_SHORT).show()
      }

      fun openBot() {
          context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/facegateofficialbot")))
      }

      fun activate() {
          if (key.trim().isEmpty()) { errorMsg = "Please enter an activation key"; return }
          errorMsg = ""; loading = true
          CoroutineScope(Dispatchers.IO).launch {
              try {
                  val rawDeviceId = DeviceUtils.getDeviceId(context)
                  val wifiIp = DeviceUtils.getWifiIpAddress(context)
                  val response = ApiClient.getApiService()
                      .validateKey(ValidateRequest(key = key.trim(), deviceId = rawDeviceId, wifiIp = wifiIp))
                      .execute()
                  withContext(Dispatchers.Main) {
                      loading = false
                      if (response.isSuccessful && response.body()?.success == true) {
                          val body = response.body()!!
                          body.token?.let { token ->
                              SharedPrefs.setActivationToken(token)
                              // BUG FIX: use server's isTrial flag — not a hardcoded key comparison
                              val trialFromServer = body.isTrial
                              SharedPrefs.setTrial(trialFromServer)
                              SharedPrefs.setPaid(!trialFromServer)
                              if (trialFromServer) {
                                  body.expiresAt?.let { expiresAtStr ->
                                      try {
                                          val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                          sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                          val date = sdf.parse(expiresAtStr)
                                          if (date != null) SharedPrefs.setTrialExpiry(date.time)
                                      } catch (e: Exception) {
                                          Logger.e("Failed to parse trial expiry: $expiresAtStr", e)
                                      }
                                  }
                              }
                              Toast.makeText(context, if (trialFromServer) "Trial activated!" else "Activation successful!", Toast.LENGTH_LONG).show()
                              onSuccess()
                          }
                      } else {
                          errorMsg = response.body()?.message ?: "Invalid activation key"
                      }
                  }
              } catch (e: Exception) {
                  withContext(Dispatchers.Main) {
                      loading = false
                      errorMsg = "Network error: ${e.message}"
                  }
              }
          }
      }

      Box(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
          Column(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
              Spacer(Modifier.height(16.dp))
              // Back
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0x1AFFFFFF)).clickable { onBack() }, contentAlignment = Alignment.Center) {
                      Text("‹", color = TextMid, fontSize = 20.sp)
                  }
                  Text("Activation", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
              }
              // Target app chip
              if (!targetAppName.isNullOrEmpty()) {
                  Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                      Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Violet), contentAlignment = Alignment.Center) {
                          Text(targetAppName.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                      }
                      Column(Modifier.weight(1f)) {
                          Text(targetAppName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                          if (!targetPackage.isNullOrEmpty()) Text(targetPackage, color = TextSec, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                      }
                  }
              }
              // Device ID card
              Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                      Column(Modifier.weight(1f)) {
                          Text("YOUR DEVICE ID", color = TextSec, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                          Spacer(Modifier.height(4.dp))
                          Text(deviceId, color = Violet, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                      }
                      Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x1A6C63FF)).clickable { copyDeviceId() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                          Text("Copy", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                      }
                  }
              }
              // How-to card
              Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x0FFACC15)).border(1.dp, Color(0x33FACC15), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                      Text("HOW TO GET A KEY", color = YellWarn, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                      listOf("Copy your Device ID above","Open the Telegram bot below","Send your Device ID to receive your key").forEachIndexed { i, s ->
                          Text("${i+1}. $s", color = YellWarn.copy(alpha = 0.6f), fontSize = 10.sp)
                      }
                      Spacer(Modifier.height(4.dp))
                      Text("Trial key: NOWORNEVER", color = TextSec, fontSize = 9.sp)
                  }
              }
              // Bot button
              Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x1A2CA5E0)).border(1.dp, Color(0x332CA5E0), RoundedCornerShape(16.dp)).clickable { openBot() }.padding(16.dp), contentAlignment = Alignment.Center) {
                  Text("Open Telegram Bot ✈", color = Color(0xFF2CA5E0), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
              }
              // Key input
              Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x1AFFFFFF)).border(1.5.dp, if (errorMsg.isNotEmpty()) RedErr.copy(0.3f) else Color(0x26FFFFFF), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("🔑", fontSize = 14.sp)
                  BasicTextField(value = key, onValueChange = { key = it; if (errorMsg.isNotEmpty()) errorMsg = "" },
                      modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                      textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                      singleLine = true,
                      visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                      decorationBox = { inner -> if (key.isEmpty()) Text("Enter activation key...", color = TextSec, fontSize = 13.sp); inner() },
                      cursorBrush = SolidColor(Violet)
                  )
                  Box(modifier = Modifier.clip(CircleShape).clickable { showKey = !showKey }.padding(8.dp)) {
                      Text(if (showKey) "👁" else "🙈", fontSize = 14.sp)
                  }
              }
              // Error
              if (errorMsg.isNotEmpty()) {
                  Text(errorMsg, color = RedErr, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
              }
              // Activate button
              Box(
                  modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                      .background(Brush.linearGradient(listOf(Violet, Pink)))
                      .clickable(enabled = !loading) { activate() }
                      .padding(vertical = 16.dp),
                  contentAlignment = Alignment.Center
              ) {
                  if (loading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                  else Text("Activate", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
              }
              Spacer(Modifier.height(32.dp))
          }
      }
  }
  