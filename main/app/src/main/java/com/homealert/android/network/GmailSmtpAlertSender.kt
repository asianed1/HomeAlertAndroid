package com.homealert.android.network

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

class GmailSmtpAlertSender {

    fun sendEmailAlert(
        senderEmail: String,
        appPassword: String,
        recipientEmails: String,
        subject: String,
        bodyText: String,
        attachmentFile: File,
        port: Int = 587,
        socketFactory: SocketFactory?,
        timeoutMs: Int = 4000
    ): Result<Unit> = runCatching {
        require(senderEmail.isNotBlank()) { "Sender Gmail address is empty" }
        require(appPassword.isNotBlank()) { "Google App Password is empty" }
        require(recipientEmails.isNotBlank()) { "Recipient email list is empty" }

        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", timeoutMs.toString())
            put("mail.smtp.timeout", timeoutMs.toString())
            put("mail.smtp.writetimeout", timeoutMs.toString())

            if (port == 465) {
                put("mail.smtp.port", "465")
                put("mail.smtp.ssl.enable", "true")
                if (socketFactory != null) {
                    put("mail.smtp.ssl.socketFactory", NetworkBoundSSLSocketFactory(socketFactory))
                    put("mail.smtp.ssl.socketFactory.fallback", "false")
                }
            } else {
                put("mail.smtp.port", "587")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                if (socketFactory != null) {
                    put("mail.smtp.socketFactory", socketFactory)
                    put("mail.smtp.socketFactory.fallback", "false")
                }
            }
        }

        val cleanPassword = appPassword.replace(" ", "")
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(senderEmail.trim(), cleanPassword)
            }
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(senderEmail.trim()))
            val recipients = recipientEmails
                .split(",", ";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { InternetAddress(it) }
                .toTypedArray()
            setRecipients(Message.RecipientType.TO, recipients)
            setSubject(subject)

            val textPart = MimeBodyPart().apply {
                setText(bodyText, "utf-8")
            }
            val filePart = MimeBodyPart().apply {
                dataHandler = DataHandler(FileDataSource(attachmentFile))
                fileName = attachmentFile.name
            }
            val multipart = MimeMultipart().apply {
                addBodyPart(textPart)
                addBodyPart(filePart)
            }
            setContent(multipart)
        }

        Transport.send(message)
    }

    /**
     * Wraps Android's Network.socketFactory inside an SSLSocketFactory for SMTPS (port 465)
     * without static getDefault() overrides or classloader reflection.
     */
    private class NetworkBoundSSLSocketFactory(
        private val networkSocketFactory: SocketFactory
    ) : SSLSocketFactory() {

        private val baseSslFactory: SSLSocketFactory =
            SSLSocketFactory.getDefault() as SSLSocketFactory

        override fun getDefaultCipherSuites(): Array<String> = baseSslFactory.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = baseSslFactory.supportedCipherSuites

        override fun createSocket(): Socket = networkSocketFactory.createSocket()

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            baseSslFactory.createSocket(s, host, port, autoClose)

        override fun createSocket(host: String?, port: Int): Socket {
            val raw = networkSocketFactory.createSocket(host, port)
            return baseSslFactory.createSocket(raw, host, port, true)
        }

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int
        ): Socket {
            val raw = networkSocketFactory.createSocket(host, port, localHost, localPort)
            return baseSslFactory.createSocket(raw, host, port, true)
        }

        override fun createSocket(host: InetAddress?, port: Int): Socket {
            val raw = networkSocketFactory.createSocket(host, port)
            return baseSslFactory.createSocket(raw, host?.hostName, port, true)
        }

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket {
            val raw = networkSocketFactory.createSocket(address, port, localAddress, localPort)
            return baseSslFactory.createSocket(raw, address?.hostName, port, true)
        }
    }
}
