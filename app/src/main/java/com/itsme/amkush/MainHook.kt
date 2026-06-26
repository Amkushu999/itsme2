package com.itsme.amkush

  import android.content.Context
  import android.content.SharedPreferences
  import android.net.Uri
  import de.robv.android.xposed.IXposedHookLoadPackage
  import de.robv.android.xposed.XC_MethodHook
  import de.robv.android.xposed.XposedHelpers
  import de.robv.android.xposed.callbacks.XC_LoadPackage
  import com.itsme.amkush.hooks.*
  import com.itsme.amkush.utils.Logger

  class MainHook : IXposedHookLoadPackage {

      override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
          if (lpparam.packageName == "android" || lpparam.packageName == "system") return

          Logger.init(true)
          Logger.d("Loading package: ${lpparam.packageName}")

          // Only hook Application.onCreate here.  All camera / anti-detection hooks are
          // installed inside hookApplication()'s afterHookedMethod callback, AFTER we
          // confirm this is the target package and set isHookingActive = true.
          //
          // Previously every hook was installed in every app process at handleLoadPackage
          // time, paying significant JNI overhead on the entire device.
          hookApplication(lpparam)
      }

      // ── hookApplication ───────────────────────────────────────────────────────
      //
      // Determines if this process is the target app.  If so:
      //   1. Sets AppState.isHookingActive = true.
      //   2. Registers ConfigUpdateReceiver for live URL swaps.
      //   3. Does NOT start a decoder — decoding runs in the MODULE process
      //      (InjectionService + FFmpegDecoder JNI).  The camera hooks will
      //      trigger the Binder connection to InjectionService automatically
      //      on the first createCaptureSession / setPreviewDisplay call.
      // ─────────────────────────────────────────────────────────────────────────
      private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
          try {
              val applicationClass = lpparam.classLoader.loadClass("android.app.Application")
              XposedHelpers.findAndHookMethod(
                  applicationClass,
                  "onCreate",
                  object : XC_MethodHook() {
                      override fun afterHookedMethod(param: MethodHookParam) {
                          val ctx = param.thisObject as Context
                          AppState.context = ctx

                          val targetPackage = resolveModuleString(ctx, "target_package")
                          AppState.targetPackage = targetPackage

                          if (targetPackage.isNullOrEmpty()) {
                              Logger.d("No target configured — skipping ${lpparam.packageName}")
                              return
                          }
                          if (targetPackage != lpparam.packageName) {
                              Logger.d("Not target (target=$targetPackage) — skipping ${lpparam.packageName}")
                              return
                          }

                          val denyList: Set<String> = try {
                              openModulePrefs(ctx, "facegate_prefs")
                                  ?.getStringSet("deny_list", emptySet()) ?: emptySet()
                          } catch (_: Throwable) { emptySet() }

                          if (denyList.contains(lpparam.packageName)) {
                              Logger.d("App in deny list — skipping ${lpparam.packageName}")
                              return
                          }

                          Logger.d("Target app detected: ${lpparam.packageName} — FFmpeg camera block active")
                          AppState.isHookingActive = true

                          // Register live-update receiver — URL/config changes from the module
                          // UI take effect immediately without restarting the target app.
                          try {
                              ConfigUpdateReceiver.register(ctx)
                          } catch (e: Throwable) {
                              Logger.e("ConfigUpdateReceiver registration failed", e)
                          }

                          // ── Install all feature hooks now, only for the target process ──
                          // Xposed allows findAndHookMethod to be called at any time after
                          // handleLoadPackage; hooks installed here are still effective for all
                          // future calls since Camera APIs are always called after Application.onCreate.
                          //
                          // Camera hooks (FFmpeg native architecture):
                          try { Camera1Hooks.hookAll(lpparam); Logger.d("Camera1 hooks installed") }
                          catch (e: Throwable) { Logger.e("Camera1 hooks failed", e) }

                          try { Camera2Hooks.hookAll(lpparam); Logger.d("Camera2 hooks installed") }
                          catch (e: Throwable) { Logger.e("Camera2 hooks failed", e) }

                          try { CameraXHooks.hookAll(lpparam); Logger.d("CameraX hooks installed") }
                          catch (e: Throwable) { Logger.e("CameraX hooks failed", e) }

                          // EXIF spoofing:
                          try { ExifSpoofHooks.hookAll(lpparam); Logger.d("EXIF spoof hooks installed") }
                          catch (e: Throwable) { Logger.e("EXIF spoof hooks failed", e) }

                          // Intent capture replacement:
                          try { IntentCaptureHooks.hookAll(lpparam); Logger.d("Intent capture hooks installed") }
                          catch (e: Throwable) { Logger.e("Intent capture hooks failed", e) }

                          // Device spoofing:
                          try { DeviceSpoofHooks.hookAll(lpparam); Logger.d("Device spoof hooks installed") }
                          catch (e: Throwable) { Logger.e("Device spoof hooks failed", e) }

                          // Deny list:
                          try { DenyListHooks.hookAll(lpparam); Logger.d("Deny list hooks installed") }
                          catch (e: Throwable) { Logger.e("Deny list hooks failed", e) }

                          // Anti-detection:
                          try { EmulatorBypassHooks.hookAll(lpparam); Logger.d("Emulator bypass hooks installed") }
                          catch (e: Throwable) { Logger.e("Emulator bypass hooks failed", e) }

                          try { RootBypassHooks.hookAll(lpparam); Logger.d("Root bypass hooks installed") }
                          catch (e: Throwable) { Logger.e("Root bypass hooks failed", e) }

                          try { AntiXposedHooks.hookAll(lpparam); Logger.d("Anti-Xposed hooks installed") }
                          catch (e: Throwable) { Logger.e("Anti-Xposed hooks failed", e) }

                          try { SELinuxBypassHooks.hookAll(lpparam); Logger.d("SELinux bypass hooks installed") }
                          catch (e: Throwable) { Logger.e("SELinux bypass hooks failed", e) }

                          try { ClonerBypassHooks.hookAll(lpparam); Logger.d("Cloner bypass hooks installed") }
                          catch (e: Throwable) { Logger.e("Cloner bypass hooks failed", e) }

                          // NOTE: No decoder is started here.
                          // The FFmpeg pipeline runs in the MODULE PROCESS (InjectionService).
                          // InjectionServiceClient connects to InjectionService via Binder the first
                          // time a camera session is created (Camera2Hooks → handleSessionCreation).
                      }
                  }
              )
          } catch (e: Throwable) {
              Logger.e("Failed to hook Application", e)
          }
      }

      // ── Module config resolution helpers ──────────────────────────────────────

      private fun openModulePrefs(ctx: Context, prefsName: String): SharedPreferences? = try {
          ctx.createPackageContext("com.itsme.amkush", Context.CONTEXT_IGNORE_SECURITY)
              .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
      } catch (_: Throwable) { null }

      private fun resolveModuleString(ctx: Context, key: String): String? {
          // Layer 1: ContentProvider (fastest, works when InjectionService is running)
          try {
              val uri = Uri.parse("content://com.itsme.amkush.ipc/config/$key")
              ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                  if (c.moveToFirst()) {
                      val idx = c.getColumnIndex("value")
                      if (idx >= 0) return c.getString(idx)
                  }
              }
          } catch (_: Throwable) {
              Logger.d("resolveModuleString: ContentProvider unavailable for $key")
          }
          // Layer 2: Direct SharedPreferences (virtual cloner environments)
          return openModulePrefs(ctx, "facegate_ipc")?.getString(key, null)
              ?: openModulePrefs(ctx, "facegate_prefs")?.getString(key, null)
      }
  }
