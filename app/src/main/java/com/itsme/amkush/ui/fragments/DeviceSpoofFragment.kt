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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.itsme.amkush.utils.DeviceUtils
import com.itsme.amkush.utils.SharedPrefs

private val Violet  = Color(0xFF6C63FF)
private val Pink    = Color(0xFFFF4D9D)
private val GreenOk = Color(0xFF4ADE80)
private val YellWarn= Color(0xFFFACC15)
private val TextSec = Color(0x44FFFFFF)
private val TextMid = Color(0x88FFFFFF)
private val Border  = Color(0x1AFFFFFF)
private val Surface = Color(0x12FFFFFF)

private val ANDROID_VERSIONS = listOf("10","11","12","12.1","13","14","15")
private val TOP_BRANDS = listOf("Samsung","Google","Xiaomi","OnePlus","Oppo","Vivo","Realme","Huawei","Sony","Motorola","Nokia","LG","Asus","HTC","Lenovo")

@Composable
fun DeviceSpoofContent() {
    val context  = LocalContext.current
    val realInfo = remember { DeviceUtils.getDeviceInfo() }

    var spoofModel     by remember { mutableStateOf(SharedPrefs.getSpoofModel() ?: "") }
    var spoofBrand     by remember { mutableStateOf(SharedPrefs.getSpoofBrand() ?: "") }
    var spoofMfr       by remember { mutableStateOf(SharedPrefs.getSpoofManufacturer() ?: "") }
    var spoofAndroid   by remember { mutableStateOf(SharedPrefs.getSpoofAndroid() ?: "") }
    var spoofBuildId   by remember { mutableStateOf(SharedPrefs.getSpoofBuildId() ?: "") }
    var spoofSecPatch  by remember { mutableStateOf(SharedPrefs.getSpoofSecurityPatch() ?: "") }
    var spoofDeviceId  by remember { mutableStateOf(SharedPrefs.getSpoofDeviceId() ?: "") }
    var spoofSerial    by remember { mutableStateOf(SharedPrefs.getSpoofSerial() ?: "") }
    var isActive       by remember { mutableStateOf(SharedPrefs.isSpoofActive()) }
    var saved          by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) { kotlinx.coroutines.delay(2000); saved = false }
    }

    val effModel = if (isActive && spoofModel.isNotEmpty()) spoofModel else realInfo.model
    val effBrand = if (isActive && spoofBrand.isNotEmpty()) spoofBrand else realInfo.brand
    val effMfr   = if (isActive && spoofMfr.isNotEmpty()) spoofMfr else realInfo.manufacturer
    val effAndroid = if (isActive && spoofAndroid.isNotEmpty()) "Android $spoofAndroid" else "Android ${realInfo.androidVersion}"
    val effBuild = if (isActive && spoofBuildId.isNotEmpty()) spoofBuildId else realInfo.buildId
    val effPatch = if (isActive && spoofSecPatch.isNotEmpty()) spoofSecPatch else realInfo.securityPatch

    val inputStyle = androidx.compose.ui.text.TextStyle(
        color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    val inputMod = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(Surface)
        .border(1.dp, Border, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp)

    @Composable
    fun InputField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = inputMod,
            textStyle = inputStyle,
            singleLine = true,
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = TextSec, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                inner()
            },
            cursorBrush = SolidColor(Violet)
        )
    }

    @Composable
    fun InfoRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextMid, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = TextMid.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0AFFFFFF)))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status badge
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) GreenOk else YellWarn)
            )
            Text(if (isActive) "Spoof active" else "Spoof inactive",
                color = if (isActive) GreenOk else YellWarn,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        // Current device info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Text("Current Device Info", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                InfoRow("Model",          effModel)
                InfoRow("Brand",          effBrand)
                InfoRow("Manufacturer",   effMfr)
                InfoRow("Android",        effAndroid)
                InfoRow("Build ID",       effBuild)
                InfoRow("Security Patch", effPatch)
            }
        }

        Text("Model", color = TextMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        InputField(spoofModel, "Model e.g. ${realInfo.model}") { spoofModel = it }

        Text("Brand", color = TextMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        // Brand chips
        val brandRows = TOP_BRANDS.chunked(5)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            brandRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { brand ->
                        val isSel = spoofBrand == brand
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (isSel) Violet else Surface)
                                .border(1.dp, if (isSel) Violet else Color.Transparent, RoundedCornerShape(50))
                                .clickable { spoofBrand = brand; spoofMfr = brand }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(brand, color = if (isSel) Color.White else Color(0xAAFFFFFF), fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        Text("Android Version", color = TextMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        val verRows = ANDROID_VERSIONS.chunked(4)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            verRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { ver ->
                        val isSel = spoofAndroid == ver
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSel) Color(0x226C63FF) else Surface)
                                .border(1.dp, if (isSel) Violet else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { spoofAndroid = ver }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(ver, color = if (isSel) Violet else Color(0xAAFFFFFF),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Text("Build ID", color = TextMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        InputField(spoofBuildId, "Build ID e.g. ${realInfo.buildId}") { spoofBuildId = it }

        Text("Security Patch", color = TextMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        InputField(spoofSecPatch, "Security Patch YYYY-MM-DD") { spoofSecPatch = it }

        Text("Device ID (override)", color = TextMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        InputField(spoofDeviceId, "Custom Device ID") { spoofDeviceId = it }

        Text("Serial (override)", color = TextMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        InputField(spoofSerial, "Custom Serial") { spoofSerial = it }

        // Buttons row
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Violet, Pink)))
                    .clickable {
                        if (spoofModel.isEmpty() && spoofBrand.isEmpty() && spoofMfr.isEmpty() &&
                            spoofAndroid.isEmpty() && spoofBuildId.isEmpty() && spoofSecPatch.isEmpty() &&
                            spoofDeviceId.isEmpty() && spoofSerial.isEmpty()) {
                            Toast.makeText(context, "Enter at least one spoof value", Toast.LENGTH_SHORT).show()
                            return@clickable
                        }
                        SharedPrefs.setSpoofModel(spoofModel.ifEmpty { null })
                        SharedPrefs.setSpoofBrand(spoofBrand.ifEmpty { null })
                        SharedPrefs.setSpoofManufacturer(spoofMfr.ifEmpty { null })
                        SharedPrefs.setSpoofAndroid(spoofAndroid.ifEmpty { null })
                        SharedPrefs.setSpoofBuildId(spoofBuildId.ifEmpty { null })
                        SharedPrefs.setSpoofSecurityPatch(spoofSecPatch.ifEmpty { null })
                        SharedPrefs.setSpoofDeviceId(spoofDeviceId.ifEmpty { null })
                        SharedPrefs.setSpoofSerial(spoofSerial.ifEmpty { null })
                        SharedPrefs.setSpoofActive(true)
                        isActive = true
                        saved = true
                        Toast.makeText(context, "Device spoof applied!", Toast.LENGTH_SHORT).show()
                    },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(targetState = saved, label = "applyBtn") { isSaved ->
                    if (isSaved) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("✔", color = Color.White, fontSize = 12.sp)
                            Text("Applied!", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    } else {
                        Text("Apply Spoof", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface)
                    .clickable {
                        spoofModel = ""; spoofBrand = ""; spoofMfr = ""; spoofAndroid = ""
                        spoofBuildId = ""; spoofSecPatch = ""; spoofDeviceId = ""; spoofSerial = ""
                        SharedPrefs.setSpoofModel(null); SharedPrefs.setSpoofBrand(null)
                        SharedPrefs.setSpoofManufacturer(null); SharedPrefs.setSpoofAndroid(null)
                        SharedPrefs.setSpoofBuildId(null); SharedPrefs.setSpoofSecurityPatch(null)
                        SharedPrefs.setSpoofDeviceId(null); SharedPrefs.setSpoofSerial(null)
                        SharedPrefs.setSpoofActive(false); isActive = false
                        Toast.makeText(context, "Spoof reset to device defaults", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text("Reset", color = TextMid, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
