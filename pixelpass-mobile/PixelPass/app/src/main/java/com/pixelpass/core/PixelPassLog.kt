package com.pixelpass.core

import android.util.Log
import com.pixelpass.BuildConfig

/**
 * Single entry point for app logging so every line can be filtered in Logcat.
 *
 * Verbose levels are compiled out of release builds by the `BuildConfig.DEBUG`
 * guard; warnings and errors are always written so field failures stay visible.
 */
object PixelPassLog {

    const val AUTH = "PixelPass_Auth"
    const val NET = "PixelPass_Net"
    const val SESSION = "PixelPass_Session"
    const val QR = "PixelPass_Qr"
    const val SCAN = "PixelPass_Scan"
    const val DB = "PixelPass_Db"
    const val SYNC = "PixelPass_Sync"

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message, throwable)
        }
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message, throwable)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, message, throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
