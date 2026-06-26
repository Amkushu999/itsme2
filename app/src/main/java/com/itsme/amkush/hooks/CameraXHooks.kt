package com.itsme.amkush.hooks

import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * CameraXHooks — FFmpeg architecture
 *
 * CameraX (androidx.camera.*) builds entirely on Camera2 internally.
 * Camera2Hooks already blocks openCamera() and intercepts createCaptureSession(),
 * so all frame injection is handled there automatically.
 *
 * This file only adds:
 *   1. Metadata intercepts (target resolution / FPS) so logging/debugging
 *      shows the correct dimensions even for CameraX apps.
 *   2. A hook on ImageAnalysis.setAnalyzer() — the one CameraX code path
 *      that bypasses Camera2 surface routing and calls analyze(ImageProxy)
 *      directly.  We wrap the analyzer to skip its call (the app already
 *      sees our frames via the redirected Surface), preventing double-
 *      processing of stale real-camera data.
 */
object CameraXHooks {

    private const val TAG = "CameraXHooks"

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            hookImageAnalysisSetAnalyzer(lpparam)
            hookImageAnalysisBuilder(lpparam)
            hookCameraProviderBind(lpparam)
            Logger.d("$TAG hooks installed")
        } catch (e: Throwable) {
            Logger.e("$TAG hookAll failed", e)
        }
    }

    // ── ImageAnalysis.setAnalyzer → wrap to skip redundant analyze() calls ──

    private fun hookImageAnalysisSetAnalyzer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val imageAnalysisClass = XposedHelpers.findClass(
                "androidx.camera.core.ImageAnalysis", lpparam.classLoader
            )
            val analyzerClass = XposedHelpers.findClass(
                "androidx.camera.core.ImageAnalysis\$Analyzer", lpparam.classLoader
            )
            val imageProxyClass = XposedHelpers.findClass(
                "androidx.camera.core.ImageProxy", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                imageAnalysisClass, "setAnalyzer",
                java.util.concurrent.Executor::class.java,
                analyzerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!AppState.isHookingActive) return
                        val originalAnalyzer = param.args[1] ?: return
                        val analyzeMethod = try {
                            originalAnalyzer.javaClass.getMethod("analyze", imageProxyClass)
                        } catch (e: Throwable) {
                            Logger.e("$TAG cannot find analyze()", e); return
                        }

                        // Wrap the analyzer: FFmpeg frames reach the app via the redirected
                        // Surface, so we let analyze() fire normally — it just reads from our
                        // fake surface buffers rather than real camera data.
                        val proxy = java.lang.reflect.Proxy.newProxyInstance(
                            originalAnalyzer.javaClass.classLoader,
                            arrayOf(analyzerClass)
                        ) { _, method, args ->
                            if (method.name == "analyze" && args?.size == 1) {
                                try {
                                    analyzeMethod.invoke(originalAnalyzer, args[0])
                                } catch (e: Throwable) {
                                    Logger.e("$TAG analyzer proxy error", e)
                                }
                            } else {
                                try { method.invoke(originalAnalyzer, *(args ?: emptyArray())) }
                                catch (_: Throwable) { null }
                            }
                        }
                        param.args[1] = proxy
                        Logger.d("$TAG ImageAnalysis.setAnalyzer wrapped")
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e("$TAG setAnalyzer hook failed", e)
        }
    }

    // ── Capture target resolution for logging ─────────────────────────────────

    private fun hookImageAnalysisBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClass(
                "androidx.camera.core.ImageAnalysis\$Builder", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                builderClass, "setTargetResolution",
                android.util.Size::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sz = param.args[0] as? android.util.Size ?: return
                        Logger.d("$TAG CameraX target resolution: ${sz.width}x${sz.height}")
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("$TAG ImageAnalysis.Builder hook skipped: ${e.message}")
        }
    }

    // ── ProcessCameraProvider.bindToLifecycle → log + state tracking ─────────

    private fun hookCameraProviderBind(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val providerClass = XposedHelpers.findClass(
                "androidx.camera.lifecycle.ProcessCameraProvider", lpparam.classLoader
            )
            val lifecycleClass = XposedHelpers.findClass(
                "androidx.lifecycle.LifecycleOwner", lpparam.classLoader
            )
            val selectorClass = XposedHelpers.findClass(
                "androidx.camera.core.CameraSelector", lpparam.classLoader
            )
            val useCaseClass = XposedHelpers.findClass(
                "androidx.camera.core.UseCase", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                providerClass, "bindToLifecycle",
                lifecycleClass, selectorClass,
                Array<Any>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Camera2Hooks handles the actual blocking.
                        // Log here for debugging convenience.
                        if (!AppState.isHookingActive) return
                        Logger.d("$TAG CameraX bindToLifecycle intercepted — Camera2 block handles physical camera")
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("$TAG bindToLifecycle hook skipped: ${e.message}")
        }
    }
}
