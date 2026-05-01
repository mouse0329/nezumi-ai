package com.nezumi_ai

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.nezumi_ai.utils.PreferencesHelper

/**
 * Custom Application class for manual WorkManager initialization
 * to reduce Binder thread load (Binder スレッド負荷軽減)
 */
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PreferencesHelper.applyThemeMode(this)
        
        // Manual WorkManager initialization to avoid default initialization
        if (!WorkManager.isInitialized()) {
            val config = Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
            WorkManager.initialize(this, config)
        }
    }
}
