package bayern.kickner.knot.config

import bayern.kickner.knot.mail.smtpProperties
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Root configuration, loaded once at startup from the JSON config file (see [loadConfig]).
 *
 * @property listenHost Interface the HTTP server binds to. The default keeps KNot reachable only from the
 * local machine, where the TLS-terminating reverse proxy is expected to run.
 * @property listenPort Port the HTTP server listens on.
 * @property sendSystemMails Sends system mails through [defaultTarget]: when KNot starts and stops, and once per
 * episode when a target reaches its rate limit.
 * @property rateLimitPerMinute Maximum number of hook requests per target and minute.
 * @property allowApiKeyInQuery Additionally accepts the API key as `?apiKey=` query parameter.
 * @property defaultTarget Mandatory target. Receives the system mails and is usable as a regular webhook target.
 * @property targets Additional webhook targets.
 */
@Serializable
data class AppConfig(
    val listenHost: String = "127.0.0.1",
    val listenPort: Int = 8080,
    val sendSystemMails: Boolean = true,
    val rateLimitPerMinute: Int = 10,
    val allowApiKeyInQuery: Boolean = false,
    @SerialName("default") val defaultTarget: Target,
    val targets: List<Target> = emptyList()
) {
    /** All targets a request may address: [targets] followed by [defaultTarget]. */
    val allTargets: List<Target> get() = targets + defaultTarget

    /**
     * The target authenticated by [apiKey], or null. Keys are compared in constant time so response timing
     * reveals nothing about how much of a guessed key was right.
     */
    fun targetForApiKey(apiKey: String): Target? {
        val presented = apiKey.toByteArray()
        return allTargets.firstOrNull { MessageDigest.isEqual(it.apiKey.toByteArray(), presented) }
    }
}

/**
 * A webhook target: the API key that selects it, the recipient and the SMTP account to send from.
 *
 * @property name Only used in log messages.
 * @property apiKey Authenticates the caller and selects this target. Must be unique across all targets.
 * @property to Recipient address.
 * @property subjectPrefix Prepended to the subject as-is (include a trailing space yourself if wanted).
 * @property bodyPrefix Prepended to the body as-is.
 * @property bodyPostfix Appended to the body as-is.
 * @property smtp SMTP account used to send mails for this target.
 */
@Serializable
data class Target(
    val name: String,
    val apiKey: String,
    val to: String,
    val subjectPrefix: String = "",
    val bodyPrefix: String = "",
    val bodyPostfix: String = "",
    val smtp: SmtpConfig
) {
    override fun toString() = "Target(name='$name', to='$to', smtp=$smtp)"
}

/**
 * SMTP account. Every field is required. The server certificate is verified whenever TLS is used.
 */
@Serializable
data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val from: String,
    val tls: TlsMode = TlsMode.STARTTLS
) {
    override fun toString() = "SmtpConfig(host='$host', port=$port, username='$username', from='$from', tls=$tls)"

    /** Jakarta Mail session properties for this account, built once on first use and shared by every mail sent with it. */
    val properties by lazy { smtpProperties(this) }
}

/** Transport encryption of an SMTP connection. */
@Serializable
enum class TlsMode {
    /** STARTTLS is required. The connection fails if the server does not offer it (typically port 587). */
    @SerialName("starttls") STARTTLS,

    /** Implicit TLS from the first byte (typically port 465). */
    @SerialName("ssl") SSL,

    /** No encryption, credentials travel in plaintext. Only for trusted internal relays. */
    @SerialName("none") NONE
}
