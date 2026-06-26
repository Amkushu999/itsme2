package com.itsme.amkush.hooks

import android.os.Build
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.net.NetworkInterface

object EmulatorBypassHooks {

    private val emulatorProperties = listOf(
        "ro.kernel.qemu", "ro.kernel.qemu.gles", "ro.kernel.qemu.gltransport",
        "ro.boot.qemu", "ro.kernel.android.qemud", "ro.kernel.android.checkjni",
        "ro.kernel.android.gps", "ro.kernel.android.ril", "ro.kernel.android.tty",
        "ro.hardware", "ro.product.cpu.abi", "ro.product.manufacturer",
        "ro.product.model", "ro.product.name", "ro.product.device",
        "ro.build.product", "ro.build.fingerprint", "ro.build.characteristics"
    )

    private val emulatorPaths = listOf(
        "/system/bin/qemu-prop", "/dev/socket/qemud",
        "/dev/qemu_pipe", "/sys/qemu_trace", "/proc/tty/drivers",
        "/data/.emulator", "/sdcard/.emulator", "/system/etc/init.goldfish.sh"
    )

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!AppState.isHookingActive) {
            return
        }

        try {
            hookSystemProperties(lpparam)
            hookBuildFields(lpparam)
            hookFileExists(lpparam)
            hookNetworkInterfaces(lpparam)
            Logger.d("Emulator bypass hooks installed")
        } catch (e: Throwable) {
            Logger.e("Emulator bypass hooks failed", e)
        }
    }

    private fun hookSystemProperties(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val systemPropertiesClass = XposedHelpers.findClass(
                "android.os.SystemProperties",
                lpparam.classLoader
            )

            val realProperties = getRealDeviceProperties()

            XposedHelpers.findAndHookMethod(
                systemPropertiesClass,
                "get",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (emulatorProperties.any { key.contains(it) }) {
                            val realValue = realProperties[key]
                            if (realValue != null) {
                                param.result = realValue
                            } else {
                                when {
                                    key.contains("qemu") -> param.result = ""
                                    key.contains("emulator") -> param.result = ""
                                    key == "ro.hardware" -> param.result = "raven"
                                    key == "ro.product.manufacturer" -> param.result = "Google"
                                    key == "ro.product.model" -> param.result = "Pixel 6 Pro"
                                    key == "ro.build.fingerprint" -> param.result = "google/raven/raven:12/SQ3A.220605.009.A1/123456:user/release-keys"
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
                        val key = param.args[0] as? String ?: return
                        if (emulatorProperties.any { key.contains(it) }) {
                            val realValue = realProperties[key]
                            if (realValue != null) {
                                param.result = realValue
                            }
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("SystemProperties hook failed", e)
        }
    }

    private fun hookBuildFields(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val buildClass = XposedHelpers.findClass("android.os.Build", lpparam.classLoader)

            val buildValues = mapOf(
                "MODEL" to "Pixel 6 Pro",
                "MANUFACTURER" to "Google",
                "BRAND" to "google",
                "DEVICE" to "raven",
                "PRODUCT" to "raven",
                "HARDWARE" to "raven",
                "FINGERPRINT" to "google/raven/raven:12/SQ3A.220605.009.A1/123456:user/release-keys",
                "BOARD" to "raven",
                "BOOTLOADER" to "raven-1.0-123456",
                "DISPLAY" to "SQ3A.220605.009.A1",
                "HOST" to "android-build",
                "ID" to "SQ3A.220605.009.A1",
                "TAGS" to "release-keys",
                "TYPE" to "user",
                "USER" to "android-build"
            )

            for ((field, value) in buildValues) {
                try {
                    XposedHelpers.setStaticObjectField(buildClass, field, value)
                } catch (e: Throwable) {
                    // Field may not exist
                }
            }

            try {
                val versionClass = XposedHelpers.findClass("android.os.Build\$VERSION", lpparam.classLoader)
                XposedHelpers.setStaticObjectField(versionClass, "RELEASE", "12")
                XposedHelpers.setStaticIntField(versionClass, "SDK_INT", 32)
                XposedHelpers.setStaticObjectField(versionClass, "SECURITY_PATCH", "2024-03-01")
            } catch (e: Throwable) {
                // Ignore
            }

            Logger.d("Build fields spoofed")

        } catch (e: Throwable) {
            Logger.e("Build fields hook failed", e)
        }
    }

    private fun hookFileExists(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fileClass = XposedHelpers.findClass("java.io.File", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                fileClass,
                "exists",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath

                        if (emulatorPaths.any { path.contains(it) }) {
                            param.result = false
                            Logger.d("Blocked emulator file check: $path")
                        }

                        if (path.contains("/sys/class/net/eth0") && param.result == true) {
                            param.result = false
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("File.exists hook failed", e)
        }
    }

    private fun hookNetworkInterfaces(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val networkInterfaceClass = XposedHelpers.findClass(
                "java.net.NetworkInterface",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                networkInterfaceClass,
                "getNetworkInterfaces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? java.util.Enumeration<*>
                        if (result != null) {
                            val filtered = mutableListOf<Any>()
                            while (result.hasMoreElements()) {
                                val ni = result.nextElement()
                                try {
                                    val name = ni.javaClass.getMethod("getName").invoke(ni) as? String
                                    if (name != "eth0" && name != "qemunet") {
                                        filtered.add(ni)
                                    }
                                } catch (e: Throwable) {
                                    filtered.add(ni)
                                }
                            }
                            param.result = java.util.Collections.enumeration(filtered)
                            Logger.d("Network interfaces filtered")
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("NetworkInterface hook failed", e)
        }
    }

    private fun getRealDeviceProperties(): Map<String, String> {
        return mapOf(
            "ro.hardware" to "raven",
            "ro.product.manufacturer" to "Google",
            "ro.product.model" to "Pixel 6 Pro",
            "ro.product.brand" to "google",
            "ro.product.device" to "raven",
            "ro.product.name" to "raven",
            "ro.build.fingerprint" to "google/raven/raven:12/SQ3A.220605.009.A1/123456:user/release-keys",
            "ro.build.characteristics" to "default",
            "ro.product.cpu.abi" to "arm64-v8a"
        )
    }
}