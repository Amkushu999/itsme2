package com.itsme.amkush.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface
import java.util.*

object DeviceUtils {

    /**
     * Get unique device ID (Android ID)
     */
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        return androidId ?: "UNKNOWN_DEVICE"
    }

    /**
     * Get formatted device ID (4 groups of 4 hex chars)
     */
    fun getFormattedDeviceId(context: Context): String {
        val id = getDeviceId(context)
        return if (id.length >= 16) {
            "${id.substring(0, 4)}-${id.substring(4, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}"
        } else {
            id
        }
    }

    /**
     * Get WiFi IP address
     */
    fun getWifiIpAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ip = wifiInfo?.ipAddress ?: return null
            return String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            Logger.e("Error getting WiFi IP", e)
            return null
        }
    }

    /**
     * Get MAC address
     */
    fun getMacAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val mac = networkInterface.hardwareAddress
                if (mac != null && mac.isNotEmpty()) {
                    val sb = StringBuilder()
                    for (i in mac.indices) {
                        sb.append(String.format("%02X", mac[i]))
                        if (i < mac.size - 1) sb.append(":")
                    }
                    return sb.toString()
                }
            }
        } catch (e: Exception) {
            Logger.e("Error getting MAC address", e)
        }
        return null
    }

    /**
     * Get device model
     */
    fun getDeviceModel(): String {
        return Build.MODEL
    }

    /**
     * Get device manufacturer
     */
    fun getDeviceManufacturer(): String {
        return Build.MANUFACTURER
    }

    /**
     * Get device brand
     */
    fun getDeviceBrand(): String {
        return Build.BRAND
    }

    /**
     * Get Android version
     */
    fun getAndroidVersion(): String {
        return Build.VERSION.RELEASE
    }

    /**
     * Get build ID
     */
    fun getBuildId(): String {
        return Build.ID
    }

    /**
     * Get security patch level
     */
    fun getSecurityPatch(): String {
        return Build.VERSION.SECURITY_PATCH ?: "Unknown"
    }

    /**
     * Get complete device info
     */
    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            model = getDeviceModel(),
            brand = getDeviceBrand(),
            manufacturer = getDeviceManufacturer(),
            androidVersion = getAndroidVersion(),
            buildId = getBuildId(),
            securityPatch = getSecurityPatch(),
            sdkVersion = Build.VERSION.SDK_INT
        )
    }

    /**
     * Data class for device information
     */
    data class DeviceInfo(
        val model: String,
        val brand: String,
        val manufacturer: String,
        val androidVersion: String,
        val buildId: String,
        val securityPatch: String,
        val sdkVersion: Int
    )
}