package bayern.kickner.knot.config

import kotnexlib.ResultOf2
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ConfigLoaderTest {

    @Test
    fun `loads a complete config`() {
        val result = load(config(targets = listOf(target(name = "grafana", apiKey = "grafana-key-0123456789", tls = "ssl"))))

        val config = assertIs<ResultOf2.Success<AppConfig>>(result).value
        assertEquals("127.0.0.1", config.listenHost)
        assertEquals(8080, config.listenPort)
        assertEquals(10, config.rateLimitPerMinute)
        assertFalse(config.allowApiKeyInQuery)
        assertFalse(config.sendSystemMails)
        assertEquals("ops", config.defaultTarget.name)
        assertEquals(TlsMode.STARTTLS, config.defaultTarget.smtp.tls)
        assertEquals("grafana", config.targets.single().name)
        assertEquals(TlsMode.SSL, config.targets.single().smtp.tls)
        assertEquals(listOf("grafana", "ops"), config.allTargets.map { it.name })
    }

    @Test
    fun `prefixes default to empty strings`() {
        val config = assertIs<ResultOf2.Success<AppConfig>>(load(config())).value

        assertEquals("", config.defaultTarget.subjectPrefix)
        assertEquals("", config.defaultTarget.bodyPrefix)
        assertEquals("", config.defaultTarget.bodyPostfix)
    }

    @Test
    fun `missing file is reported`() {
        val result = loadConfig("/nonexistent/knot-config.json")

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "/nonexistent/knot-config.json")
    }

    @Test
    fun `unreadable file is reported as such`() {
        val directory = File.createTempFile("knot-config", "").apply { delete(); mkdir(); deleteOnExit() }

        val result = loadConfig(directory.absolutePath)

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "could not be read")
    }

    @Test
    fun `duplicate target names are rejected`() {
        val result = load(config(targets = listOf(target(name = "ops", apiKey = "second-key-0123456789"))))

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "name 'ops' is not unique")
    }

    @Test
    fun `invalid json is reported without echoing the file content`() {
        val result = load("""{"listenPort": 8080, "default": {"apiKey": "super-secret-key-value" """)

        val message = assertIs<ResultOf2.Failure<String>>(result).value
        assertFalse(message.contains("super-secret-key-value"), message)
    }

    @Test
    fun `unknown keys are rejected`() {
        val result = load("""{"rateLimitPerMinut": 5, "default": ${target()}}""")

        val message = assertIs<ResultOf2.Failure<String>>(result).value
        assertContains(message, "rateLimitPerMinut")
        assertFalse(message.contains("ignoreUnknownKeys"), "developer hint must not leak into the admin-facing message: $message")
    }

    @Test
    fun `missing default target is rejected`() {
        val result = load("""{"targets": [${target()}]}""")

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "default")
    }

    @Test
    fun `duplicate api keys are rejected`() {
        val result = load(config(targets = listOf(target(name = "grafana", apiKey = "ops-key-0123456789"))))

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "apiKey")
    }

    @Test
    fun `short api key is rejected`() {
        val result = load(config(default = target(apiKey = "too-short")))

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "16 characters")
    }

    @Test
    fun `blank smtp fields are rejected`() {
        val result = load(config(default = target(host = " ", password = "")))

        val message = assertIs<ResultOf2.Failure<String>>(result).value
        assertContains(message, "smtp.host")
        assertContains(message, "smtp.password")
    }

    @Test
    fun `invalid mail addresses are rejected`() {
        val result = load(config(default = target(to = "ops.example.com", from = "knot <at> example.com")))

        val message = assertIs<ResultOf2.Failure<String>>(result).value
        assertContains(message, "'to'")
        assertContains(message, "smtp.from")
    }

    @Test
    fun `ports out of range are rejected`() {
        val result = load(config(listenPort = 70000, default = target(port = 0)))

        val message = assertIs<ResultOf2.Failure<String>>(result).value
        assertContains(message, "listenPort")
        assertContains(message, "smtp.port")
    }

    @Test
    fun `rate limit must be positive`() {
        val result = load(config(rateLimitPerMinute = 0))

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "rateLimitPerMinute")
    }

    @Test
    fun `issues name the target they belong to`() {
        val result = load(config(targets = listOf(target(name = "grafana", apiKey = "grafana-key-0123456789", to = "nope"))))

        assertContains(assertIs<ResultOf2.Failure<String>>(result).value, "targets[0] ('grafana')")
    }

    @Test
    fun `secrets are masked in toString`() {
        val config = assertIs<ResultOf2.Success<AppConfig>>(load(config())).value

        val printed = config.toString()
        assertFalse(printed.contains("secret-password"), printed)
        assertFalse(printed.contains("ops-key-0123456789"), printed)
    }

    private fun load(json: String): ResultOf2<AppConfig, String> {
        val file = File.createTempFile("knot-config", ".json").apply { deleteOnExit() }
        file.writeText(json)
        return loadConfig(file.absolutePath)
    }
}

internal fun target(
    name: String = "ops",
    apiKey: String = "ops-key-0123456789",
    to: String = "ops@example.com",
    host: String = "smtp.example.com",
    port: Int = 587,
    username: String = "knot@example.com",
    password: String = "secret-password",
    from: String = "knot@example.com",
    tls: String = "starttls"
) = """
    {
      "name": "$name",
      "apiKey": "$apiKey",
      "to": "$to",
      "smtp": {
        "host": "$host",
        "port": $port,
        "username": "$username",
        "password": "$password",
        "from": "$from",
        "tls": "$tls"
      }
    }
""".trimIndent()

internal fun config(
    default: String = target(),
    targets: List<String> = emptyList(),
    listenPort: Int = 8080,
    rateLimitPerMinute: Int = 10
) = """
    {
      "listenPort": $listenPort,
      "rateLimitPerMinute": $rateLimitPerMinute,
      "default": $default,
      "targets": [${targets.joinToString(",\n")}]
    }
""".trimIndent()
