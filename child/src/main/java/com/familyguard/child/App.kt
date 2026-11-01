package com.familyguard.child

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Guard.load(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "الحماية", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    companion object {
        const val CHANNEL = "guard"
    }
}
