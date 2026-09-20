package bayern.kickner.knot

import bayern.kickner.knot.config.AppConfig
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.notify.SystemNotifier
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class KnotServerTest {

    private val transport = RecordingTransport()
    private val mailSender = MailSender(transport, noRetryDelays)

    private fun server(config: AppConfig) = knotServer(config, mailSender, SystemNotifier(config.defaultTarget, mailSender, "test"))

    @Test
    fun `a taken port makes the start fail`() {
        ServerSocket(0).use { taken ->
            val server = server(testConfig(listenPort = taken.localPort))

            assertFails { server.start(wait = false) }
            server.stop()
        }
    }

    @Test
    fun `a running server answers health checks and sends the startup mail`() {
        val server = server(testConfig(listenPort = 0))
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }

        assertEquals("ok", get(port).body())
        transport.awaitMails(1)
        assertEquals("KNot started", transport.sent.single().subject)

        server.stop()
    }

    private fun get(port: Int): HttpResponse<String> =
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).build(), HttpResponse.BodyHandlers.ofString())
}
