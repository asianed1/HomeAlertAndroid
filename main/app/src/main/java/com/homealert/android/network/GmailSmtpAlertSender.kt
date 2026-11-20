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
                    NetworkBoundSocketFactory.bind(socketFactory)
                    put("mail.smtp.ssl.socketFactory.class", NetworkBoundSocketFactory::class.java.name)
                    put("mail.smtp.ssl.socketFactory.fallback", "false")
                }
            } else {
                put("mail.smtp.port", "587")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                if (socketFactory != null) {
                    NetworkBoundSocketFactory.bind(socketFactory)
                    put("mail.smtp.socketFactory.class", NetworkBoundSocketFactory::class.java.name)
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

        try {
            Transport.send(message)
        } finally {
            NetworkBoundSocketFactory.clear()
        }
    }

    /**
     * Bridges JavaMail's class-name-based SocketFactory lookup with Android's Network.socketFactory
     * so SMTP traffic is bound strictly to Wi-Fi or Telco Cellular Network.
     */
    class NetworkBoundSocketFactory : SocketFactory() {
        companion object {
            private val threadDelegate = ThreadLocal<SocketFactory?>()

            @Volatile
            private var fallbackDelegate: SocketFactory? = null

            fun bind(factory: SocketFactory) {
                threadDelegate.set(factory)
                fallbackDelegate = factory
            }

            fun clear() {
                threadDelegate.remove()
            }

            @JvmStatic
            fun getDefault(): SocketFactory = NetworkBoundSocketFactory()
        }

        private val delegate: SocketFactory
            get() = threadDelegate.get() ?: fallbackDelegate ?: SocketFactory.getDefault()

        override fun createSocket(): Socket = delegate.createSocket()
        override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            delegate.createSocket(address, port, localAddress, localPort)
    }
}
