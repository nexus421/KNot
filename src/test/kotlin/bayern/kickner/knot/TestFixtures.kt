package bayern.kickner.knot

import bayern.kickner.knot.config.AppConfig
import bayern.kickner.knot.config.SmtpConfig
import bayern.kickner.knot.config.Target
import bayern.kickner.knot.config.TlsMode
import jakarta.mail.MessagingException
import jakarta.mail.internet.MimeMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

internal fun testTarget(
    name: String = "ops",
    apiKey: String = "ops-key-0123456789",
    to: String = "ops@example.com",
    subjectPrefix: String = "",
    bodyPrefix: String = "",
    bodyPostfix: String = "",
    smtp: SmtpConfig = testSmtp()
) = Target(name, apiKey, to, subjectPrefix, bodyPrefix, bodyPostfix, smtp)

internal fun testSmtp(
    host: String = "smtp.example.com",
    port: Int = 587,
    username: String = "knot@example.com",
    password: String = "secret-password",
    from: String = "knot@example.com",
    tls: TlsMode = TlsMode.STARTTLS
) = SmtpConfig(host, port, username, password, from, tls)

internal fun testConfig(
    defaultTarget: Target = testTarget(),
    targets: List<Target> = listOf(testTarget(name = "grafana", apiKey = "grafana-key-0123456789", to = "alerts@example.com", subjectPrefix = "[Grafana] ")),
    rateLimitPerMinute: Int = 10,
    allowApiKeyInQuery: Boolean = false,
    listenPort: Int = 8080
) = AppConfig(
    listenPort = listenPort,
    rateLimitPerMinute = rateLimitPerMinute,
    allowApiKeyInQuery = allowApiKeyInQuery,
    defaultTarget = defaultTarget,
    targets = targets
)

/** Stands in for `Transport.send`: fails the first [failures] calls with [lastError], records the rest. */
internal class RecordingTransport(
    private var failures: Int = 0,
    val lastError: MessagingException = MessagingException("Could not connect to SMTP host: smtp.example.com, port: 587")
) : (MimeMessage) -> Unit {
    // Written on Dispatchers.IO, read from the test thread
    val sent = CopyOnWriteArrayList<MimeMessage>()
    @Volatile var calls = 0

    override fun invoke(message: MimeMessage) {
        calls++
        if (failures-- > 0) throw lastError
        sent += message
    }

    /** Waits up to three seconds for [count] recorded mails. Asynchronous senders need this. */
    fun awaitMails(count: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (sent.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }
}

/** Three attempts without pausing in between. */
internal val noRetryDelays = listOf(Duration.ZERO, Duration.ZERO)
