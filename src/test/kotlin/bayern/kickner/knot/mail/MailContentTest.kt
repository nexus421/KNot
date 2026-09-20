package bayern.kickner.knot.mail

import bayern.kickner.knot.testTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class MailContentTest {

    @Test
    fun `subject prefix is prepended without a separator`() {
        val content = testTarget(subjectPrefix = "[Grafana] ").compose(subject = "HighCPU firing", body = "CPU > 90%")

        assertEquals("[Grafana] HighCPU firing", content.subject)
    }

    @Test
    fun `missing or blank subject falls back to the fixed default`() {
        assertEquals("KNot Message", testTarget().compose(subject = null, body = "x").subject)
        assertEquals("KNot Message", testTarget().compose(subject = "  ", body = "x").subject)
        assertEquals("[KNot] KNot Message", testTarget(subjectPrefix = "[KNot] ").compose(subject = null, body = "x").subject)
    }

    @Test
    fun `body is wrapped by prefix and postfix without separators`() {
        val content = testTarget(bodyPrefix = "Alert:\n", bodyPostfix = "\n-- KNot").compose(subject = "s", body = "CPU > 90%")

        assertEquals("Alert:\nCPU > 90%\n-- KNot", content.body)
    }

    @Test
    fun `line breaks in the subject are collapsed to spaces`() {
        val content = testTarget().compose(subject = "Alert\r\nBcc: attacker@example.com", body = "x")

        assertEquals("Alert Bcc: attacker@example.com", content.subject)
    }
}
