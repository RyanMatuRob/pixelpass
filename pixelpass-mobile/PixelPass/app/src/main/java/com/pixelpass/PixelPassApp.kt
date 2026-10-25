package com.pixelpass

import android.app.Application
import com.pixelpass.core.PixelPassLog
import com.pixelpass.data.local.PixelPassDatabase
import com.pixelpass.di.AppContainer

class PixelPassApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
        PixelPassLog.i(PixelPassLog.DB, "Application started; local database is ${PixelPassDatabase.NAME}")
    }
}
