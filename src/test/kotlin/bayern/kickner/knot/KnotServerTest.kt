package bayern.kickner.knot

import bayern.kickner.knot.config.AppConfig
import bayern.kickner.knot.notify.SystemNotifier
import bayern.kickner.knot.mail.MailSender
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class KnotServerTest {

    private val transport = RecordingTransport()
    private val mailSender = MailSender(transport, noRetryDelays)
    private val hooks = mutableListOf<() -> Unit>()

    private fun server(config: AppConfig) =
        knotServer(config, mailSender, SystemNotifier(config.defaultTarget, mailSender, "test"), registerShutdownHook = hooks::add)

    @Test
    fun `a failed start registers no shutdown hook and sends no mail`() {
        ServerSocket(0).use { taken ->
            val server = server(testConfig(listenPort = taken.localPort))

            assertFails { server.start(wait = false) }
            server.stop()
        }

        assertTrue(hooks.isEmpty(), "shutdown hook must only exist for a running server")
        assertEquals(0, transport.calls)
    }

    @Test
    fun `a running server sends the startup mail and the shutdown hook stops it with the stop mail`() {
        val server = server(testConfig(listenPort = 0))
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }

        assertEquals("ok", get(port).body())
        transport.awaitMails(1)
        assertEquals("KNot started", transport.sent.single().subject)

        hooks.single().invoke()

        assertEquals(listOf("KNot started", "KNot stopped"), transport.sent.map { it.subject })
        assertFails { get(port) }
    }

    private fun get(port: Int): HttpResponse<String> =
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).build(), HttpResponse.BodyHandlers.ofString())
}
