package bayern.kickner.knot.mail

import bayern.kickner.knot.config.TlsMode
import bayern.kickner.knot.RecordingTransport
import bayern.kickner.knot.noRetryDelays
import bayern.kickner.knot.testSmtp
import bayern.kickner.knot.testTarget
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.Session
import kotlinx.coroutines.runBlocking
import kotnexlib.ResultOf
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class MailSenderTest {

    @Test
    fun `starttls mode requires starttls and verifies the server identity`() {
        val props = smtpProperties(testSmtp(tls = TlsMode.STARTTLS))

        assertEquals("true", props.getProperty("mail.smtp.starttls.enable"))
        assertEquals("true", props.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertNull(props.getProperty("mail.smtp.ssl.enable"))
    }

    @Test
    fun `ssl mode uses implicit tls and verifies the server identity`() {
        val props = smtpProperties(testSmtp(tls = TlsMode.SSL, port = 465))

        assertEquals("true", props.getProperty("mail.smtp.ssl.enable"))
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertNull(props.getProperty("mail.smtp.starttls.enable"))
    }

    @Test
    fun `none mode configures no encryption`() {
        val props = smtpProperties(testSmtp(tls = TlsMode.NONE))

        assertNull(props.getProperty("mail.smtp.ssl.enable"))
        assertNull(props.getProperty("mail.smtp.starttls.enable"))
    }

    @Test
    fun `connection is authenticated and every timeout is finite`() {
        val props = smtpProperties(testSmtp(host = "mail.example.org", port = 2525))

        assertEquals("mail.example.org", props.getProperty("mail.smtp.host"))
        assertEquals("2525", props.getProperty("mail.smtp.port"))
        assertEquals("true", props.getProperty("mail.smtp.auth"))
        assertEquals("5000", props.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("10000", props.getProperty("mail.smtp.timeout"))
        assertEquals("10000", props.getProperty("mail.smtp.writetimeout"))
    }

    @Test
    fun `message carries sender recipient subject and utf-8 text`() {
        val target = testTarget(to = "alerts@example.com", smtp = testSmtp(from = "knot@example.com"))
        val message = buildMessage(Session.getInstance(Properties()), target, MailContent("Crème brûlée à Ærø", "Ça va?"))
        message.saveChanges()

        assertEquals("knot@example.com", message.from.single().toString())
        assertEquals("alerts@example.com", message.getRecipients(Message.RecipientType.TO).single().toString())
        assertEquals("Crème brûlée à Ærø", message.subject)
        assertContains(message.getHeader("Subject").single(), "=?UTF-8?")
        assertContains(message.getHeader("Content-Type").single(), "charset=UTF-8")
        assertEquals("Ça va?", message.content)
    }

    @Test
    fun `send delivers the message with the target's smtp settings`() = runBlocking {
        val transport = RecordingTransport()
        val target = testTarget(smtp = testSmtp(host = "relay.example.com"))

        val result = MailSender(transport, noRetryDelays).send(target, MailContent("s", "b"))

        assertIs<ResultOf.Success<Unit>>(result)
        assertEquals(1, transport.calls)
        assertEquals("relay.example.com", transport.sent.single().session.getProperty("mail.smtp.host"))
        assertEquals("s", transport.sent.single().subject)
    }

    @Test
    fun `send retries after a failed attempt`() = runBlocking {
        val transport = RecordingTransport(failures = 2)

        val result = MailSender(transport, noRetryDelays).send(testTarget(), MailContent("s", "b"))

        assertIs<ResultOf.Success<Unit>>(result)
        assertEquals(3, transport.calls)
    }

    @Test
    fun `send does not retry when the credentials are rejected`() = runBlocking {
        val transport = RecordingTransport(failures = 5, lastError = AuthenticationFailedException("535 5.7.8 Authentication failed"))

        val result = MailSender(transport, noRetryDelays).send(testTarget(name = "grafana"), MailContent("s", "b"))

        val failure = assertIs<ResultOf.Failure>(result)
        assertEquals(1, transport.calls)
        assertContains(failure.message.orEmpty(), "grafana")
        assertSame(transport.lastError, failure.throwable)
    }

    @Test
    fun `send gives up after the last attempt`() = runBlocking {
        val transport = RecordingTransport(failures = 5)

        val result = MailSender(transport, noRetryDelays).send(testTarget(name = "grafana"), MailContent("s", "b"))

        val failure = assertIs<ResultOf.Failure>(result)
        assertEquals(3, transport.calls)
        assertContains(failure.message.orEmpty(), "grafana")
        assertContains(failure.message.orEmpty(), "3 attempts")
        assertFalse(failure.message.orEmpty().contains("secret-password"))
        assertSame(transport.lastError, failure.throwable)
    }
}
