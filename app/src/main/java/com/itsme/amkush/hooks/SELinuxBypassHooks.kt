package com.itsme.amkush.hooks

import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SELinuxBypassHooks {

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!AppState.isHookingActive) {
            return
        }

        try {
            hookSELinux(lpparam)
            Logger.d("SELinux bypass hooks installed")
        } catch (e: Throwable) {
            Logger.e("SELinux bypass hooks failed", e)
        }
    }

    private fun hookSELinux(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook SELinux.getContext()
            try {
                val selinuxClass = XposedHelpers.findClass(
                    "android.os.SELinux",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    selinuxClass,
                    "getContext",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = "u:r:untrusted_app:s0"
                            Logger.d("SELinux.getContext spoofed")
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    selinuxClass,
                    "getFileContext",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = "u:object_r:untrusted_app_file:s0"
                            Logger.d("SELinux.getFileContext spoofed")
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    selinuxClass,
                    "isSELinuxEnabled",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = true
                            Logger.d("SELinux.isSELinuxEnabled spoofed")
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    selinuxClass,
                    "isSELinuxEnforced",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = true
                            Logger.d("SELinux.isSELinuxEnforced spoofed")
                        }
                    }
                )

            } catch (e: Throwable) {
                Logger.d("SELinux class not found")
            }

            // Hook SystemProperties for SELinux
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
                            when {
                                key.contains("selinux") -> {
                                    when {
                                        key.contains("enforcing") -> param.result = "1"
                                        key.contains("status") -> param.result = "enforcing"
                                        key.contains("policy") -> param.result = "enforcing"
                                        else -> param.result = "enforcing"
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
                            if (key.contains("selinux")) {
                                param.result = "enforcing"
                            }
                        }
                    }
                )

            } catch (e: Throwable) {
                // Ignore
            }

            // Hook File for SELinux status files
            try {
                val fileClass = XposedHelpers.findClass("java.io.File", lpparam.classLoader)

                XposedHelpers.findAndHookMethod(
                    fileClass,
                    "exists",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val file = param.thisObject as? java.io.File ?: return
                            val path = file.absolutePath
                            if (path == "/sys/fs/selinux/enforce" || path == "/sys/fs/selinux/policy") {
                                param.result = true
                            }
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    fileClass,
                    "canRead",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val file = param.thisObject as? java.io.File ?: return
                            val path = file.absolutePath
                            if (path == "/sys/fs/selinux/enforce" || path == "/sys/fs/selinux/policy") {
                                param.result = true
                            }
                        }
                    }
                )

            } catch (e: Throwable) {
                // Ignore
            }

        } catch (e: Throwable) {
            Logger.e("SELinux hook failed", e)
        }
    }
}