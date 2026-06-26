package com.itsme.amkush.hooks

import android.os.Build
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object DeviceSpoofHooks {

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!AppState.isHookingActive) {
            return
        }

        if (!SharedPrefs.isSpoofActive()) {
            Logger.d("Device spoofing not active, skipping")
            return
        }

        try {
            hookBuildClass(lpparam)
            hookSystemProperties(lpparam)
            hookTelephonyManager(lpparam)
            hookSettingsSecure(lpparam)
            Logger.d("Device spoof hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Device spoof hooks failed", e)
        }
    }

    private fun hookBuildClass(lpparam: XC_LoadPackage.LoadPackageParam) {
        val buildClass = XposedHelpers.findClass("android.os.Build", lpparam.classLoader)

        try {
            SharedPrefs.getSpoofModel()?.let {
                XposedHelpers.setStaticObjectField(buildClass, "MODEL", it)
                Logger.d("Build.MODEL spoofed to: $it")
            }
        } catch (e: Throwable) {
            Logger.e("Failed to spoof Build.MODEL", e)
        }

        try {
            SharedPrefs.getSpoofBrand()?.let {
                XposedHelpers.setStaticObjectField(buildClass, "BRAND", it)
                Logger.d("Build.BRAND spoofed to: $it")
            }
        } catch (e: Throwable) {
            Logger.e("Failed to spoof Build.BRAND", e)
        }

        try {
            SharedPrefs.getSpoofManufacturer()?.let {
                XposedHelpers.setStaticObjectField(buildClass, "MANUFACTURER", it)
                Logger.d("Build.MANUFACTURER spoofed to: $it")
            }
        } catch (e: Throwable) {
            Logger.e("Failed to spoof Build.MANUFACTURER", e)
        }

        try {
            SharedPrefs.getSpoofBuildId()?.let {
                XposedHelpers.setStaticObjectField(buildClass, "ID", it)
                Logger.d("Build.ID spoofed to: $it")
            }
        } catch (e: Throwable) {
            Logger.e("Failed to spoof Build.ID", e)
        }

        try {
            SharedPrefs.getSpoofAndroid()?.let {
                val versionClass = XposedHelpers.findClass("android.os.Build\$VERSION", lpparam.classLoader)
                XposedHelpers.setStaticObjectField(versionClass, "RELEASE", it)
                Logger.d("Build.VERSION.RELEASE spoofed to: $it")
            }
        } catch (e: Throwable) {
            Logger.e("Failed to spoof Build.VERSION.RELEASE", e)
        }

        SharedPrefs.getSpoofAndroid()?.let { spoofAndroid ->
            try {
                val sdkInt = when (spoofAndroid.toIntOrNull()) {
                    10 -> 29
                    11 -> 30
                    12 -> 31
                    13 -> 33
                    14 -> 34
                    15 -> 35
                    else -> Build.VERSION.SDK_INT
                }
                val versionClass = XposedHelpers.findClass("android.os.Build\$VERSION", lpparam.classLoader)
                XposedHelpers.setStaticIntField(versionClass, "SDK_INT", sdkInt)
                Logger.d("Build.VERSION.SDK_INT spoofed to: $sdkInt")
            } catch (e: Throwable) {
                Logger.e("Failed to spoof Build.VERSION.SDK_INT", e)
            }
        }

        SharedPrefs.getSpoofSecurityPatch()?.let {
            try {
                val versionClass = XposedHelpers.findClass("android.os.Build\$VERSION", lpparam.classLoader)
                XposedHelpers.setStaticObjectField(versionClass, "SECURITY_PATCH", it)
                Logger.d("Build.VERSION.SECURITY_PATCH spoofed to: $it")
            } catch (e: Throwable) {
                Logger.e("Failed to spoof Build.VERSION.SECURITY_PATCH", e)
            }
        }

        SharedPrefs.getSpoofSerial()?.let {
            try {
                XposedHelpers.setStaticObjectField(buildClass, "SERIAL", it)
                Logger.d("Build.SERIAL spoofed to: $it")
            } catch (e: Throwable) {
                Logger.e("Failed to spoof Build.SERIAL", e)
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                buildClass,
                "getSerial",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        SharedPrefs.getSpoofSerial()?.let {
                            param.result = it
                            Logger.d("Build.getSerial spoofed to: $it")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Method may not exist on older Android versions
        }
    }

    private fun hookSystemProperties(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val systemPropertiesClass = XposedHelpers.findClass(
                "android.os.SystemProperties",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                systemPropertiesClass,
                "get",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String
                        when (key) {
                            "ro.product.model" -> {
                                SharedPrefs.getSpoofModel()?.let {
                                    param.result = it
                                    Logger.d("SystemProperties.get(ro.product.model) -> $it")
                                }
                            }
                            "ro.product.brand" -> {
                                SharedPrefs.getSpoofBrand()?.let {
                                    param.result = it
                                }
                            }
                            "ro.product.manufacturer" -> {
                                SharedPrefs.getSpoofManufacturer()?.let {
                                    param.result = it
                                }
                            }
                            "ro.build.id" -> {
                                SharedPrefs.getSpoofBuildId()?.let {
                                    param.result = it
                                }
                            }
                            "ro.build.version.release" -> {
                                SharedPrefs.getSpoofAndroid()?.let {
                                    param.result = it
                                }
                            }
                            "ro.build.version.security_patch" -> {
                                SharedPrefs.getSpoofSecurityPatch()?.let {
                                    param.result = it
                                }
                            }
                            "ro.serialno" -> {
                                SharedPrefs.getSpoofSerial()?.let {
                                    param.result = it
                                }
                            }
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                systemPropertiesClass,
                "get",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String
                        when (key) {
                            "ro.product.model" -> {
                                SharedPrefs.getSpoofModel()?.let {
                                    param.result = it
                                }
                            }
                            "ro.product.brand" -> {
                                SharedPrefs.getSpoofBrand()?.let {
                                    param.result = it
                                }
                            }
                            "ro.product.manufacturer" -> {
                                SharedPrefs.getSpoofManufacturer()?.let {
                                    param.result = it
                                }
                            }
                            "ro.build.id" -> {
                                SharedPrefs.getSpoofBuildId()?.let {
                                    param.result = it
                                }
                            }
                            "ro.build.version.release" -> {
                                SharedPrefs.getSpoofAndroid()?.let {
                                    param.result = it
                                }
                            }
                            "ro.build.version.security_patch" -> {
                                SharedPrefs.getSpoofSecurityPatch()?.let {
                                    param.result = it
                                }
                            }
                            "ro.serialno" -> {
                                SharedPrefs.getSpoofSerial()?.let {
                                    param.result = it
                                }
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e("SystemProperties hook failed", e)
        }
    }

    private fun hookTelephonyManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val telephonyManagerClass = XposedHelpers.findClass(
                "android.telephony.TelephonyManager",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                telephonyManagerClass,
                "getDeviceId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        SharedPrefs.getSpoofDeviceId()?.let {
                            param.result = it
                            Logger.d("TelephonyManager.getDeviceId spoofed to: $it")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                telephonyManagerClass,
                "getImei",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        SharedPrefs.getSpoofDeviceId()?.let {
                            param.result = it
                            Logger.d("TelephonyManager.getImei spoofed to: $it")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                telephonyManagerClass,
                "getDeviceSoftwareVersion",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = "00"
                        Logger.d("TelephonyManager.getDeviceSoftwareVersion spoofed")
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                telephonyManagerClass,
                "getSimSerialNumber",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        SharedPrefs.getSpoofSerial()?.let {
                            param.result = it
                            Logger.d("TelephonyManager.getSimSerialNumber spoofed to: $it")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                telephonyManagerClass,
                "getSubscriberId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        SharedPrefs.getSpoofDeviceId()?.let {
                            param.result = it
                            Logger.d("TelephonyManager.getSubscriberId spoofed to: $it")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e("TelephonyManager hook failed", e)
        }
    }

    private fun hookSettingsSecure(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val settingsSecureClass = XposedHelpers.findClass(
                "android.provider.Settings\$Secure",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                settingsSecureClass,
                "getString",
                android.content.ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String
                        if (key == "android_id") {
                            SharedPrefs.getSpoofDeviceId()?.let {
                                param.result = it
                                Logger.d("Settings.Secure.getString(android_id) spoofed to: $it")
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e("Settings.Secure hook failed", e)
        }
    }
}