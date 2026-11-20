package com.homealert.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.homealert.android.data.MediaStoreRepository
import com.homealert.android.data.SettingsRepository
import com.homealert.android.network.AlertDispatcher

class HomeAlertApplication : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var mediaStoreRepository: MediaStoreRepository
        private set

    lateinit var alertDispatcher: AlertDispatcher
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        mediaStoreRepository = MediaStoreRepository(this, settingsRepository)
        alertDispatcher = AlertDispatcher(this, settingsRepository)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val monitoringChannel = NotificationChannel(
                CHANNEL_MONITORING,
                getString(R.string.notification_channel_monitoring),
                NotificationManager.IMPORTANCE_LOW
            )
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(monitoringChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    companion object {
        const val CHANNEL_MONITORING = "homealert_monitoring"
        const val CHANNEL_ALERTS = "homealert_alerts"
    }
}
