package com.example

import android.app.Application
import com.vyro.core.contracts.MemoryPressureLevel

class VyroApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) {
            container.cacheManager.onTrimMemory(MemoryPressureLevel.fromAndroidTrimLevel(level))
        }
    }
}
