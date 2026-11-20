package com.homealert.android.data.model

import android.net.Uri

enum class NetworkMode(val label: String, val description: String) {
    WIFI_ONLY(
        label = "Mode A: Wi-Fi Connected Only",
        description = "Binds strictly to Wi-Fi. Never uses cellular data or SMS (ideal for SIM-less spare phones)."
    ),
    SMART_AUTO_FAILOVER(
        label = "Mode B: Smart Auto-Failover",
        description = "Wi-Fi (4s timeout) → Telco 4G/5G Data → Telco SMS Last Resort."
    )
}

enum class AlertChannel(val label: String) {
    TELEGRAM("Telegram"),
    GMAIL("Gmail (SMTP)"),
    BOTH("Both (Telegram + Gmail)")
}

enum class DeliveryTier(val badgePrefix: String) {
    WIFI("Sent via Wi-Fi"),
    TELCO_DATA("Sent via Telco Data"),
    TELCO_SMS("Sent via SMS"),
    LOCAL_ONLY("Saved Locally"),
    QUEUED("Queued for Retry")
}

enum class MonitoringState {
    IDLE,
    EXIT_DELAY,
    ARMED
}

data class AppSettings(
    val cameraLabel: String = "Front Hallway",
    val sensitivityPercent: Float = 5.0f,
    val lumaDeltaThreshold: Int = 26,
    val cooldownSeconds: Int = 20,
    val exitDelayEnabled: Boolean = true,
    val exitDelaySeconds: Int = 15,
    val stealthAutoActivate: Boolean = true,
    val networkMode: NetworkMode = NetworkMode.SMART_AUTO_FAILOVER,
    val alertChannel: AlertChannel = AlertChannel.TELEGRAM,
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val gmailSender: String = "",
    val gmailAppPassword: String = "",
    val gmailRecipients: String = "",
    val gmailPort: Int = 587,
    val smsFallbackNumber: String = "",
    val selectedSimSubId: Int = -1,
    val deterrentFlashEnabled: Boolean = true,
    val deterrentShutterSoundEnabled: Boolean = true,
    val requireRegionCrossing: Boolean = false,
    val zoneLayout: ZoneLayout = ZoneLayout.FOUR_ZONES
)

enum class ZoneLayout(val cols: Int, val rows: Int, val label: String) {
    FOUR_ZONES(cols = 2, rows = 2, label = "4 Zones (2×2)"),
    SIX_ZONES(cols = 3, rows = 2, label = "6 Zones (3×2)")
}

data class MotionBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val centroidX: Float,
    val centroidY: Float,
    val zoneLabel: String,
    val crossedFromLabel: String? = null,
    val inGuardband: Boolean = false
)

data class SnapshotEntry(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val timestampMillis: Long,
    val cameraLabel: String = "",
    val motionPercent: Float = 0f,
    val deliveryStatus: String = "Saved Locally",
    val deliveryTier: DeliveryTier = DeliveryTier.LOCAL_ONLY
)

data class NetworkHealthState(
    val wifiConnected: Boolean = false,
    val wifiValidated: Boolean = false,
    val cellularAvailable: Boolean = false,
    val activeSimLabel: String = "Default SIM",
    val activeTierDescription: String = "Checking network..."
)

data class MotionTelemetry(
    val changedAreaPercent: Float = 0f,
    val meanLumaShift: Float = 0f,
    val globalExposureSuppressed: Boolean = false,
    val consecutiveMotionFrames: Int = 0,
    val cooldownRemainingSeconds: Int = 0,
    val lastAnalysisFps: Float = 0f,
    val boundingBox: MotionBoundingBox? = null
)

data class SimSubscriptionInfo(
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String,
    val slotIndex: Int
)

data class DiagnosticTestResult(
    val tierName: String,
    val success: Boolean,
    val message: String,
    val timestampMillis: Long = System.currentTimeMillis()
)
