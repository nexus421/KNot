package bayern.kickner.knot.cli

import bayern.kickner.knot.RecordingTransport
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.noRetryDelays
import bayern.kickner.knot.testConfig
import jakarta.mail.Message
import kotlinx.coroutines.runBlocking
import kotnexlib.ResultOf
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val fixedTime = ZonedDateTime.of(2026, 9, 19, 18, 40, 12, 0, ZoneId.of("Europe/Berlin"))

private suspend fun testMail(transport: RecordingTransport, targetName: String) = sendTestMail(
    config = testConfig(),
    targetName = targetName,
    mailSender = MailSender(transport, noRetryDelays),
    version = "1.2.3",
    hostname = "node-1",
    now = { fixedTime }
)

class CommandsTest {

    @Test
    fun `generated key is url-safe base64 of 32 random bytes`() {
        val key = generateApiKey()

        assertEquals(43, key.length)
        assertTrue(key.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "not url-safe: $key")
    }

    @Test
    fun `generated keys differ`() {
        assertNotEquals(generateApiKey(), generateApiKey())
    }

    @Test
    fun `test mail goes to the named target with its prefixes`() = runBlocking {
        val transport = RecordingTransport()

        val result = testMail(transport, "grafana")

        assertIs<ResultOf.Success<Unit>>(result)
        val mail = transport.sent.single()
        assertEquals("alerts@example.com", mail.getRecipients(Message.RecipientType.TO).single().toString())
        assertEquals("[Grafana] KNot test mail", mail.subject)
        val body = mail.content as String
        assertContains(body, "grafana")
        assertContains(body, "1.2.3")
        assertContains(body, "node-1")
        assertContains(body, "2026-09-19 18:40:12")
    }

    @Test
    fun `default target can be tested by name`() = runBlocking {
        val transport = RecordingTransport()

        val result = testMail(transport, "ops")

        assertIs<ResultOf.Success<Unit>>(result)
        assertEquals("ops@example.com", transport.sent.single().getRecipients(Message.RecipientType.TO).single().toString())
    }

    @Test
    fun `unknown target is a failure naming the configured targets`() = runBlocking {
        val transport = RecordingTransport()

        val result = testMail(transport, "nope")

        assertIs<ResultOf.Failure>(result)
        val message = result.message.orEmpty()
        assertContains(message, "'nope'")
        assertContains(message, "grafana")
        assertContains(message, "ops")
        assertEquals(0, transport.calls)
    }

    @Test
    fun `failed delivery is a failure naming the target`() = runBlocking {
        val transport = RecordingTransport(failures = 3)

        val result = testMail(transport, "grafana")

        assertIs<ResultOf.Failure>(result)
        assertContains(result.message.orEmpty(), "grafana")
    }
}
