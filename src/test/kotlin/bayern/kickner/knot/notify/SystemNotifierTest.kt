package bayern.kickner.knot.notify

import bayern.kickner.knot.RecordingTransport
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.noRetryDelays
import bayern.kickner.knot.testTarget
import jakarta.mail.Message
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val fixedTime = ZonedDateTime.of(2026, 9, 19, 18, 40, 12, 0, ZoneId.of("Europe/Berlin"))

private fun notifier(transport: (jakarta.mail.internet.MimeMessage) -> Unit) = SystemNotifier(
    target = testTarget(subjectPrefix = "[KNot] "),
    mailSender = MailSender(transport, noRetryDelays),
    version = "1.2.3",
    hostname = "node-1",
    now = { fixedTime }
)

class SystemNotifierTest {

    @Test
    fun `startup mail names host, time and version`() = runBlocking {
        val transport = RecordingTransport()

        notifier(transport).notifyStarted()

        val mail = transport.sent.single()
        assertEquals("ops@example.com", mail.getRecipients(Message.RecipientType.TO).single().toString())
        assertEquals("[KNot] KNot started", mail.subject)
        val body = mail.content as String
        assertContains(body, "node-1")
        assertContains(body, "2026-09-19 18:40:12")
        assertContains(body, "1.2.3")
    }

    @Test
    fun `startup mail failure does not throw`() = runBlocking {
        val transport = RecordingTransport(failures = 3)

        notifier(transport).notifyStarted()

        assertEquals(3, transport.calls)
    }

    @Test
    fun `rate limit mail names the target and its limit`() = runBlocking {
        val transport = RecordingTransport()

        notifier(transport).notifyRateLimitReached(testTarget(name = "grafana", apiKey = "grafana-key-0123456789"), limitPerMinute = 10)

        val mail = transport.sent.single()
        assertEquals("ops@example.com", mail.getRecipients(Message.RecipientType.TO).single().toString())
        assertEquals("[KNot] KNot rate limit reached: grafana", mail.subject)
        val body = mail.content as String
        assertContains(body, "'grafana'")
        assertContains(body, "10 requests per minute")
        assertContains(body, "node-1")
        assertContains(body, "2026-09-19 18:40:12")
        assertTrue(body.contains("grafana-key").not(), "the API key must not be part of the mail")
    }

    @Test
    fun `shutdown mail is sent before returning`() {
        val transport = RecordingTransport()

        notifier(transport).notifyStopped()

        val mail = transport.sent.single()
        assertEquals("[KNot] KNot stopped", mail.subject)
        val body = mail.content as String
        assertContains(body, "node-1")
        assertContains(body, "2026-09-19 18:40:12")
    }
}
