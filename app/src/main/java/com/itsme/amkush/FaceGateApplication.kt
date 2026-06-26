package com.itsme.amkush

import android.app.Application
import android.content.Context
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs

class FaceGateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SharedPrefs.init(this)
        Logger.init(false)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }
}