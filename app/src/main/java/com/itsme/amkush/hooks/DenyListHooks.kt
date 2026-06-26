package com.itsme.amkush.hooks

import android.content.pm.PackageManager
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object DenyListHooks {

    private const val TAG = "FaceGate"

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!AppState.isHookingActive) {
            return
        }

        val denyList = SharedPrefs.getDenyList()
        if (denyList.isEmpty()) {
            Logger.d("Deny list is empty, skipping")
            return
        }

        try {
            hookPackageManager(lpparam, denyList)
            Logger.d("Deny list hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Deny list hooks failed", e)
        }
    }

    private fun hookPackageManager(lpparam: XC_LoadPackage.LoadPackageParam, denyList: Set<String>) {
        val packageManagerClass = XposedHelpers.findClass(
            "android.app.ApplicationPackageManager",
            lpparam.classLoader
        )

        // Hook getInstalledApplications
        XposedHelpers.findAndHookMethod(
            packageManagerClass,
            "getInstalledApplications",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val result = param.result as? List<*>
                    result?.let { apps ->
                        val filtered = apps.filter { appInfo ->
                            val packageName = getPackageName(appInfo!!)
                            !denyList.contains(packageName)
                        }
                        param.result = filtered
                        Logger.d("Filtered ${apps.size - filtered.size} apps from deny list")
                    }
                }
            }
        )

        // Hook getInstalledPackages
        XposedHelpers.findAndHookMethod(
            packageManagerClass,
            "getInstalledPackages",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val result = param.result as? List<*>
                    result?.let { packages ->
                        val filtered = packages.filter { pkgInfo ->
                            val packageName = getPackageNameFromPackageInfo(pkgInfo!!)
                            !denyList.contains(packageName)
                        }
                        param.result = filtered
                        Logger.d("Filtered ${packages.size - filtered.size} packages from deny list")
                    }
                }
            }
        )

        // Hook getPackageInfo
        XposedHelpers.findAndHookMethod(
            packageManagerClass,
            "getPackageInfo",
            String::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val packageName = param.args[0] as? String
                    if (packageName != null && denyList.contains(packageName)) {
                        param.result = null
                        Logger.d("Blocked getPackageInfo for: $packageName")
                    }
                }
            }
        )

        // Hook getApplicationInfo
        XposedHelpers.findAndHookMethod(
            packageManagerClass,
            "getApplicationInfo",
            String::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val packageName = param.args[0] as? String
                    if (packageName != null && denyList.contains(packageName)) {
                        param.result = null
                        Logger.d("Blocked getApplicationInfo for: $packageName")
                    }
                }
            }
        )

        // Hook resolveActivity
        XposedHelpers.findAndHookMethod(
            packageManagerClass,
            "resolveActivity",
            XposedHelpers.findClass("android.content.Intent", lpparam.classLoader),
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args[0] as? android.content.Intent
                    val component = intent?.component
                    val packageName = component?.packageName
                    if (packageName != null && denyList.contains(packageName)) {
                        param.result = null
                        Logger.d("Blocked resolveActivity for: $packageName")
                    }
                }
            }
        )
    }

    private fun getPackageName(appInfo: Any): String {
        return try {
            val method = appInfo.javaClass.getMethod("packageName")
            method.invoke(appInfo) as? String ?: "unknown"
        } catch (e: Throwable) {
            "unknown"
        }
    }

    private fun getPackageNameFromPackageInfo(pkgInfo: Any): String {
        return try {
            val method = pkgInfo.javaClass.getMethod("packageName")
            method.invoke(pkgInfo) as? String ?: "unknown"
        } catch (e: Throwable) {
            "unknown"
        }
    }
}