package com.itsme.amkush.hooks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.text.SimpleDateFormat
import java.util.*

object ExifSpoofHooks {

    private const val TAG = "FaceGate"

    // Cache for real device GPS
    private var cachedLatitude: String? = null
    private var cachedLongitude: String? = null
    private var cachedAltitude: String? = null
    private var cachedGpsDate: String? = null
    private var cachedGpsTime: String? = null
    private var lastGpsUpdate: Long = 0
    private val GPS_CACHE_MS = 60000 // 1 minute

    // Device info cache
    private var cachedDeviceMake: String? = null
    private var cachedDeviceModel: String? = null

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!AppState.isHookingActive) {
            return
        }

        try {
            // Initialize device info cache
            initDeviceInfo()

            hookExifInterface(lpparam)
            hookGetAttribute(lpparam)
            hookGetAttributeInt(lpparam)
            hookGetAttributeDouble(lpparam)
            hookSetAttribute(lpparam)
            hookSaveAttributes(lpparam)
            hookHasThumbnail(lpparam)
            hookGetThumbnail(lpparam)
            hookGetLatLong(lpparam)
            hookSetLatLong(lpparam)
            Logger.d("EXIF spoof hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("EXIF spoof hooks failed", e)
        }
    }

    private fun initDeviceInfo() {
        cachedDeviceMake = getDeviceMakeForExif()
        cachedDeviceModel = getDeviceModelForExif()
        Logger.d("Device info for EXIF: Make=$cachedDeviceMake, Model=$cachedDeviceModel")
    }

    private fun getDeviceMakeForExif(): String {
        // Priority 1: User spoofed value
        if (SharedPrefs.isSpoofActive()) {
            SharedPrefs.getSpoofBrand()?.let { return it }
            SharedPrefs.getSpoofManufacturer()?.let { return it }
        }

        // Priority 2: Real device (if not emulator)
        if (!isEmulator()) {
            return Build.MANUFACTURER.ifEmpty { "Google" }
        }

        // Priority 3: Safe default for emulator
        return "Google"
    }

    private fun getDeviceModelForExif(): String {
        // Priority 1: User spoofed value
        if (SharedPrefs.isSpoofActive()) {
            SharedPrefs.getSpoofModel()?.let { return it }
        }

        // Priority 2: Real device (if not emulator)
        if (!isEmulator()) {
            return Build.MODEL.ifEmpty { "Pixel 7 Pro" }
        }

        // Priority 3: Safe default for emulator
        return "Pixel 7 Pro"
    }

    private fun isEmulator(): Boolean {
        // Check common emulator indicators
        val fingerprint = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val hardware = Build.HARDWARE ?: ""
        val product = Build.PRODUCT ?: ""

        // Google emulator
        if (fingerprint.contains("generic") || fingerprint.contains("vbox") ||
            fingerprint.contains("test-keys") || model.contains("sdk") ||
            hardware.contains("goldfish") || hardware.contains("ranchu") ||
            product.contains("sdk") || manufacturer.equals("unknown", ignoreCase = true)) {
            return true
        }

        // Genymotion
        if (manufacturer.equals("genymotion", ignoreCase = true) ||
            hardware.contains("vbox")) {
            return true
        }

        return false
    }

    private fun hookExifInterface(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            try {
                XposedHelpers.findAndHookMethod(
                    exifClass,
                    "init",
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            refreshDeviceGps()
                            initDeviceInfo()
                            Logger.d("ExifInterface initialized, GPS and device info refreshed")
                        }
                    }
                )
            } catch (e: Throwable) {
                // Constructor may not exist on all versions
            }

        } catch (e: Throwable) {
            Logger.e("ExifInterface hook failed", e)
        }
    }

    private fun hookGetAttribute(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "getAttribute",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tag = param.args[0] as? String ?: return
                        val originalValue = param.result as? String

                        val spoofedValue = getSpoofedExifValueString(tag, originalValue)
                        if (spoofedValue != null && spoofedValue != originalValue) {
                            param.result = spoofedValue
                            Logger.d("Spoofed EXIF tag: $tag = $spoofedValue (original: $originalValue)")
                        }
                    }
                }
            )

            try {
                XposedHelpers.findAndHookMethod(
                    exifClass,
                    "getAttributeString",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val tag = param.args[0] as? String ?: return
                            val originalValue = param.result as? String

                            val spoofedValue = getSpoofedExifValueString(tag, originalValue)
                            if (spoofedValue != null && spoofedValue != originalValue) {
                                param.result = spoofedValue
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                // Method may not exist on older Android versions
            }

        } catch (e: Throwable) {
            Logger.e("ExifInterface.getAttribute hook failed", e)
        }
    }

    private fun hookGetAttributeInt(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "getAttributeInt",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tag = param.args[0] as? String ?: return
                        val defaultValue = param.args[1] as? Int ?: 0
                        val originalValue = param.result as? Int ?: defaultValue

                        val spoofedValue = getSpoofedExifValueInt(tag, originalValue)
                        if (spoofedValue != originalValue) {
                            param.result = spoofedValue
                            Logger.d("Spoofed EXIF int tag: $tag = $spoofedValue (original: $originalValue)")
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("ExifInterface.getAttributeInt hook failed", e)
        }
    }

    private fun hookGetAttributeDouble(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "getAttributeDouble",
                String::class.java,
                Double::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tag = param.args[0] as? String ?: return
                        val defaultValue = param.args[1] as? Double ?: 0.0
                        val originalValue = param.result as? Double ?: defaultValue

                        val spoofedValue = getSpoofedExifValueDouble(tag, originalValue)
                        if (spoofedValue != originalValue) {
                            param.result = spoofedValue
                            Logger.d("Spoofed EXIF double tag: $tag = $spoofedValue (original: $originalValue)")
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("ExifInterface.getAttributeDouble hook failed", e)
        }
    }

    private fun hookSetAttribute(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "setAttribute",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tag = param.args[0] as? String ?: return
                        val value = param.args[1] as? String

                        when (tag) {
                            "ImageUniqueID", "GPSProcessingMethod", "MakerNote" -> {
                                param.args[1] = null
                                Logger.d("Blocked writing EXIF tag: $tag")
                                return
                            }
                            "Make", "Model", "LensMake", "LensModel" -> {
                                param.args[1] = getSpoofedExifValueString(tag, value)
                                Logger.d("Overrode EXIF tag: $tag = ${param.args[1]}")
                                return
                            }
                            "DateTime", "DateTimeOriginal", "DateTimeDigitized" -> {
                                param.args[1] = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                                Logger.d("Overrode EXIF date tag: $tag = ${param.args[1]}")
                                return
                            }
                            "Orientation" -> {
                                param.args[1] = "1"
                                Logger.d("Overrode EXIF Orientation: ${param.args[1]}")
                                return
                            }
                        }
                    }
                }
            )

            try {
                XposedHelpers.findAndHookMethod(
                    exifClass,
                    "setAttributeInt",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val tag = param.args[0] as? String ?: return
                            when (tag) {
                                "Orientation" -> {
                                    param.args[1] = 1
                                    Logger.d("Overrode EXIF Orientation int: ${param.args[1]}")
                                }
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                // Method may not exist on older versions
            }

        } catch (e: Throwable) {
            Logger.e("ExifInterface.setAttribute hook failed", e)
        }
    }

    private fun hookSaveAttributes(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "saveAttributes",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logger.d("EXIF attributes being saved - preserving spoofed values")
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("ExifInterface.saveAttributes hook failed", e)
        }
    }

    private fun hookHasThumbnail(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "hasThumbnail",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = true
                        Logger.d("ExifInterface.hasThumbnail spoofed to true")
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("ExifInterface.hasThumbnail hook failed", e)
        }
    }

    private fun hookGetThumbnail(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "getThumbnail",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logger.d("ExifInterface.getThumbnail called")
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "getThumbnailBitmap",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logger.d("ExifInterface.getThumbnailBitmap called")
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("ExifInterface.getThumbnail hook failed", e)
        }
    }

    private fun hookGetLatLong(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "getLatLong",
                FloatArray::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val output = param.args[0] as? FloatArray
                        if (output != null && output.size >= 2) {
                            refreshDeviceGps()
                            val lat = cachedLatitude?.let { parseGpsCoordinate(it) } ?: 37.4238f
                            val lng = cachedLongitude?.let { parseGpsCoordinate(it) } ?: -122.0839f
                            output[0] = lat
                            output[1] = lng
                            Logger.d("Spoofed getLatLong: lat=$lat, lng=$lng")
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("ExifInterface.getLatLong hook failed", e)
        }
    }

    private fun hookSetLatLong(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val exifClass = XposedHelpers.findClass(
                "androidx.exifinterface.media.ExifInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                exifClass,
                "setLatLong",
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        refreshDeviceGps()
                        val lat = cachedLatitude?.let { parseGpsCoordinate(it) } ?: 37.4238
                        val lng = cachedLongitude?.let { parseGpsCoordinate(it) } ?: -122.0839
                        param.args[0] = lat
                        param.args[1] = lng
                        Logger.d("Spoofed setLatLong: lat=$lat, lng=$lng")
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("ExifInterface.setLatLong hook failed", e)
        }
    }

    private fun refreshDeviceGps() {
        val now = System.currentTimeMillis()
        if (now - lastGpsUpdate < GPS_CACHE_MS && cachedLatitude != null) {
            return
        }

        try {
            val context = AppState.context
            if (context == null) {
                Logger.d("Context not available for GPS refresh")
                setDefaultGps()
                return
            }

            val hasFineLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFineLocation && !hasCoarseLocation) {
                Logger.d("No location permissions, using default GPS spoof")
                setDefaultGps()
                return
            }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager == null) {
                Logger.d("LocationManager not available")
                setDefaultGps()
                return
            }

            var bestLocation: Location? = null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            for (provider in providers) {
                try {
                    val location = locationManager.getLastKnownLocation(provider)
                    if (location != null) {
                        if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                            bestLocation = location
                        }
                    }
                } catch (e: SecurityException) {
                    // Skip if permission denied
                }
            }

            if (bestLocation != null) {
                val latitude = bestLocation.latitude
                val longitude = bestLocation.longitude
                val altitude = bestLocation.altitude

                cachedLatitude = formatGpsCoordinate(latitude)
                cachedLongitude = formatGpsCoordinate(longitude)
                cachedAltitude = formatGpsAltitude(altitude)

                val calendar = Calendar.getInstance()
                cachedGpsDate = SimpleDateFormat("yyyy:MM:dd", Locale.US).format(calendar.time)
                cachedGpsTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(calendar.time)

                lastGpsUpdate = System.currentTimeMillis()
                Logger.d("GPS updated: lat=$latitude, lng=$longitude, alt=$altitude")
            } else {
                Logger.d("No GPS location available, using default")
                setDefaultGps()
            }

        } catch (e: Exception) {
            Logger.e("Failed to refresh GPS", e)
            setDefaultGps()
        }
    }

    private fun setDefaultGps() {
        cachedLatitude = "37/1,25/1,21/100"
        cachedLongitude = "122/1,4/1,2/100"
        cachedAltitude = "100/1"
        val calendar = Calendar.getInstance()
        cachedGpsDate = SimpleDateFormat("yyyy:MM:dd", Locale.US).format(calendar.time)
        cachedGpsTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(calendar.time)
        lastGpsUpdate = System.currentTimeMillis()
        Logger.d("Default GPS set to Google HQ")
    }

    private fun formatGpsCoordinate(coordinate: Double): String {
        val degrees = coordinate.toInt()
        val minutes = ((coordinate - degrees) * 60).toInt()
        val seconds = ((coordinate - degrees - minutes / 60.0) * 3600 * 100).toInt()
        return "$degrees/1,$minutes/1,$seconds/100"
    }

    private fun formatGpsAltitude(altitude: Double): String {
        return "${altitude.toInt()}/1"
    }

    private fun parseGpsCoordinate(coordinate: String): Float {
        try {
            val parts = coordinate.split(",")
            if (parts.size == 3) {
                val degrees = parts[0].split("/")[0].toInt()
                val minutes = parts[1].split("/")[0].toInt()
                val seconds = parts[2].split("/")[0].toInt()
                return degrees + (minutes / 60.0f) + (seconds / 3600.0f)
            }
        } catch (e: Exception) {
            // Ignore
        }
        return 37.4238f
    }

    private fun getSpoofedExifValueString(tag: String, currentValue: String?): String? {
        val dateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val currentTime = Date()

        return when (tag) {
            // Device identification - USE CACHED VALUES
            "Make" -> cachedDeviceMake
            "Model" -> cachedDeviceModel
            "BodySerialNumber" -> "1234567890"
            "CameraOwnerName" -> "User"
            "LensMake" -> cachedDeviceMake
            "LensModel" -> cachedDeviceModel
            "LensSerialNumber" -> "1234567890"
            "Software" -> "Camera"

            // Date/Time
            "DateTime" -> dateFormat.format(currentTime)
            "DateTimeOriginal" -> dateFormat.format(currentTime)
            "DateTimeDigitized" -> dateFormat.format(currentTime)
            "SubSecTime" -> String.format("%03d", System.currentTimeMillis() % 1000)
            "SubSecTimeOriginal" -> String.format("%03d", System.currentTimeMillis() % 1000)
            "SubSecTimeDigitized" -> String.format("%03d", System.currentTimeMillis() % 1000)

            // Orientation
            "Orientation" -> "1"

            // Camera settings
            "ExposureTime" -> "1/100"
            "FNumber" -> "1.8"
            "ISOSpeedRatings" -> "100"
            "ISO" -> "100"
            "WhiteBalance" -> "0"
            "Flash" -> "0"
            "FocalLength" -> "4.0"
            "ApertureValue" -> "1.8"
            "ShutterSpeedValue" -> "1/100"
            "BrightnessValue" -> "0.0"
            "ExposureBiasValue" -> "0.0"
            "DigitalZoomRatio" -> "1.0"
            "ExposureMode" -> "0"
            "ExposureProgram" -> "2"
            "Contrast" -> "0"
            "Saturation" -> "0"
            "Sharpness" -> "0"
            "GainControl" -> "0"
            "CustomRendered" -> "0"
            "SceneCaptureType" -> "0"
            "SceneType" -> "1"

            // GPS - Use real device GPS
            "GPSLatitude" -> cachedLatitude ?: "37/1,25/1,21/100"
            "GPSLatitudeRef" -> "N"
            "GPSLongitude" -> cachedLongitude ?: "122/1,4/1,2/100"
            "GPSLongitudeRef" -> "W"
            "GPSAltitude" -> cachedAltitude ?: "100/1"
            "GPSAltitudeRef" -> "0"
            "GPSDateStamp" -> cachedGpsDate ?: SimpleDateFormat("yyyy:MM:dd", Locale.US).format(Date())
            "GPSTimeStamp" -> cachedGpsTime ?: SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            "GPSDOP" -> "3.0"
            "GPSMapDatum" -> "WGS-84"
            "GPSMeasureMode" -> "3"
            "GPSVersionID" -> "2.3.0.0"
            "GPSDifferential" -> "0"
            "GPSStatus" -> "A"

            // Blocked tags
            "GPSProcessingMethod" -> null
            "ImageUniqueID" -> null
            "MakerNote" -> null

            // Image metadata
            "ImageWidth" -> "4032"
            "ImageLength" -> "3024"
            "ColorSpace" -> "1"
            "SamplesPerPixel" -> "3"
            "PlanarConfiguration" -> "1"
            "ResolutionUnit" -> "2"
            "XResolution" -> "72.0"
            "YResolution" -> "72.0"
            "YCbCrPositioning" -> "1"

            // Thumbnail
            "HasThumbnail" -> "true"
            "ThumbnailOffset" -> "0"
            "ThumbnailLength" -> "0"

            // Exif version
            "ExifVersion" -> "0231"
            "FlashpixVersion" -> "0100"

            // Pass through other tags
            else -> currentValue
        }
    }

    private fun getSpoofedExifValueInt(tag: String, currentValue: Int): Int {
        return when (tag) {
            "Orientation" -> 1
            "Flash" -> 0
            "WhiteBalance" -> 0
            "ExposureMode" -> 0
            "ExposureProgram" -> 2
            "Contrast" -> 0
            "Saturation" -> 0
            "Sharpness" -> 0
            "GainControl" -> 0
            "CustomRendered" -> 0
            "SceneCaptureType" -> 0
            "SceneType" -> 1
            "ColorSpace" -> 1
            "SamplesPerPixel" -> 3
            "PlanarConfiguration" -> 1
            "ResolutionUnit" -> 2
            "YCbCrPositioning" -> 1
            "GPSAltitudeRef" -> 0
            "GPSDifferential" -> 0
            "GPSMeasureMode" -> 3
            "GPSStatus" -> 1
            "ImageWidth" -> 4032
            "ImageLength" -> 3024
            "ThumbnailLength" -> 0
            "ISOSpeedRatings" -> 100
            "ISOSpeed" -> 100
            else -> currentValue
        }
    }

    private fun getSpoofedExifValueDouble(tag: String, currentValue: Double): Double {
        return when (tag) {
            "GPSDOP" -> 3.0
            "GPSAltitude" -> cachedAltitude?.split("/")?.firstOrNull()?.toDoubleOrNull() ?: 100.0
            else -> currentValue
        }
    }
}