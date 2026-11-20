package com.homealert.android.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class TelegramAlertSender {

    fun sendPhotoAlert(
        botToken: String,
        chatId: String,
        caption: String,
        jpegFile: File,
        socketFactory: SocketFactory?,
        timeoutSeconds: Long = 4L
    ): Result<Unit> = runCatching {
        require(botToken.isNotBlank()) { "Telegram Bot Token is empty" }
        require(chatId.isNotBlank()) { "Telegram Chat ID is empty" }
        require(jpegFile.exists()) { "Snapshot file missing" }

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)

        if (socketFactory != null) {
            clientBuilder.socketFactory(socketFactory)
        }

        val client = clientBuilder.build()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.trim())
            .addFormDataPart("caption", caption)
            .addFormDataPart(
                "photo",
                jpegFile.name,
                jpegFile.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://api.telegram.org/bot${botToken.trim()}/sendPhoto")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty().take(200)
                error("Telegram HTTP ${response.code}: $body")
            }
        }
    }
}
