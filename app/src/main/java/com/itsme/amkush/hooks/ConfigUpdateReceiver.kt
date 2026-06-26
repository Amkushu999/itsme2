package com.itsme.amkush.hooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger

/**
 * Registered inside the hooked target process so that when the user changes
 * the stream URL in the module app, the new URL takes effect immediately —
 * no target-app restart required.
 *
 * InjectionService (module-app side) sends a broadcast with action
 * [ACTION] whenever stream config changes.  This receiver catches it,
 * updates AppState, and calls InjectionServiceClient.hotSwap() to swap
 * the source on the fly.
 *
 * Broadcast action : [ACTION]
 * Extras:
 *   [EXTRA_STREAM_URL]  String?  — new RTSP / HLS URL (null ⟹ use media_uri)
 *   [EXTRA_MEDIA_URI]   String?  — new local file / content URI
 *   [EXTRA_ACTIVE]      Boolean  — false ⟹ stop injection immediately
 */
class ConfigUpdateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION           = "com.itsme.amkush.ACTION_CONFIG_UPDATE"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_MEDIA_URI  = "media_uri"
        const val EXTRA_ACTIVE     = "active"

        /**
         * Register this receiver in the hooked process context.
         * Returns the receiver so the caller can unregister it if needed.
         */
        fun register(context: Context): ConfigUpdateReceiver {
            val receiver = ConfigUpdateReceiver()
            val filter   = IntentFilter(ACTION)
            context.registerReceiver(receiver, filter)
            Logger.d("ConfigUpdateReceiver: registered in ${context.packageName}")
            return receiver
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val active    = intent.getBooleanExtra(EXTRA_ACTIVE, true)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val mediaUri  = intent.getStringExtra(EXTRA_MEDIA_URI)

        Logger.d("ConfigUpdateReceiver: active=$active url=$streamUrl media=$mediaUri")

        if (!active) {
            AppState.isHookingActive = false
            InjectionServiceClient.stopAll()
            return
        }

        AppState.isHookingActive = true
        val url = streamUrl ?: mediaUri
        if (!url.isNullOrBlank()) {
            InjectionServiceClient.hotSwap(url)
        }
    }
}
