package com.homealert.android.network

/**
 * Independent SMS Anti-Flood Guard: enforces a strict minimum cooldown floor (default 90 seconds)
 * between fallback SMS transmissions regardless of the shorter snapshot cooldown slider,
 * preventing Android's unattended ~30-SMS-per-window confirmation popup.
 */
class SmsRateLimiter(
    private val minIntervalMs: Long = 90_000L
) {
    @Volatile
    private var lastSmsSentAtMs: Long = Long.MIN_VALUE / 2

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (nowMs - lastSmsSentAtMs >= minIntervalMs) {
            lastSmsSentAtMs = nowMs
            return true
        }
        return false
    }

    @Synchronized
    fun remainingCooldownSeconds(nowMs: Long): Int {
        val elapsed = nowMs - lastSmsSentAtMs
        if (elapsed >= minIntervalMs) return 0
        return ((minIntervalMs - elapsed + 999L) / 1000L).toInt().coerceAtLeast(1)
    }

    @Synchronized
    fun reset() {
        lastSmsSentAtMs = Long.MIN_VALUE / 2
    }
}
