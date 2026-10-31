package bayern.kickner.knot.routes

import bayern.kickner.klogger.KLogger
import bayern.kickner.knot.RecordingTransport
import bayern.kickner.knot.config.AppConfig
import bayern.kickner.knot.knot
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.noRetryDelays
import bayern.kickner.knot.notify.SystemNotifier
import bayern.kickner.knot.ratelimit.RateLimiter
import bayern.kickner.knot.testConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import jakarta.mail.Message
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val GRAFANA_KEY = "grafana-key-0123456789"
private const val OPS_KEY = "ops-key-0123456789"

/** Collects the messages logged by the hook route. Klogger is global, so the destination is registered once per JVM. */
private object HookRouteLog {
    val lines = CopyOnWriteArrayList<String>()

    init {
        KLogger.configure { logToCustom { _, tag, message -> if (tag == "HookRoute") lines += message } }
    }
}

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
        // The first rejection reaches the limit, the second one is a plain rejection. Both carry Retry-After.
        repeat(2) {
            val rejected = hook(apiKey = GRAFANA_KEY)
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertEquals("60", rejected.headers[HttpHeaders.RetryAfter])
        }
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
    fun `rejected requests are logged at most ten times per minute regardless of the rate limit`() = testApplication {
        knotApp(config = testConfig(rateLimitPerMinute = 1))
        HookRouteLog.lines.clear()

        repeat(25) { assertEquals(HttpStatusCode.Unauthorized, hook(apiKey = "wrong-key-0123456789").status) }

        // Ten regular lines, then one line that announces the silence, then nothing
        val rejections = HookRouteLog.lines.filter { it.startsWith("Rejected hook request") }
        assertEquals(11, rejections.size)
        assertTrue(rejections.take(10).none { it.contains("not logged") })
        assertTrue(rejections.last().contains("further rejections are not logged"))
    }

    @Test
    fun `a flood of wrong keys neither blocks nor silences valid requests`() = testApplication {
        val transport = RecordingTransport()
        knotApp(transport = transport)
        HookRouteLog.lines.clear()

        repeat(50) { hook(apiKey = "wrong-key-0123456789") }

        assertEquals(HttpStatusCode.OK, hook(apiKey = OPS_KEY).status)
        assertEquals(1, transport.calls)
        assertTrue(HookRouteLog.lines.contains("Mail sent for target 'ops'"))
    }

    @Test
    fun `responses never echo the api key`() = testApplication {
        knotApp()

        val response = hook(apiKey = "wrong-key-0123456789")

        assertTrue(response.bodyAsText().contains("wrong-key").not())
    }

    @Test
    fun `the last forwarded entry is logged as client address`() = testApplication {
        knotApp()

        assertEquals("198.51.100.7", loggedClientAddress(forwardedFor = "203.0.113.66, 198.51.100.7"))
        assertEquals("2001:db8::1", loggedClientAddress(forwardedFor = "2001:db8::1"))
    }

    @Test
    fun `a forwarded value that is no address falls back to the peer`() = testApplication {
        knotApp()
        val peer = loggedClientAddress(forwardedFor = null)

        // The Ktor client refuses control characters like ESC in headers, so text and a tab stand in for them
        for (forwarded in listOf("1.2.3.4 FAKE", "1.2.3.4\tFAKE", "A".repeat(8000), "", "1.2.3.4, evil")) {
            assertEquals(peer, loggedClientAddress(forwardedFor = forwarded), forwarded.take(40))
        }
    }

    /** Sends a request with a wrong key and returns the client address its log line names. */
    private suspend fun ApplicationTestBuilder.loggedClientAddress(forwardedFor: String?): String {
        HookRouteLog.lines.clear()
        assertEquals(HttpStatusCode.Unauthorized, hook(apiKey = "wrong-key-0123456789", forwardedFor = forwardedFor).status)
        return HookRouteLog.lines.single().removePrefix("Rejected hook request from ").substringBefore(": missing")
    }

    private suspend fun ApplicationTestBuilder.hook(
        apiKey: String,
        body: String = """{"body": "x"}""",
        forwardedFor: String? = null
    ) = client.post("/hook") {
        header("X-API-Key", apiKey)
        forwardedFor?.let { header("X-Forwarded-For", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
