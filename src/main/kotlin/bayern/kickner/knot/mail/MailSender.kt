package bayern.kickner.knot.mail

import bayern.kickner.klogger.errorLog
import bayern.kickner.klogger.warnLog
import bayern.kickner.knot.config.SmtpConfig
import bayern.kickner.knot.config.Target
import bayern.kickner.knot.config.TlsMode
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotnexlib.ResultOf
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val CONNECT_TIMEOUT_MS = 5_000
private const val IO_TIMEOUT_MS = 10_000

/** Pauses before the second and third attempt. A hanging SMTP server is bounded by the timeouts above. */
private val defaultRetryDelays = listOf(1.seconds, 3.seconds)

/**
 * Sends mails over SMTP via Jakarta Mail, retrying transient failures.
 *
 * @param transport Delivers a message, defaults to [Transport.send]. Injectable so tests need no SMTP server.
 * @param retryDelays Pause before each retry. The number of attempts is one more than the number of delays.
 */
class MailSender(
    private val transport: (MimeMessage) -> Unit = { Transport.send(it) },
    private val retryDelays: List<Duration> = defaultRetryDelays
) {

    private val maxAttempts = retryDelays.size + 1

    /**
     * Sends [content] to [target] synchronously, on [Dispatchers.IO]. Every attempt is logged on failure.
     * The returned failure names the target and the last error, never credentials. Rejected credentials are
     * not retried: they cannot fix themselves, and repeated failed logins get accounts locked.
     */
    suspend fun send(target: Target, content: MailContent): ResultOf<Unit> {
        val session = Session.getInstance(target.smtp.properties, target.smtp.authenticator())
        val message = buildMessage(session, target, content)

        var lastError: Throwable? = null
        var attempts = 0
        for (attempt in 1..maxAttempts) {
            attempts = attempt
            val error = withContext(Dispatchers.IO) { runCatching { transport(message) } }.exceptionOrNull()
            if (error == null) return ResultOf.Success(Unit)

            lastError = error
            warnLog { "Mail for target '${target.name}' failed on attempt $attempt/$maxAttempts: ${error.describe()}" }
            if (error is AuthenticationFailedException) break
            retryDelays.getOrNull(attempt - 1)?.let { delay(it) }
        }

        val noun = if (attempts == 1) "attempt" else "attempts"
        val summary = "Mail for target '${target.name}' could not be sent after $attempts $noun: ${lastError?.describe()}"
        errorLog(summary, lastError)
        return ResultOf.Failure(summary, lastError)
    }
}

/**
 * Jakarta Mail session properties for [smtp]: encryption per [SmtpConfig.tls] with certificate hostname
 * verification, SMTP AUTH, and finite timeouts (Jakarta Mail's defaults are infinite).
 */
fun smtpProperties(smtp: SmtpConfig): Properties = Properties().apply {
    put("mail.smtp.host", smtp.host)
    put("mail.smtp.port", smtp.port.toString())
    put("mail.smtp.auth", "true")
    put("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
    put("mail.smtp.timeout", IO_TIMEOUT_MS.toString())
    put("mail.smtp.writetimeout", IO_TIMEOUT_MS.toString())

    when (smtp.tls) {
        TlsMode.STARTTLS -> {
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
        }
        TlsMode.SSL -> {
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
        }
        TlsMode.NONE -> Unit
    }
}

/**
 * Plain-text message from [target]'s sender to its recipient. The charset is explicit: without it Jakarta Mail
 * falls back to the platform encoding and umlauts break.
 */
fun buildMessage(session: Session, target: Target, content: MailContent): MimeMessage = MimeMessage(session).apply {
    setFrom(InternetAddress(target.smtp.from))
    setRecipient(Message.RecipientType.TO, InternetAddress(target.to))
    setSubject(content.subject, "UTF-8")
    setText(content.body, "UTF-8")
}

private fun SmtpConfig.authenticator() = object : Authenticator() {
    override fun getPasswordAuthentication() = PasswordAuthentication(username, password)
}

private fun Throwable.describe() = message ?: this::class.simpleName ?: "unknown error"
