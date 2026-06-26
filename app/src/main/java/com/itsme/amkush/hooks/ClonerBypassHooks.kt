package com.itsme.amkush.hooks

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

object ClonerBypassHooks {

    private val clonerPaths = listOf(
        "/data/data/com.mochi.cloner",
        "/data/data/com.lbe.parallel.intl",
        "/data/data/com.parallel.space",
        "/data/data/com.excelliance.dualaid",
        "/data/data/com.bly.dkplat",
        "/data/data/com.tencent.mobileqq.msf",
        "/data/data/com.android.clone",
        "/data/data/com.dualspace.parallel",
        "/data/data/com.clone.app",
        "/data/data/com.virtual.space",
        "/data/data/com.excelliance.multiaccounts",
        "/data/data/com.app.clone",
        "/data/data/com.clone.manager",
        "/data/data/com.multi.accounts",
        "/data/data/com.parallel.pro",
        "/data/data/com.vmos.cloner",
        "/data/data/com.android.parallel",
        "/data/data/com.lbe.parallel",
        "/data/data/com.space.virtual",
        "/data/data/com.clone.space",
        "/data/data/com.mochi.virtual",
        "/data/user/0/com.mochi.cloner",
        "/data/user_de/0/com.mochi.cloner",
        "/data/data/com.whatsapp.clone",
        "/data/data/com.instagram.clone",
        "/data/data/com.facebook.clone",
        "/data/data/com.telegram.clone",
        "/data/data/com.snapchat.clone",
        "/data/data/com.tiktok.clone"
    )

    private val clonerPackages = listOf(
        "com.mochi.cloner",
        "com.lbe.parallel.intl",
        "com.parallel.space",
        "com.excelliance.dualaid",
        "com.bly.dkplat",
        "com.tencent.mobileqq.msf",
        "com.android.clone",
        "com.dualspace.parallel",
        "com.clone.app",
        "com.virtual.space",
        "com.excelliance.multiaccounts",
        "com.app.clone",
        "com.clone.manager",
        "com.multi.accounts",
        "com.parallel.pro",
        "com.vmos.cloner",
        "com.android.parallel",
        "com.lbe.parallel",
        "com.space.virtual",
        "com.clone.space",
        "com.mochi.virtual",
        "com.whatsapp.clone",
        "com.instagram.clone",
        "com.facebook.clone",
        "com.telegram.clone",
        "com.snapchat.clone",
        "com.tiktok.clone",
        "com.multi.parallel.space",
        "com.clone.multiaccounts"
    )

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!AppState.isHookingActive) {
            return
        }

        try {
            hookFileExists(lpparam)
            hookPackageManager(lpparam)
            hookApplicationInfo(lpparam)
            hookProcessInfo(lpparam)
            hookSystemProperties(lpparam)
            hookMountNamespace(lpparam)
            hookPathChecks(lpparam)
            hookRuntimeExec(lpparam)
            hookActivityThread(lpparam)
            Logger.d("Cloner bypass hooks installed")
        } catch (e: Throwable) {
            Logger.e("Cloner bypass hooks failed", e)
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

                        if (clonerPaths.any { path.contains(it) }) {
                            param.result = false
                            Logger.d("Blocked cloner file check: $path")
                        }

                        if (path.contains("/data/data/") && path.contains("/clone/")) {
                            param.result = false
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                fileClass,
                "canRead",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath

                        if (clonerPaths.any { path.contains(it) }) {
                            param.result = false
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                fileClass,
                "canWrite",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath

                        if (clonerPaths.any { path.contains(it) }) {
                            param.result = false
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                fileClass,
                "getAbsolutePath",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath

                        if (clonerPaths.any { path.contains(it) }) {
                            val originalPath = path.replace(
                                Regex("/data/data/com\\..*?\\.clone/"),
                                "/data/data/${AppState.targetPackage}/"
                            )
                            param.result = originalPath
                            Logger.d("Spoofed cloner path: $path -> $originalPath")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                fileClass,
                "getCanonicalPath",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath

                        if (clonerPaths.any { path.contains(it) }) {
                            val canonicalPath = path.replace(
                                Regex("/data/data/com\\..*?\\.clone/"),
                                "/data/data/${AppState.targetPackage}/"
                            )
                            param.result = canonicalPath
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("File.exists hook failed", e)
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
                        filterClonerPackages(param)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                pmClass,
                "getInstalledPackages",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        filterClonerPackages(param)
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
                        if (packageName != null && clonerPackages.contains(packageName)) {
                            param.result = null
                            Logger.d("Blocked cloner package info: $packageName")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                pmClass,
                "getApplicationInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String
                        if (packageName != null && clonerPackages.contains(packageName)) {
                            param.result = null
                            Logger.d("Blocked cloner application info: $packageName")
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("PackageManager hook failed", e)
        }
    }

    private fun filterClonerPackages(param: XC_MethodHook.MethodHookParam) {
        try {
            val result = param.result as? List<*>
            if (result != null) {
                val filtered = result.filter { item ->
                    val pkgName = getPackageNameFromItem(item!!)
                    !clonerPackages.contains(pkgName)
                }
                param.result = filtered
                Logger.d("Filtered cloner packages")
            }
        } catch (e: Throwable) {
            Logger.e("Failed to filter cloner packages", e)
        }
    }

    private fun hookApplicationInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val appInfoClass = XposedHelpers.findClass(
                "android.content.pm.ApplicationInfo",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                appInfoClass,
                "getSourceDir",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val appInfo = param.thisObject
                        try {
                            val packageName = appInfo.javaClass.getMethod("packageName").invoke(appInfo) as? String
                            if (packageName == AppState.targetPackage) {
                                val normalPath = "/data/app/${packageName}-base.apk"
                                param.result = normalPath
                                Logger.d("Spoofed sourceDir for: $packageName")
                            }
                        } catch (e: Throwable) {
                            // Ignore
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                appInfoClass,
                "getDataDir",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val appInfo = param.thisObject
                        try {
                            val packageName = appInfo.javaClass.getMethod("packageName").invoke(appInfo) as? String
                            if (packageName == AppState.targetPackage) {
                                val normalPath = "/data/data/${packageName}"
                                param.result = normalPath
                                Logger.d("Spoofed dataDir for: $packageName")
                            }
                        } catch (e: Throwable) {
                            // Ignore
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("ApplicationInfo hook failed", e)
        }
    }

    private fun hookProcessInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val processClass = XposedHelpers.findClass(
                "android.os.Process",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                processClass,
                "myUid",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val uid = param.result as? Int
                        if (uid != null && uid > 100000) {
                            // Keep as-is
                        }
                    }
                }
            )

            try {
                val pbClass = XposedHelpers.findClass(
                    "java.lang.ProcessBuilder",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    pbClass,
                    "environment",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val env = param.result as? MutableMap<String, String>
                            if (env != null) {
                                val keysToRemove = env.keys.filter { key ->
                                    key.contains("CLONER") ||
                                    key.contains("CLONE") ||
                                    key.contains("VIRTUAL") ||
                                    key.contains("PARALLEL") ||
                                    key.contains("MULTI") ||
                                    key.contains("SPACE")
                                }
                                keysToRemove.forEach { env.remove(it) }
                                Logger.d("Filtered ${keysToRemove.size} cloner environment variables")
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                // ProcessBuilder.environment may not exist on older Android
            }

        } catch (e: Throwable) {
            Logger.e("ProcessInfo hook failed", e)
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

                        if (key.contains("cloner") || key.contains("clone") || key.contains("virtual") ||
                            key.contains("parallel") || key.contains("space") || key.contains("multiaccounts")) {
                            param.result = ""
                            Logger.d("Blocked cloner property: $key")
                        }

                        if (key == "ro.build.fingerprint" || key == "ro.build.tags") {
                            param.result = "google/raven/raven:12/SQ3A.220605.009.A1/123456:user/release-keys"
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

                        if (key.contains("cloner") || key.contains("clone") || key.contains("virtual") ||
                            key.contains("parallel") || key.contains("space") || key.contains("multiaccounts")) {
                            param.result = ""
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("SystemProperties hook failed", e)
        }
    }

    private fun hookMountNamespace(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val osClass = XposedHelpers.findClass("android.system.Os", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                osClass,
                "stat",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val path = param.args[0] as? String ?: return
                        if (path.contains("/proc/self/mountinfo") || path.contains("/proc/self/mounts")) {
                            Logger.d("Spoofed mount namespace for: $path")
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.d("Mount namespace hook not available")
        }

        try {
            val fileClass = XposedHelpers.findClass("java.io.File", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                fileClass,
                "exists",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath

                        if (path == "/proc/self/mountinfo" || path == "/proc/self/mounts") {
                            val isRealMount = checkIfRealMount(path)
                            if (!isRealMount) {
                                param.result = false
                            }
                        }
                    }
                }
            )

            try {
                val fisClass = XposedHelpers.findClass(
                    "java.io.FileInputStream",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    fisClass,
                    "read",
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val file = param.thisObject.javaClass.getDeclaredField("file").get(param.thisObject) as? File
                            val path = file?.absolutePath

                            if (path == "/proc/self/mountinfo" || path == "/proc/self/mounts") {
                                val data = param.result as? Int
                                if (data != null && data > 0) {
                                    val buffer = param.args[0] as? ByteArray
                                    if (buffer != null) {
                                        val content = String(buffer, 0, data)
                                        val filteredContent = filterMountContent(content)
                                        val filteredBytes = filteredContent.toByteArray()
                                        System.arraycopy(filteredBytes, 0, buffer, 0, filteredBytes.size)
                                        param.result = filteredBytes.size
                                    }
                                }
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                // FileInputStream hook may not be available
            }

        } catch (e: Throwable) {
            // Ignore
        }
    }

    private fun checkIfRealMount(path: String): Boolean {
        return try {
            val content = File(path).readText()
            val clonerMounts = listOf(
                "mochi", "clone", "parallel", "virtual", "space", "multiaccounts"
            )
            !clonerMounts.any { content.contains(it) }
        } catch (e: Throwable) {
            true
        }
    }

    private fun filterMountContent(content: String): String {
        val lines = content.split("\n")
        val filteredLines = lines.filter { line ->
            val lower = line.lowercase()
            !lower.contains("mochi") &&
            !lower.contains("clone") &&
            !lower.contains("parallel") &&
            !lower.contains("virtual") &&
            !lower.contains("space") &&
            !lower.contains("multiaccounts") &&
            !lower.contains("dual") &&
            !lower.contains("multi")
        }
        return filteredLines.joinToString("\n")
    }

    private fun hookPathChecks(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val systemClass = XposedHelpers.findClass("java.lang.System", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                systemClass,
                "getProperty",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key == "java.class.path" || key == "java.library.path" || key == "java.ext.dirs") {
                            val value = param.result as? String
                            if (value != null) {
                                val filtered = value.split(":")
                                    .filterNot { path ->
                                        clonerPaths.any { path.contains(it) }
                                    }
                                    .joinToString(":")
                                param.result = filtered
                                Logger.d("Filtered cloner paths from system property: $key")
                            }
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("System property hook failed", e)
        }
    }

    private fun hookRuntimeExec(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                runtimeClass,
                "exec",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = param.args[0] as? String ?: return
                        if (cmd.contains("pm") && cmd.contains("list") && cmd.contains("packages")) {
                            val filteredCmd = cmd.replace(
                                Regex("\\| grep .*"),
                                ""
                            )
                            param.args[0] = filteredCmd
                            Logger.d("Filtered PM command: $cmd -> $filteredCmd")
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.e("Runtime.exec hook failed", e)
        }
    }

    private fun hookActivityThread(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val activityThreadClass = XposedHelpers.findClass(
                "android.app.ActivityThread",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                activityThreadClass,
                "currentActivityThread",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val thread = param.result
                        if (thread != null) {
                            try {
                                val boundApplication = thread.javaClass.getDeclaredField("mBoundApplication")
                                boundApplication.isAccessible = true
                                val appInfo = boundApplication.get(thread)
                                val packageName = appInfo.javaClass.getMethod("packageName").invoke(appInfo) as? String

                                if (packageName == AppState.targetPackage) {
                                    // Spoof the application info to show normal installation path
                                    try {
                                        val infoField = appInfo.javaClass.getDeclaredField("sourceDir")
                                        infoField.isAccessible = true
                                        val currentSource = infoField.get(appInfo) as? String
                                        if (currentSource != null && clonerPaths.any { currentSource.contains(it) }) {
                                            val spoofedPath = "/data/app/${packageName}-base.apk"
                                            infoField.set(appInfo, spoofedPath)
                                            Logger.d("Spoofed ActivityThread sourceDir: $currentSource -> $spoofedPath")
                                        }
                                    } catch (e: Throwable) {
                                        // Field may not exist
                                    }

                                    try {
                                        val dataDirField = appInfo.javaClass.getDeclaredField("dataDir")
                                        dataDirField.isAccessible = true
                                        val currentDataDir = dataDirField.get(appInfo) as? String
                                        if (currentDataDir != null && clonerPaths.any { currentDataDir.contains(it) }) {
                                            val spoofedDataDir = "/data/data/${packageName}"
                                            dataDirField.set(appInfo, spoofedDataDir)
                                            Logger.d("Spoofed ActivityThread dataDir: $currentDataDir -> $spoofedDataDir")
                                        }
                                    } catch (e: Throwable) {
                                        // Field may not exist
                                    }
                                }
                            } catch (e: Throwable) {
                                // Ignore
                            }
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.d("ActivityThread hook not available")
        }
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