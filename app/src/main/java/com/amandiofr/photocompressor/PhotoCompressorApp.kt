package com.amandiofr.photocompressor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PhotoCompressorApp : Application() {

    companion object {
        const val CHANNEL_ID = "compression_progress"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Compression des photos",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Progression de la compression en arrière-plan" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
