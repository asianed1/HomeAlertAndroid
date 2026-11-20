package com.homealert.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsRateLimiterTest {

    @Test
    fun enforces90SecondAntiFloodCooldownFloor() {
        val limiter = SmsRateLimiter(minIntervalMs = 90_000L)

        // First SMS allowed immediately
        assertTrue(limiter.tryAcquire(nowMs = 100_000L))

        // Snapshot cooldown might be 10s, but SMS must remain blocked at +30s and +89s
        assertFalse(limiter.tryAcquire(nowMs = 130_000L))
        assertEquals(60, limiter.remainingCooldownSeconds(130_000L))
        assertFalse(limiter.tryAcquire(nowMs = 189_999L))

        // Allowed again once 90,000ms have elapsed
        assertTrue(limiter.tryAcquire(nowMs = 190_000L))
        assertEquals(90, limiter.remainingCooldownSeconds(190_000L))
    }
}
