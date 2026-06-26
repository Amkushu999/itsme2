package com.itsme.amkush.ipc

  import android.content.ContentValues
  import android.content.Context
  import android.net.Uri
  import com.itsme.amkush.utils.Logger

  /**
   * Cross-process config client — works in both the control app process and
   * any Xposed-hooked target-app process.
   *
   * Read strategy (two layers, tried in order):
   *   1. ContentProvider query — works when InjectionService is running on a rooted device.
   *   2. createPackageContext + SharedPreferences — works in virtual cloner environments
   *      (Mochi Cloner, VirtualXposed, etc.) where the module app and the hook both live
   *      inside the same virtual process space and cross-process ContentProvider may be
   *      unreachable, but the file system is shared.
   *
   * Write always goes through the ContentProvider (module-app side only).
   */
  object RemoteConfig {

      private const val AUTHORITY = FaceGateIpcProvider.AUTHORITY
      private const val MODULE_PKG = "com.itsme.amkush"

      const val KEY_TARGET_PACKAGE   = "target_package"
      const val KEY_STREAM_URL       = "stream_url"
      const val KEY_MEDIA_URI        = "media_uri"
      const val KEY_INJECTION_ACTIVE = "injection_active"

      private fun uriFor(key: String): Uri =
          Uri.parse("content://$AUTHORITY/${FaceGateIpcProvider.BASE_PATH}/$key")

      // ──────────────────────────────────────────────────────────────
      //  Write  (module-app process only — ContentProvider is local here)
      // ──────────────────────────────────────────────────────────────

      fun write(context: Context, key: String, value: String?) {
          try {
              val cv = ContentValues().apply {
                  put(FaceGateIpcProvider.COL_KEY, key)
                  if (value == null) putNull(FaceGateIpcProvider.COL_VALUE)
                  else put(FaceGateIpcProvider.COL_VALUE, value)
              }
              context.contentResolver.insert(uriFor(key), cv)
          } catch (e: Throwable) {
              Logger.e("RemoteConfig.write($key) failed", e)
          }
      }

      fun setTargetPackage(context: Context, pkg: String?)      = write(context, KEY_TARGET_PACKAGE, pkg)
      fun setStreamUrl(context: Context, url: String?)          = write(context, KEY_STREAM_URL, url)
      fun setMediaUri(context: Context, uri: String?)           = write(context, KEY_MEDIA_URI, uri)
      fun setInjectionActive(context: Context, active: Boolean) =
          write(context, KEY_INJECTION_ACTIVE, if (active) "1" else "0")

      // ──────────────────────────────────────────────────────────────
      //  Read  (two-layer: ContentProvider → createPackageContext)
      // ──────────────────────────────────────────────────────────────

      fun read(context: Context, key: String): String? {
          // Layer 1: ContentProvider — fast path on rooted devices with InjectionService running
          try {
              val cursor = context.contentResolver.query(
                  uriFor(key), null, null, null, null
              )
              cursor?.use { c ->
                  if (c.moveToFirst()) {
                      val idx = c.getColumnIndex(FaceGateIpcProvider.COL_VALUE)
                      if (idx >= 0) return c.getString(idx)
                  }
              }
          } catch (e: Throwable) {
              Logger.d("RemoteConfig: ContentProvider unavailable for $key — trying SharedPrefs fallback")
          }

          // Layer 2: Direct SharedPrefs access via createPackageContext.
          // In virtual cloner environments (Mochi Cloner, VirtualXposed, etc.), both the
          // module app and the target app run inside the same virtual sandbox, so the module's
          // data directory is visible to a createPackageContext call. On rooted devices this
          // also works when SELinux is permissive (LSPosed grants the required context).
          return try {
              val moduleCtx = context.createPackageContext(
                  MODULE_PKG,
                  Context.CONTEXT_IGNORE_SECURITY
              )
              // Try facegate_ipc first (written by FaceGateIpcProvider / InjectionService)
              moduleCtx.getSharedPreferences("facegate_ipc", Context.MODE_PRIVATE)
                  .getString(key, null)
              // Then try facegate_prefs (written by SharedPrefs utility / UI layer)
                  ?: moduleCtx.getSharedPreferences("facegate_prefs", Context.MODE_PRIVATE)
                      .getString(key, null)
          } catch (e: Throwable) {
              Logger.e("RemoteConfig.read($key): SharedPrefs fallback also failed", e)
              null
          }
      }

      fun getTargetPackage(context: Context): String?  = read(context, KEY_TARGET_PACKAGE)
      fun getStreamUrl(context: Context): String?       = read(context, KEY_STREAM_URL)
      fun getMediaUri(context: Context): String?        = read(context, KEY_MEDIA_URI)
      fun isInjectionActive(context: Context): Boolean  = read(context, KEY_INJECTION_ACTIVE) == "1"

      // ──────────────────────────────────────────────────────────────
      //  Clear  (called when injection stops)
      // ──────────────────────────────────────────────────────────────

      fun clearAll(context: Context) {
          setInjectionActive(context, false)
          setStreamUrl(context, null)
          setMediaUri(context, null)
      }
  }
  