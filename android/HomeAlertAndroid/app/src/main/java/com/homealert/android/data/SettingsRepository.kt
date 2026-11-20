package com.homealert.android.data

import android.content.Context
import android.content.SharedPreferences
import com.homealert.android.data.model.AlertChannel
import com.homealert.android.data.model.AppSettings
import com.homealert.android.data.model.DeliveryTier
import com.homealert.android.data.model.NetworkMode
import com.homealert.android.data.model.ZoneLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "home_alert_settings_v1",
        Context.MODE_PRIVATE
    )

    private val deliveryLogPrefs: SharedPreferences = context.getSharedPreferences(
        "home_alert_delivery_log_v1",
        Context.MODE_PRIVATE
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val networkModeName = prefs.getString("network_mode", NetworkMode.SMART_AUTO_FAILOVER.name)
        val channelName = prefs.getString("alert_channel", AlertChannel.TELEGRAM.name)
        val zoneLayoutName = prefs.getString("zone_layout", ZoneLayout.FOUR_ZONES.name)
        return AppSettings(
            cameraLabel = prefs.getString("camera_label", "Front Hallway") ?: "Front Hallway",
            sensitivityPercent = prefs.getFloat("sensitivity_percent", 5.0f),
            lumaDeltaThreshold = prefs.getInt("luma_delta_threshold", 26),
            cooldownSeconds = prefs.getInt("cooldown_seconds", 20),
            exitDelayEnabled = prefs.getBoolean("exit_delay_enabled", true),
            exitDelaySeconds = prefs.getInt("exit_delay_seconds", 15),
            stealthAutoActivate = prefs.getBoolean("stealth_auto_activate", true),
            networkMode = runCatching { NetworkMode.valueOf(networkModeName!!) }
                .getOrDefault(NetworkMode.SMART_AUTO_FAILOVER),
            alertChannel = runCatching { AlertChannel.valueOf(channelName!!) }
                .getOrDefault(AlertChannel.TELEGRAM),
            telegramBotToken = prefs.getString("telegram_bot_token", "") ?: "",
            telegramChatId = prefs.getString("telegram_chat_id", "") ?: "",
            gmailSender = prefs.getString("gmail_sender", "") ?: "",
            gmailAppPassword = prefs.getString("gmail_app_password", "") ?: "",
            gmailRecipients = prefs.getString("gmail_recipients", "") ?: "",
            gmailPort = prefs.getInt("gmail_port", 587),
            smsFallbackNumber = prefs.getString("sms_fallback_number", "") ?: "",
            selectedSimSubId = prefs.getInt("selected_sim_sub_id", -1),
            deterrentFlashEnabled = prefs.getBoolean("deterrent_flash_enabled", true),
            deterrentShutterSoundEnabled = prefs.getBoolean("deterrent_shutter_sound_enabled", true),
            requireRegionCrossing = prefs.getBoolean("require_region_crossing", false),
            zoneLayout = runCatching { ZoneLayout.valueOf(zoneLayoutName!!) }
                .getOrDefault(ZoneLayout.FOUR_ZONES)
        )
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit()
            .putString("camera_label", updated.cameraLabel)
            .putFloat("sensitivity_percent", updated.sensitivityPercent)
            .putInt("luma_delta_threshold", updated.lumaDeltaThreshold)
            .putInt("cooldown_seconds", updated.cooldownSeconds)
            .putBoolean("exit_delay_enabled", updated.exitDelayEnabled)
            .putInt("exit_delay_seconds", updated.exitDelaySeconds)
            .putBoolean("stealth_auto_activate", updated.stealthAutoActivate)
            .putString("network_mode", updated.networkMode.name)
            .putString("alert_channel", updated.alertChannel.name)
            .putString("telegram_bot_token", updated.telegramBotToken)
            .putString("telegram_chat_id", updated.telegramChatId)
            .putString("gmail_sender", updated.gmailSender)
            .putString("gmail_app_password", updated.gmailAppPassword)
            .putString("gmail_recipients", updated.gmailRecipients)
            .putInt("gmail_port", updated.gmailPort)
            .putString("sms_fallback_number", updated.smsFallbackNumber)
            .putInt("selected_sim_sub_id", updated.selectedSimSubId)
            .putBoolean("deterrent_flash_enabled", updated.deterrentFlashEnabled)
            .putBoolean("deterrent_shutter_sound_enabled", updated.deterrentShutterSoundEnabled)
            .putBoolean("require_region_crossing", updated.requireRegionCrossing)
            .putString("zone_layout", updated.zoneLayout.name)
            .apply()
    }

    fun saveDeliveryMetadata(
        fileName: String,
        cameraLabel: String,
        motionPercent: Float,
        deliveryStatus: String,
        tier: DeliveryTier
    ) {
        val json = JSONObject().apply {
            put("cameraLabel", cameraLabel)
            put("motionPercent", motionPercent.toDouble())
            put("deliveryStatus", deliveryStatus)
            put("tier", tier.name)
        }
        deliveryLogPrefs.edit().putString(fileName, json.toString()).apply()
    }

    fun getDeliveryMetadata(fileName: String): DeliveryMetadata? {
        val raw = deliveryLogPrefs.getString(fileName, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            DeliveryMetadata(
                cameraLabel = json.optString("cameraLabel", ""),
                motionPercent = json.optDouble("motionPercent", 0.0).toFloat(),
                deliveryStatus = json.optString("deliveryStatus", "Saved Locally"),
                tier = runCatching { DeliveryTier.valueOf(json.optString("tier")) }
                    .getOrDefault(DeliveryTier.LOCAL_ONLY)
            )
        }.getOrNull()
    }

    fun removeDeliveryMetadata(fileName: String) {
        deliveryLogPrefs.edit().remove(fileName).apply()
    }

    data class DeliveryMetadata(
        val cameraLabel: String,
        val motionPercent: Float,
        val deliveryStatus: String,
        val tier: DeliveryTier
    )
}
