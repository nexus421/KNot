package bayern.kickner.knot.routes

import bayern.kickner.knot.RecordingTransport
import bayern.kickner.knot.config.AppConfig
import bayern.kickner.knot.knot
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.noRetryDelays
import bayern.kickner.knot.notify.SystemNotifier
import bayern.kickner.knot.ratelimit.RateLimiter
import bayern.kickner.knot.testConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import jakarta.mail.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val GRAFANA_KEY = "grafana-key-0123456789"
private const val OPS_KEY = "ops-key-0123456789"

private fun ApplicationTestBuilder.knotApp(
    config: AppConfig = testConfig(),
    transport: RecordingTransport = RecordingTransport(),
    systemMails: Boolean = false
) {
    val mailSender = MailSender(transport, noRetryDelays)
    val notifier = if (systemMails) SystemNotifier(config.defaultTarget, mailSender, version = "test", hostname = "node-1") else null
    application { knot(config, mailSender, RateLimiter(config.rateLimitPerMinute), notifier) }
}

class HookRouteTest {

    @Test
    fun `health answers without authentication`() = testApplication {
        knotApp()

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `request without api key is unauthorized`() = testApplication {
        val transport = RecordingTransport()
        knotApp(transport = transport)

        val response = client.post("/hook") { setBody("""{"body": "x"}""") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `unknown api key is unauthorized`() = testApplication {
        knotApp()

        assertEquals(HttpStatusCode.Unauthorized, hook(apiKey = "no-such-key-0123456789").status)
        assertEquals(HttpStatusCode.Unauthorized, hook(apiKey = GRAFANA_KEY.dropLast(1)).status)
        assertEquals(HttpStatusCode.Unauthorized, hook(apiKey = "$GRAFANA_KEY-suffix").status)
    }

    @Test
    fun `api key in the header selects the target and sends the framed mail`() = testApplication {
        val transport = RecordingTransport()
        knotApp(transport = transport)

        val response = hook(apiKey = GRAFANA_KEY, body = """{"subject": "HighCPU firing", "body": "CPU > 90%"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
        val mail = transport.sent.single()
        assertEquals("alerts@example.com", mail.getRecipients(Message.RecipientType.TO).single().toString())
        assertEquals("[Grafana] HighCPU firing", mail.subject)
        assertEquals("CPU > 90%", mail.content)
    }

    @Test
    fun `default target is a regular target as well`() = testApplication {
        val transport = RecordingTransport()
        knotApp(transport = transport)

        assertEquals(HttpStatusCode.OK, hook(apiKey = OPS_KEY).status)
        assertEquals("ops@example.com", transport.sent.single().getRecipients(Message.RecipientType.TO).single().toString())
    }

    @Test
    fun `subject falls back to the default when missing`() = testApplication {
        val transport = RecordingTransport()
        knotApp(transport = transport)

        hook(apiKey = OPS_KEY, body = """{"body": "x"}""")

        assertEquals("KNot Message", transport.sent.single().subject)
    }

    @Test
    fun `query api key is ignored unless enabled`() = testApplication {
        knotApp(config = testConfig(allowApiKeyInQuery = false))

        val response = client.post("/hook?apiKey=$GRAFANA_KEY") { setBody("""{"body": "x"}""") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `query api key is accepted when enabled`() = testApplication {
        val transport = RecordingTransport()
        knotApp(config = testConfig(allowApiKeyInQuery = true), transport = transport)

        val response = client.post("/hook?apiKey=$GRAFANA_KEY") { setBody("""{"body": "x"}""") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("alerts@example.com", transport.sent.single().getRecipients(Message.RecipientType.TO).single().toString())
    }

    @Test
    fun `header api key wins over the query parameter`() = testApplication {
        val transport = RecordingTransport()
        knotApp(config = testConfig(allowApiKeyInQuery = true), transport = transport)

        val response = client.post("/hook?apiKey=$OPS_KEY") {
            header("X-API-Key", GRAFANA_KEY)
            setBody("""{"body": "x"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("alerts@example.com", transport.sent.single().getRecipients(Message.RecipientType.TO).single().toString())
    }

    @Test
    fun `missing, null or blank body is rejected naming the field`() = testApplication {
        val transport = RecordingTransport()
        knotApp(transport = transport)

        for (payload in listOf("""{"subject": "s"}""", """{"body": null}""", """{"body": "  "}""")) {
            val response = hook(apiKey = OPS_KEY, body = payload)
            assertEquals(HttpStatusCode.BadRequest, response.status, payload)
            assertEquals("Field 'body' must not be empty", response.bodyAsText(), payload)
        }
        assertEquals(0, transport.calls)
    }

    @Test
    fun `broken json or a wrong field type is rejected as invalid payload`() = testApplication {
        val transport = RecordingTransport()
        knotApp(transport = transport)

        for (payload in listOf("not json", """{"body": 42}""")) {
            val response = hook(apiKey = OPS_KEY, body = payload)
            assertEquals(HttpStatusCode.BadRequest, response.status, payload)
            assertEquals("Invalid JSON payload", response.bodyAsText(), payload)
        }
        assertEquals(0, transport.calls)
    }

    @Test
    fun `unknown payload fields are ignored`() = testApplication {
        knotApp()

        val response = hook(apiKey = OPS_KEY, body = """{"body": "x", "severity": "high", "labels": {"a": 1}}""")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `json is accepted regardless of the content type`() = testApplication {
        knotApp()

        val response = client.post("/hook") {
            header("X-API-Key", OPS_KEY)
            contentType(ContentType.Text.Plain)
            setBody("""{"body": "x"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `requests above the rate limit are rejected without sending`() = testApplication {
        val transport = RecordingTransport()
        knotApp(config = testConfig(rateLimitPerMinute = 2), transport = transport)

        assertEquals(HttpStatusCode.OK, hook(apiKey = GRAFANA_KEY).status)
        assertEquals(HttpStatusCode.OK, hook(apiKey = GRAFANA_KEY).status)
        assertEquals(HttpStatusCode.TooManyRequests, hook(apiKey = GRAFANA_KEY).status)
        assertEquals(HttpStatusCode.OK, hook(apiKey = OPS_KEY).status)
        assertEquals(3, transport.calls)
    }

    @Test
    fun `reaching the rate limit sends one system mail per episode`() = testApplication {
        val transport = RecordingTransport()
        knotApp(config = testConfig(rateLimitPerMinute = 1), transport = transport, systemMails = true)

        assertEquals(HttpStatusCode.OK, hook(apiKey = GRAFANA_KEY).status)
        assertEquals(HttpStatusCode.TooManyRequests, hook(apiKey = GRAFANA_KEY).status)
        assertEquals(HttpStatusCode.TooManyRequests, hook(apiKey = GRAFANA_KEY).status)
        assertEquals(HttpStatusCode.TooManyRequests, hook(apiKey = GRAFANA_KEY).status)

        transport.awaitMails(2)
        assertEquals(listOf("alerts@example.com", "ops@example.com"), transport.sent.map { it.getRecipients(Message.RecipientType.TO).single().toString() })
        assertEquals("KNot rate limit reached: grafana", transport.sent.last().subject)
    }

    @Test
    fun `without system mails the rate limit is silent`() = testApplication {
        val transport = RecordingTransport()
        knotApp(config = testConfig(rateLimitPerMinute = 1), transport = transport, systemMails = false)

        assertEquals(HttpStatusCode.OK, hook(apiKey = GRAFANA_KEY).status)
        assertEquals(HttpStatusCode.TooManyRequests, hook(apiKey = GRAFANA_KEY).status)

        Thread.sleep(200)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `failed delivery is a bad gateway`() = testApplication {
        val transport = RecordingTransport(failures = 3)
        knotApp(transport = transport)

        val response = hook(apiKey = OPS_KEY)

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals(3, transport.calls)
    }

    @Test
    fun `oversized payload is rejected`() = testApplication {
        val transport = RecordingTransport()
        knotApp(transport = transport)

        val response = hook(apiKey = OPS_KEY, body = """{"body": "${"x".repeat(300 * 1024)}"}""")

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `responses never echo the api key`() = testApplication {
        knotApp()

        val response = hook(apiKey = "wrong-key-0123456789")

        assertTrue(response.bodyAsText().contains("wrong-key").not())
    }

    private suspend fun ApplicationTestBuilder.hook(apiKey: String, body: String = """{"body": "x"}""") = client.post("/hook") {
        header("X-API-Key", apiKey)
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
