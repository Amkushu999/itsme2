package com.itsme.amkush.hooks

import android.content.pm.PackageManager
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.IOException
import java.lang.reflect.Method

object RootBypassHooks {

    private val rootPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/system/app/Superuser.apk",
        "/sbin/su", "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
        "/magisk/", "/data/adb/magisk/", "/sbin/magisk",
        "/system/bin/magisk", "/system/xbin/magisk", "/sbin/.magisk",
        "/data/magisk", "/data/adb/su", "/system/bin/.ext/.su"
    )

    private val rootPackages = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.keramidas.TitaniumBackup",
        "com.stericson.RootTools"
    )

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!AppState.isHookingActive) {
            return
        }

        try {
            hookFileExists(lpparam)
            hookRuntimeExec(lpparam)
            hookPackageManager(lpparam)
            hookBuildFields(lpparam)
            hookSystemProperties(lpparam)
            Logger.d("Root bypass hooks installed")
        } catch (e: Throwable) {
            Logger.e("Root bypass hooks failed", e)
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

                        if (rootPaths.any { path.contains(it) }) {
                            param.result = false
                            Logger.d("Blocked root file check: $path")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                fileClass,
                "canExecute",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath

                        if (rootPaths.any { path.contains(it) }) {
                            param.result = false
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("File exists hook failed", e)
        }
    }

    private fun hookRuntimeExec(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)

            val execMethods = listOf(
                "exec" to arrayOf(String::class.java),
                "exec" to arrayOf(String::class.java, StringArray::class.java),
                "exec" to arrayOf(StringArray::class.java),
                "exec" to arrayOf(StringArray::class.java, StringArray::class.java)
            )

            for ((methodName, paramTypes) in execMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        runtimeClass,
                        methodName,
                        *paramTypes,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val cmd = getCommandFromArgs(param.args)
                                if (cmd != null && isRootCommand(cmd)) {
                                    Logger.d("Blocked root command: $cmd")
                                    throw IOException("Command not found")
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    // Method may not exist
                }
            }

            // Hook ProcessBuilder
            try {
                val processBuilderClass = XposedHelpers.findClass(
                    "java.lang.ProcessBuilder",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    processBuilderClass,
                    "start",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val pb = param.thisObject
                            try {
                                val command = pb.javaClass.getMethod("command").invoke(pb) as? List<*>
                                val cmdStr = command?.joinToString(" ") ?: ""
                                if (isRootCommand(cmdStr)) {
                                    Logger.d("Blocked ProcessBuilder root command: $cmdStr")
                                    throw IOException("Command not found")
                                }
                            } catch (e: Throwable) {
                                // Ignore
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                // ProcessBuilder may not exist or method may not exist
            }

        } catch (e: Throwable) {
            Logger.e("Runtime.exec hook failed", e)
        }
    }

    private fun hookPackageManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                pmClass,
                "getInstalledApplications",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        filterPackages(param)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                pmClass,
                "getInstalledPackages",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        filterPackages(param)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                pmClass,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String
                        if (packageName != null && rootPackages.contains(packageName)) {
                            param.result = null
                            Logger.d("Blocked package info request: $packageName")
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("PackageManager hook failed", e)
        }
    }

    private fun filterPackages(param: XC_MethodHook.MethodHookParam) {
        try {
            val result = param.result as? List<*>
            if (result != null) {
                val filtered = result.filter { item ->
                    val pkgName = getPackageNameFromItem(item!!)
                    !rootPackages.contains(pkgName)
                }
                param.result = filtered
                Logger.d("Filtered root packages")
            }
        } catch (e: Throwable) {
            Logger.e("Failed to filter packages", e)
        }
    }

    private fun hookBuildFields(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val buildClass = XposedHelpers.findClass("android.os.Build", lpparam.classLoader)

            try {
                XposedHelpers.setStaticObjectField(buildClass, "TAGS", "release-keys")
                XposedHelpers.setStaticObjectField(buildClass, "TYPE", "user")
                XposedHelpers.setStaticObjectField(buildClass, "FINGERPRINT", "google/raven/raven:12/SQ3A.220605.009.A1/123456:user/release-keys")
            } catch (e: Throwable) {
                // Fields may not exist
            }

            Logger.d("Build fields spoofed for root bypass")

        } catch (e: Throwable) {
            Logger.e("Build fields hook failed", e)
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
                        val key = param.args[0] as? String ?: return
                        if (key == "ro.build.tags" || key == "ro.build.type") {
                            param.result = if (key == "ro.build.tags") "release-keys" else "user"
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("SystemProperties hook failed", e)
        }
    }

    private fun getCommandFromArgs(args: Array<Any>?): String? {
        if (args == null || args.isEmpty()) return null
        return when (val first = args[0]) {
            is String -> first
            is Array<*> -> first.joinToString(" ")
            else -> null
        }
    }

    private fun isRootCommand(cmd: String): Boolean {
        val lower = cmd.lowercase().trim()
        val tokens = lower.split(Regex("\\s+"))
        // Match "su" only as a standalone token or known root paths — avoid false
        // positives from words that merely contain "su" (e.g. "audio", "issue").
        return tokens[0] == "su" ||
                lower.contains("/bin/su") ||
                lower.contains("/xbin/su") ||
                lower.contains("magisk") ||
                lower.contains("which su") ||
                lower.contains("busybox") ||
                lower.contains("whoami") ||
                lower.contains("superuser") ||
                (tokens.contains("id") && tokens.contains("uid")) ||
                lower.contains("mount") && lower.contains("system") ||
                lower.contains("cat") && lower.contains("/su") ||
                lower.contains("ls") && lower.contains("/su")
    }

    private fun getPackageNameFromItem(item: Any): String {
        return try {
            val method = item.javaClass.getMethod("packageName")
            method.invoke(item) as? String ?: ""
        } catch (e: Throwable) {
            try {
                val field = item.javaClass.getField("packageName")
                field.get(item) as? String ?: ""
            } catch (e2: Throwable) {
                ""
            }
        }
    }
}

private typealias StringArray = Array<String>