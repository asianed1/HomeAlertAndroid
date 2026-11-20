package com.homealert.android.network

import android.content.Context
import com.homealert.android.data.SettingsRepository
import com.homealert.android.data.model.AlertChannel
import com.homealert.android.data.model.AppSettings
import com.homealert.android.data.model.DeliveryTier
import com.homealert.android.data.model.DiagnosticTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.SocketFactory

class AlertDispatcher(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val gateway = AndroidNetworkGateway(context)
    private val smsRateLimiter = SmsRateLimiter(minIntervalMs = 90_000L)
    private val failoverEngine = NetworkFailoverEngine(smsRateLimiter = smsRateLimiter)
    private val telegramSender = TelegramAlertSender()
    private val gmailSender = GmailSmtpAlertSender()

    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryQueue = ConcurrentLinkedQueue<QueuedAlert>()

    private data class QueuedAlert(
        val fileName: String,
        val compressedFile: File,
        val cameraLabel: String,
        val motionPercent: Float,
        val timestampMillis: Long,
        val attempts: Int = 0
    )

    fun inspectHealth(settings: AppSettings) = gateway.inspectNetworkHealth(settings)
    fun getAvailableSims() = gateway.getAvailableSims()

    suspend fun dispatchSnapshotAlert(
        fileName: String,
        compressedFile: File,
        cameraLabel: String,
        motionPercent: Float,
        timestampMillis: Long,
        settings: AppSettings,
        enqueueOnFailure: Boolean = true
    ): FailoverOutcome = withContext(Dispatchers.IO) {
        val transport = createTransport(compressedFile, cameraLabel, motionPercent, timestampMillis)
        val outcome = failoverEngine.executeFailover(
            settings = settings,
            cameraLabel = cameraLabel,
            timestampMs = timestampMillis,
            gateway = transport
        )

        settingsRepository.saveDeliveryMetadata(
            fileName = fileName,
            cameraLabel = cameraLabel,
            motionPercent = motionPercent,
            deliveryStatus = outcome.statusBadge,
            tier = outcome.tier
        )

        if (outcome.tier == DeliveryTier.LOCAL_ONLY && enqueueOnFailure && retryQueue.size < 10) {
            retryQueue.offer(
                QueuedAlert(
                    fileName = fileName,
                    compressedFile = compressedFile,
                    cameraLabel = cameraLabel,
                    motionPercent = motionPercent,
                    timestampMillis = timestampMillis,
                    attempts = 1
                )
            )
            scheduleRetryDrain()
        }

        outcome
    }

    private fun scheduleRetryDrain() {
        retryScope.launch {
            delay(15_000L)
            val item = retryQueue.poll() ?: return@launch
            if (!item.compressedFile.exists()) return@launch
            if (System.currentTimeMillis() - item.timestampMillis > 5 * 60_000L) return@launch

            val currentSettings = settingsRepository.settings.value
            val outcome = dispatchSnapshotAlert(
                fileName = item.fileName,
                compressedFile = item.compressedFile,
                cameraLabel = item.cameraLabel,
                motionPercent = item.motionPercent,
                timestampMillis = item.timestampMillis,
                settings = currentSettings,
                enqueueOnFailure = false
            )

            if (outcome.tier == DeliveryTier.LOCAL_ONLY && item.attempts < 3) {
                retryQueue.offer(item.copy(attempts = item.attempts + 1))
                scheduleRetryDrain()
            }
        }
    }

    private fun createTransport(
        compressedFile: File,
        cameraLabel: String,
        motionPercent: Float,
        timestampMillis: Long
    ): FailoverTransportGateway {
        val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))

        return object : FailoverTransportGateway {
            override suspend fun getValidatedWifiSocketFactory(): SocketFactory? {
                return gateway.findValidatedWifiNetwork()?.socketFactory
            }

            override suspend fun acquireCellularSocketFactory(timeoutMs: Long): Pair<SocketFactory, () -> Unit>? {
                val pair = gateway.requestCellularNetwork(timeoutMs) ?: return null
                return pair.first.socketFactory to pair.second
            }

            override suspend fun sendPhotoViaSocket(
                tierLabel: String,
                socketFactory: SocketFactory,
                settings: AppSettings
            ): Result<String> {
                val caption = buildString {
                    append("🚨 HomeAlert Motion Detected\n")
                    append("Camera: $cameraLabel\n")
                    append("Time: $dateText\n")
                    append("Motion Area: ${String.format(Locale.US, "%.1f", motionPercent)}%\n")
                    append("Network: $tierLabel")
                }

                return when (settings.alertChannel) {
                    AlertChannel.TELEGRAM -> {
                        telegramSender.sendPhotoAlert(
                            botToken = settings.telegramBotToken,
                            chatId = settings.telegramChatId,
                            caption = caption,
                            jpegFile = compressedFile,
                            socketFactory = socketFactory,
                            timeoutSeconds = 4L
                        ).map { "Telegram" }
                    }

                    AlertChannel.GMAIL -> {
                        gmailSender.sendEmailAlert(
                            senderEmail = settings.gmailSender,
                            appPassword = settings.gmailAppPassword,
                            recipientEmails = settings.gmailRecipients,
                            subject = "🚨 HomeAlert: Motion at $cameraLabel ($dateText)",
                            bodyText = caption,
                            attachmentFile = compressedFile,
                            port = settings.gmailPort,
                            socketFactory = socketFactory,
                            timeoutMs = 4000
                        ).map { "Gmail" }
                    }

                    AlertChannel.BOTH -> coroutineScope {
                        val tgDeferred = async {
                            telegramSender.sendPhotoAlert(
                                botToken = settings.telegramBotToken,
                                chatId = settings.telegramChatId,
                                caption = caption,
                                jpegFile = compressedFile,
                                socketFactory = socketFactory,
                                timeoutSeconds = 4L
                            )
                        }
                        val mailDeferred = async {
                            gmailSender.sendEmailAlert(
                                senderEmail = settings.gmailSender,
                                appPassword = settings.gmailAppPassword,
                                recipientEmails = settings.gmailRecipients,
                                subject = "🚨 HomeAlert: Motion at $cameraLabel ($dateText)",
                                bodyText = caption,
                                attachmentFile = compressedFile,
                                port = settings.gmailPort,
                                socketFactory = socketFactory,
                                timeoutMs = 4000
                            )
                        }
                        val tgRes = tgDeferred.await()
                        val mailRes = mailDeferred.await()
                        when {
                            tgRes.isSuccess && mailRes.isSuccess -> Result.success("Telegram + Gmail")
                            tgRes.isSuccess -> Result.success("Telegram (Gmail failed)")
                            mailRes.isSuccess -> Result.success("Gmail (Telegram failed)")
                            else -> Result.failure(
                                tgRes.exceptionOrNull()
                                    ?: mailRes.exceptionOrNull()
                                    ?: IllegalStateException("Both channels failed")
                            )
                        }
                    }
                }
            }

            override suspend fun sendSmsFallback(
                phoneNumber: String,
                subId: Int,
                message: String
            ): Result<Unit> {
                return gateway.sendSms(phoneNumber, subId, message)
            }
        }
    }

    /**
     * Explicit Diagnostic Test Helpers for Settings tab before homeowner leaves home.
     */
    suspend fun runTestWifiAlert(settings: AppSettings, testImageFile: File): DiagnosticTestResult =
        withContext(Dispatchers.IO) {
            val wifiNet = gateway.findValidatedWifiNetwork()
                ?: return@withContext DiagnosticTestResult(
                    tierName = "Tier 1: Wi-Fi",
                    success = false,
                    message = "No validated Wi-Fi network detected (missing NET_CAPABILITY_VALIDATED)."
                )
            val transport = createTransport(testImageFile, settings.cameraLabel, 100f, System.currentTimeMillis())
            val res = transport.sendPhotoViaSocket("Wi-Fi (Diagnostic Test)", wifiNet.socketFactory, settings)
            if (res.isSuccess) {
                DiagnosticTestResult("Tier 1: Wi-Fi", true, "Delivered via Wi-Fi (${res.getOrNull()})")
            } else {
                DiagnosticTestResult("Tier 1: Wi-Fi", false, res.exceptionOrNull()?.message ?: "Failed")
            }
        }

    suspend fun runTestTelcoDataAlert(settings: AppSettings, testImageFile: File): DiagnosticTestResult =
        withContext(Dispatchers.IO) {
            val pair = gateway.requestCellularNetwork(timeoutMs = 5_000L)
                ?: return@withContext DiagnosticTestResult(
                    tierName = "Tier 2: Telco Data",
                    success = false,
                    message = "Could not acquire cellular data network (no SIM data plan or signal)."
                )
            val (cellNet, release) = pair
            try {
                val transport = createTransport(testImageFile, settings.cameraLabel, 100f, System.currentTimeMillis())
                val res = transport.sendPhotoViaSocket("Telco Data (Diagnostic Test)", cellNet.socketFactory, settings)
                if (res.isSuccess) {
                    DiagnosticTestResult("Tier 2: Telco Data", true, "Delivered via Telco Data (${res.getOrNull()})")
                } else {
                    DiagnosticTestResult("Tier 2: Telco Data", false, res.exceptionOrNull()?.message ?: "Failed")
                }
            } finally {
                release()
            }
        }

    suspend fun runTestFallbackSms(settings: AppSettings): DiagnosticTestResult =
        withContext(Dispatchers.IO) {
            val number = settings.smsFallbackNumber.trim()
            if (number.isEmpty()) {
                return@withContext DiagnosticTestResult(
                    tierName = "Tier 3: Telco SMS",
                    success = false,
                    message = "Enter an SMS Fallback Phone Number first."
                )
            }
            val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val msg = "🧪 HomeAlert Diagnostic Test: Fallback SMS confirmed for ${settings.cameraLabel} on $dateText."
            val res = gateway.sendSms(number, settings.selectedSimSubId, msg)
            if (res.isSuccess) {
                DiagnosticTestResult("Tier 3: Telco SMS", true, "Test SMS queued to $number.")
            } else {
                DiagnosticTestResult("Tier 3: Telco SMS", false, res.exceptionOrNull()?.message ?: "SMS failed")
            }
        }
}
