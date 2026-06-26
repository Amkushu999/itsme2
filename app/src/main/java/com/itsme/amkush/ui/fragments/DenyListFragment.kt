package com.itsme.amkush.ui.fragments

  import android.annotation.SuppressLint
  import android.content.pm.PackageManager
  import android.os.Build
  import androidx.compose.animation.core.*
  import androidx.compose.foundation.*
  import androidx.compose.foundation.layout.*
  import androidx.compose.foundation.lazy.*
  import androidx.compose.foundation.shape.*
  import androidx.compose.foundation.text.BasicTextField
  import androidx.compose.material3.CircularProgressIndicator
  import androidx.compose.material3.Text
  import androidx.compose.runtime.*
  import androidx.compose.ui.*
  import androidx.compose.ui.draw.clip
  import androidx.compose.ui.graphics.*
  import androidx.compose.ui.platform.LocalContext
  import androidx.compose.ui.text.font.FontFamily
  import androidx.compose.ui.text.font.FontWeight
  import androidx.compose.ui.text.style.TextOverflow
  import androidx.compose.ui.unit.*
  import com.itsme.amkush.model.AppInfo
  import com.itsme.amkush.ui.AppIconCircle
  import com.itsme.amkush.utils.SharedPrefs
  import kotlinx.coroutines.*

  private val Violet  = Color(0xFF6C63FF)
  private val RedDeny = Color(0xFFFF4D6D)
  private val TextSec = Color(0x44FFFFFF)
  private val TextMid = Color(0x88FFFFFF)
  private val Border  = Color(0x1AFFFFFF)
  private val Surface = Color(0x12FFFFFF)

  @SuppressLint("QueryPermissionsNeeded")
  @Composable
  fun DenyListContent() {
      val context = LocalContext.current

      var appList   by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
      var loading   by remember { mutableStateOf(true) }
      var search    by remember { mutableStateOf("") }
      var denyList  by remember { mutableStateOf<Set<String>>(emptySet()) }
      var showSys   by remember { mutableStateOf(false) }

      val filtered = remember(appList, search, showSys) {
          val q = search.lowercase().trim()
          val visible = if (showSys) appList else appList.filter { !it.isSystemApp }
          if (q.isEmpty()) visible
          else visible.filter {
              it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
          }
      }

      LaunchedEffect(Unit) {
          denyList = SharedPrefs.getDenyList()
          withContext(Dispatchers.IO) {
              try {
                  val pm = context.packageManager
                  // API 33+ requires ApplicationInfoFlags; older APIs take an int flag
                  val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                      pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                  } else {
                      @Suppress("DEPRECATION")
                      pm.getInstalledApplications(PackageManager.GET_META_DATA)
                  }
                  val apps = packages.map { pkg ->
                      val isSys = (pkg.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                      val name  = pm.getApplicationLabel(pkg).toString()
                      val icon  = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
                      AppInfo(pkg.packageName, name, icon, isSys)
                  }.sortedBy { it.appName.lowercase() }

                  withContext(Dispatchers.Main) { appList = apps; loading = false }
              } catch (_: Exception) {
                  withContext(Dispatchers.Main) { loading = false }
              }
          }
      }

      fun toggle(app: AppInfo) {
          if (app.packageName == context.packageName) return  // never allow un-hiding our own app
          val current = denyList.toMutableSet()
          if (current.contains(app.packageName)) {
              current.remove(app.packageName)
              SharedPrefs.removeFromDenyList(app.packageName)
          } else {
              current.add(app.packageName)
              SharedPrefs.addToDenyList(app.packageName)
          }
          denyList = current
      }

      Column(modifier = Modifier.fillMaxSize()) {
          // Header
          Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp)) {
              Text("Deny List", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
              Spacer(Modifier.height(10.dp))
              // Search bar
              Row(
                  modifier = Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(12.dp))
                      .background(Color(0x1AFFFFFF))
                      .border(1.dp, Border, RoundedCornerShape(12.dp))
                      .padding(horizontal = 12.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                  Text("🔍", fontSize = 12.sp, color = TextMid)
                  BasicTextField(
                      value = search,
                      onValueChange = { search = it },
                      modifier = Modifier.weight(1f),
                      textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                      singleLine = true,
                      decorationBox = { inner ->
                          if (search.isEmpty()) Text("Search...", color = TextSec, fontSize = 12.sp)
                          inner()
                      },
                      cursorBrush = SolidColor(Violet)
                  )
              }
              Spacer(Modifier.height(8.dp))
              // System app toggle chip
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  val chipBg = if (showSys) Violet.copy(0.2f) else Color(0x10FFFFFF)
                  val chipBorder = if (showSys) Violet.copy(0.5f) else Border
                  Row(
                      modifier = Modifier
                          .clip(RoundedCornerShape(20.dp))
                          .background(chipBg)
                          .border(1.dp, chipBorder, RoundedCornerShape(20.dp))
                          .clickable { showSys = !showSys }
                          .padding(horizontal = 12.dp, vertical = 6.dp),
                      horizontalArrangement = Arrangement.spacedBy(6.dp),
                      verticalAlignment = Alignment.CenterVertically
                  ) {
                      Text(if (showSys) "✓" else "+", color = if (showSys) Violet else TextMid, fontSize = 11.sp)
                      Text("System apps", color = if (showSys) Violet else TextMid, fontSize = 11.sp)
                  }
              }
          }

          if (loading) {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  CircularProgressIndicator(color = Violet)
              }
          } else {
              LazyColumn(
                  modifier = Modifier.fillMaxSize(),
                  contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                  items(filtered, key = { it.packageName }) { app ->
                      val isDenied   = denyList.contains(app.packageName)
                      val isOwnApp   = app.packageName == context.packageName
                      Row(
                          modifier = Modifier
                              .fillMaxWidth()
                              .clip(RoundedCornerShape(16.dp))
                              .background(
                                  when {
                                      isOwnApp  -> Color(0x1A6C63FF)
                                      isDenied  -> Color(0x1AFF4D6D)
                                      else      -> Surface
                                  }
                              )
                              .border(
                                  1.dp,
                                  when {
                                      isOwnApp -> Violet.copy(0.3f)
                                      isDenied -> RedDeny.copy(0.2f)
                                      else     -> Color.Transparent
                                  },
                                  RoundedCornerShape(16.dp)
                              )
                              .clickable(enabled = !isOwnApp) { toggle(app) }
                              .padding(horizontal = 12.dp, vertical = 10.dp),
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.spacedBy(12.dp)
                      ) {
                          AppIconCircle(app = app, size = 40.dp, cornerRadius = 12.dp)
                          Column(Modifier.weight(1f)) {
                              Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                  Text(
                                      app.appName,
                                      color = when { isOwnApp -> Violet; isDenied -> RedDeny; else -> Color.White },
                                      fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                      maxLines = 1, overflow = TextOverflow.Ellipsis
                                  )
                                  if (isOwnApp) {
                                      Text("default", color = Violet.copy(0.7f), fontSize = 9.sp,
                                          fontFamily = FontFamily.Monospace)
                                  }
                              }
                              Text(app.packageName, color = TextSec, fontSize = 9.sp,
                                  fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                          }
                          // Toggle indicator (plain if/else — no RowScope.AnimatedVisibility conflict)
                          Box(
                              modifier = Modifier
                                  .size(24.dp)
                                  .clip(CircleShape)
                                  .background(
                                      when { isOwnApp -> Violet; isDenied -> RedDeny; else -> Color(0x1AFFFFFF) }
                                  )
                                  .border(
                                      if (!isDenied && !isOwnApp) 1.5.dp else 0.dp,
                                      Color(0x33FFFFFF), CircleShape
                                  ),
                              contentAlignment = Alignment.Center
                          ) {
                              if (isDenied || isOwnApp) {
                                  Text("✔", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                              }
                          }
                      }
                  }
                  item { Spacer(Modifier.height(16.dp)) }
              }
          }
      }
  }
  