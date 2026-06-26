package com.itsme.amkush.utils

import android.util.Log
import de.robv.android.xposed.XposedBridge

object Logger {
    private const val TAG = "FaceGate"
    private var isXposedMode = false

    fun init(xposedMode: Boolean) {
        isXposedMode = xposedMode
    }

    fun d(message: String) {
        if (isXposedMode) {
            XposedBridge.log("[$TAG] $message")
        } else {
            Log.d(TAG, message)
        }
    }

    fun i(message: String) {
        if (isXposedMode) {
            XposedBridge.log("[$TAG] $message")
        } else {
            Log.i(TAG, message)
        }
    }

    fun w(message: String) {
        if (isXposedMode) {
            XposedBridge.log("[$TAG] WARN: $message")
        } else {
            Log.w(TAG, message)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (isXposedMode) {
            XposedBridge.log("[$TAG] ERROR: $message")
            throwable?.let { XposedBridge.log("[$TAG] ${it.stackTraceToString()}") }
        } else {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        }
    }

    fun logException(tag: String, throwable: Throwable) {
        if (isXposedMode) {
            XposedBridge.log("[$tag] Exception: ${throwable.message}")
            XposedBridge.log("[$tag] ${throwable.stackTraceToString()}")
        } else {
            Log.e(tag, "Exception: ${throwable.message}", throwable)
        }
    }
}