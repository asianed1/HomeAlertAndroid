package com.homealert.android.network

import com.homealert.android.data.model.AppSettings
import com.homealert.android.data.model.DeliveryTier
import com.homealert.android.data.model.NetworkMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.SocketFactory

class NetworkFailoverEngineTest {

    private class FakeGateway(
        var wifiAvailable: Boolean = true,
        var wifiUploadSuccess: Boolean = true,
        var cellularAvailable: Boolean = true,
        var cellularUploadSuccess: Boolean = true,
        var smsSuccess: Boolean = true
    ) : FailoverTransportGateway {
        var cellularRequested = false
        var cellularReleased = false
        var smsSentCount = 0
        var lastSmsMessage: String? = null

        override suspend fun getValidatedWifiSocketFactory(): SocketFactory? =
            if (wifiAvailable) SocketFactory.getDefault() else null

        override suspend fun acquireCellularSocketFactory(timeoutMs: Long): Pair<SocketFactory, () -> Unit>? {
            cellularRequested = true
            return if (cellularAvailable) {
                SocketFactory.getDefault() to { cellularReleased = true }
            } else null
        }

        override suspend fun sendPhotoViaSocket(
            tierLabel: String,
            socketFactory: SocketFactory,
            settings: AppSettings
        ): Result<String> {
            return if (tierLabel == "Wi-Fi") {
                if (wifiUploadSuccess) Result.success("Telegram") else Result.failure(RuntimeException("Wi-Fi timeout"))
            } else {
                if (cellularUploadSuccess) Result.success("Telegram") else Result.failure(RuntimeException("Cell timeout"))
            }
        }

        override suspend fun sendSmsFallback(phoneNumber: String, subId: Int, message: String): Result<Unit> {
            smsSentCount++
            lastSmsMessage = message
            return if (smsSuccess) Result.success(Unit) else Result.failure(RuntimeException("No SIM"))
        }
    }

    @Test
    fun modeAWifiOnlyNeverTouchesCellularOrSmsWhenWifiFails() = runTest {
        val engine = NetworkFailoverEngine()
        val gateway = FakeGateway(wifiAvailable = false, cellularAvailable = true, smsSuccess = true)
        val settings = AppSettings(
            networkMode = NetworkMode.WIFI_ONLY,
            smsFallbackNumber = "+15550001111"
        )

        val outcome = engine.executeFailover(settings, "Front Hallway", 1_700_000_000_000L, gateway)
        assertEquals(DeliveryTier.LOCAL_ONLY, outcome.tier)
        assertFalse(gateway.cellularRequested)
        assertEquals(0, gateway.smsSentCount)
    }

    @Test
    fun modeBUsesTier1WifiWhenValidated() = runTest {
        val engine = NetworkFailoverEngine()
        val gateway = FakeGateway(wifiAvailable = true, wifiUploadSuccess = true)
        val settings = AppSettings(networkMode = NetworkMode.SMART_AUTO_FAILOVER)

        val outcome = engine.executeFailover(settings, "Garage", 1_700_000_000_000L, gateway)
        assertEquals(DeliveryTier.WIFI, outcome.tier)
        assertFalse(gateway.cellularRequested)
        assertEquals(0, gateway.smsSentCount)
    }

    @Test
    fun modeBFailsOverToTier2TelcoDataAndReleasesCallback() = runTest {
        val engine = NetworkFailoverEngine()
        val gateway = FakeGateway(wifiAvailable = false, cellularAvailable = true, cellularUploadSuccess = true)
        val settings = AppSettings(networkMode = NetworkMode.SMART_AUTO_FAILOVER)

        val outcome = engine.executeFailover(settings, "Back Patio", 1_700_000_000_000L, gateway)
        assertEquals(DeliveryTier.TELCO_DATA, outcome.tier)
        assertTrue(gateway.cellularRequested)
        assertTrue(gateway.cellularReleased)
        assertEquals(0, gateway.smsSentCount)
    }

    @Test
    fun modeBFallsBackToTier3SmsAndHonorsRateLimit() = runTest {
        var fakeClock = 1_700_000_000_000L
        val engine = NetworkFailoverEngine(
            smsRateLimiter = SmsRateLimiter(90_000L),
            clockMs = { fakeClock }
        )
        val gateway = FakeGateway(
            wifiAvailable = true,
            wifiUploadSuccess = false, // e.g. Wi-Fi router powered, outside fiber cut -> 4s timeout
            cellularAvailable = false,
            smsSuccess = true
        )
        val settings = AppSettings(
            networkMode = NetworkMode.SMART_AUTO_FAILOVER,
            smsFallbackNumber = "+15551234567"
        )

        val first = engine.executeFailover(settings, "Front Hallway", fakeClock, gateway)
        assertEquals(DeliveryTier.TELCO_SMS, first.tier)
        assertEquals(1, gateway.smsSentCount)
        assertTrue(gateway.lastSmsMessage!!.contains("Front Hallway"))

        // 20 seconds later: SMS rate limiter blocks second SMS flood
        fakeClock += 20_000L
        val second = engine.executeFailover(settings, "Front Hallway", fakeClock, gateway)
        assertEquals(DeliveryTier.LOCAL_ONLY, second.tier)
        assertEquals(1, gateway.smsSentCount)
    }
}
