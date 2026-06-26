package com.itsme.amkush.ui

  import android.content.Intent
  import android.os.Build
  import android.os.Bundle
  import androidx.activity.ComponentActivity
  import androidx.activity.compose.setContent
  import androidx.compose.animation.*
  import androidx.compose.animation.core.*
  import androidx.compose.foundation.background
  import androidx.compose.foundation.layout.*
  import androidx.compose.material3.Text
  import androidx.compose.runtime.*
  import androidx.compose.ui.*
  import androidx.compose.ui.graphics.*
  import androidx.compose.ui.text.font.*
  import androidx.compose.ui.unit.*
  import com.itsme.amkush.utils.SharedPrefs
  import kotlinx.coroutines.delay

  private val DarkBg = Color(0xFF0D0D18)
  private val Violet  = Color(0xFF6C63FF)
  private val Pink    = Color(0xFFFF4D9D)

  class SplashScreenActivity : ComponentActivity() {
      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          SharedPrefs.init(this)
          setContent {
              SplashContent(
                  onFinished = {
                      val next = if (SharedPrefs.isActivated())
                          Intent(this, TabsActivity::class.java)
                      else
                          Intent(this, PaymentActivity::class.java)
                      startActivity(next)
                      // API 34+ uses overrideActivityTransition; older uses deprecated method
                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                          overrideActivityTransition(
                              OVERRIDE_TRANSITION_OPEN,
                              android.R.anim.fade_in,
                              android.R.anim.fade_out
                          )
                      } else {
                          @Suppress("DEPRECATION")
                          overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                      }
                      finish()
                  }
              )
          }
      }
  }

  @Composable
  private fun SplashContent(onFinished: () -> Unit) {
      var show   by remember { mutableStateOf(false) }
      var cursor by remember { mutableStateOf(true) }

      LaunchedEffect(Unit) {
          delay(200); show = true
          while (true) { delay(530); cursor = !cursor }
      }
      LaunchedEffect(Unit) { delay(2800); onFinished() }

      Box(
          modifier = Modifier
              .fillMaxSize()
              .background(DarkBg),
          contentAlignment = Alignment.Center
      ) {
          AnimatedVisibility(
              visible = show,
              enter = fadeIn(tween(600)) + scaleIn(tween(600), initialScale = 0.85f)
          ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Text(
                      "FaceGate",
                      fontSize = 38.sp,
                      fontWeight = FontWeight.Bold,
                      style = androidx.compose.ui.text.TextStyle(
                          brush = Brush.linearGradient(listOf(Violet, Pink))
                      )
                  )
                  Spacer(Modifier.height(6.dp))
                  Row {
                      Text("Initialising", color = Color.White.copy(0.55f),
                          fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                      Text(if (cursor) "_" else " ", color = Violet,
                          fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                  }
              }
          }
      }
  }
  