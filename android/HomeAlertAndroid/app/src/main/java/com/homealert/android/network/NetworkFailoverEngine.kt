package com.homealert.android.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.homealert.android.data.model.AppSettings
import com.homealert.android.data.model.DeliveryTier
import com.homealert.android.data.model.NetworkHealthState
import com.homealert.android.data.model.NetworkMode
import com.homealert.android.data.model.SimSubscriptionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.SocketFactory
import kotlin.coroutines.resume

/**
 * Pure, testable interface for resolving networks & dispatching alerts across the 3-tier failover hierarchy.
 */
interface FailoverTransportGateway {
    suspend fun getValidatedWifiSocketFactory(): SocketFactory?
    suspend fun acquireCellularSocketFactory(timeoutMs: Long): Pair<SocketFactory, () -> Unit>?
    suspend fun sendPhotoViaSocket(
        tierLabel: String,
        socketFactory: SocketFactory,
        settings: AppSettings
    ): Result<String>

    suspend fun sendSmsFallback(
        phoneNumber: String,
        subId: Int,
        message: String
    ): Result<Unit>
}

data class FailoverOutcome(
    val tier: DeliveryTier,
    val statusBadge: String,
    val channelSummary: String,
    val errorDetails: String? = null
)

/**
 * Core 3-Tier Smart Auto-Failover Decision Engine:
 * - Mode A (WIFI_ONLY): Strictly Tier 1 (validated Wi-Fi). Never touches Cellular or SMS.
 * - Mode B (SMART_AUTO_FAILOVER):
 *     Tier 1: Validated Wi-Fi (strict 4s timeout)
 *     Tier 2: Explicit TRANSPORT_CELLULAR request + socket binding
 *     Tier 3: SmsManager text-only fallback guarded by SmsRateLimiter (90s anti-flood floor)
 */
class NetworkFailoverEngine(
    private val smsRateLimiter: SmsRateLimiter = SmsRateLimiter(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun executeFailover(
        settings: AppSettings,
        cameraLabel: String,
        timestampMs: Long,
        gateway: FailoverTransportGateway
    ): FailoverOutcome {
        val wifiSocketFactory = gateway.getValidatedWifiSocketFactory()
        var lastError: String? = null

        // Tier 1: Wi-Fi with NET_CAPABILITY_VALIDATED
        if (wifiSocketFactory != null) {
            val wifiResult = gateway.sendPhotoViaSocket(
                tierLabel = "Wi-Fi",
                socketFactory = wifiSocketFactory,
                settings = settings
            )
            if (wifiResult.isSuccess) {
                val channelDesc = wifiResult.getOrDefault(settings.alertChannel.label)
                return FailoverOutcome(
                    tier = DeliveryTier.WIFI,
                    statusBadge = "Sent via Wi-Fi / $channelDesc",
                    channelSummary = channelDesc
                )
            }
            lastError = wifiResult.exceptionOrNull()?.message ?: "Wi-Fi upload failed"
        } else {
            lastError = "Wi-Fi unvalidated or offline"
        }

        // Mode A stops strictly at Wi-Fi
        if (settings.networkMode == NetworkMode.WIFI_ONLY) {
            return FailoverOutcome(
                tier = DeliveryTier.LOCAL_ONLY,
                statusBadge = "Saved Locally (Wi-Fi Only offline)",
                channelSummary = "Local",
                errorDetails = lastError
            )
        }

        // Tier 2: Telco 4G/5G Mobile Data Failover
        val cellularBinding = gateway.acquireCellularSocketFactory(timeoutMs = 5_000L)
        if (cellularBinding != null) {
            val (cellSocketFactory, releaseNetwork) = cellularBinding
            try {
                val cellResult = gateway.sendPhotoViaSocket(
                    tierLabel = "Telco Data",
                    socketFactory = cellSocketFactory,
                    settings = settings
                )
                if (cellResult.isSuccess) {
                    val channelDesc = cellResult.getOrDefault(settings.alertChannel.label)
                    return FailoverOutcome(
                        tier = DeliveryTier.TELCO_DATA,
                        statusBadge = "Sent via Telco Data / $channelDesc",
                        channelSummary = channelDesc
                    )
                }
                lastError = cellResult.exceptionOrNull()?.message ?: "Telco data upload failed"
            } finally {
                releaseNetwork()
            }
        } else {
            lastError = "Telco Mobile Data unavailable"
        }

        // Tier 3: Telco SMS Last Resort (with 90s Anti-Flood Guard)
        val smsNumber = settings.smsFallbackNumber.trim()
        if (smsNumber.isNotEmpty()) {
            val now = clockMs()
            if (smsRateLimiter.tryAcquire(now)) {
                val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(Date(timestampMs))
                val smsBody =
                    "🚨 HomeAlert: Motion detected at $cameraLabel on $formattedDate. Snapshot saved locally (Wi-Fi & Data offline)."

                val smsResult = gateway.sendSmsFallback(
                    phoneNumber = smsNumber,
                    subId = settings.selectedSimSubId,
                    message = smsBody
                )
                if (smsResult.isSuccess) {
                    return FailoverOutcome(
                        tier = DeliveryTier.TELCO_SMS,
                        statusBadge = "Sent via SMS",
                        channelSummary = "SMS"
                    )
                }
                lastError = smsResult.exceptionOrNull()?.message ?: "SMS send failed"
            } else {
                val waitSec = smsRateLimiter.remainingCooldownSeconds(now)
                lastError = "SMS anti-flood guard active (${waitSec}s remaining)"
            }
        }

        return FailoverOutcome(
            tier = DeliveryTier.LOCAL_ONLY,
            statusBadge = "Saved Locally",
            channelSummary = "Local",
            errorDetails = lastError
        )
    }
}

/**
 * Android ConnectivityManager & SmsManager implementation of FailoverTransportGateway.
 */
class AndroidNetworkGateway(
    private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun inspectNetworkHealth(settings: AppSettings): NetworkHealthState {
        var wifiConnected = false
        var wifiValidated = false
        var cellularAvailable = false

        @Suppress("DEPRECATION")
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifiConnected = true
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    wifiValidated = true
                }
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                cellularAvailable = true
            }
        }

        val activeTierDesc = when {
            wifiValidated -> "Tier 1 Ready: Wi-Fi Validated"
            settings.networkMode == NetworkMode.WIFI_ONLY -> "Wi-Fi Only: No Validated Internet"
            cellularAvailable -> "Tier 2 Ready: Telco Data Failover"
            settings.smsFallbackNumber.isNotBlank() -> "Tier 3 Ready: Telco SMS Standby"
            else -> "Offline: Local Save Only"
        }

        return NetworkHealthState(
            wifiConnected = wifiConnected,
            wifiValidated = wifiValidated,
            cellularAvailable = cellularAvailable,
            activeSimLabel = resolveSimLabel(settings.selectedSimSubId),
            activeTierDescription = activeTierDesc
        )
    }

    fun findValidatedWifiNetwork(): Network? {
        @Suppress("DEPRECATION")
        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return network
            }
        }
        return null
    }

    /**
     * Explicitly requests TRANSPORT_CELLULAR even if the device remains connected to a Wi-Fi router
     * whose external ISP cable was cut. Returns the bound Network and an unregister lambda.
     */
    suspend fun requestCellularNetwork(timeoutMs: Long = 5_000L): Pair<Network, () -> Unit>? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) {
                            cont.resume(network to {
                                runCatching { connectivityManager.unregisterNetworkCallback(this) }
                            })
                        }
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) {
                            runCatching { connectivityManager.unregisterNetworkCallback(this) }
                            cont.resume(null)
                        }
                    }
                }

                runCatching {
                    connectivityManager.requestNetwork(request, callback)
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }

                cont.invokeOnCancellation {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun getAvailableSims(): List<SimSubscriptionInfo> {
        return runCatching {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val list = subManager.activeSubscriptionInfoList.orEmpty()
            list.map { info ->
                SimSubscriptionInfo(
                    subscriptionId = info.subscriptionId,
                    displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                    carrierName = info.carrierName?.toString() ?: "Carrier",
                    slotIndex = info.simSlotIndex
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun resolveSimLabel(subId: Int): String {
        val sims = getAvailableSims()
        if (sims.isEmpty()) return "Default SIM"
        return sims.firstOrNull { it.subscriptionId == subId }?.let {
            "SIM ${it.slotIndex + 1} (${it.carrierName})"
        } ?: "SIM ${sims.first().slotIndex + 1} (${sims.first().carrierName})"
    }

    suspend fun sendSms(phoneNumber: String, subId: Int, message: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val smsManager = if (subId >= 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                            .createForSubscriptionId(subId)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getSmsManagerForSubscriptionId(subId)
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                }

                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                }
            }
        }
}
