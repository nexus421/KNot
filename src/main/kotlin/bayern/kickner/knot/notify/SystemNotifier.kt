package bayern.kickner.knot.notify

import bayern.kickner.klogger.infoLog
import bayern.kickner.klogger.warnLog
import bayern.kickner.knot.config.Target
import bayern.kickner.knot.globalScope
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.mail.compose
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotnexlib.ResultOf
import java.net.InetAddress
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

/**
 * Sends the optional system mails — KNot started/stopped, a target reached its rate limit — always through the
 * default target. Every mail is best effort: a failure is logged and nothing else happens.
 *
 * @param version Application version shown in the startup mail.
 * @param hostname Name of the machine KNot runs on.
 * @param now Current time; injectable for tests.
 */
class SystemNotifier(
    private val target: Target,
    private val mailSender: MailSender,
    private val version: String,
    private val hostname: String = localHostname(),
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() }
) {

    suspend fun notifyStarted() {
        send("KNot started", "KNot has started.\n\n${footer()}\nVersion: $version")
    }

    /**
     * Reports that [reachedBy] exceeded its limit. The caller decides when this is worth a mail (once per
     * episode, see [bayern.kickner.knot.ratelimit.RateLimiter]); the key itself is never part of the mail.
     */
    suspend fun notifyRateLimitReached(reachedBy: Target, limitPerMinute: Int) {
        val body = "Target '${reachedBy.name}' has reached its rate limit of $limitPerMinute requests per minute. " +
            "Further requests are rejected with 429 as long as the limit stays exceeded.\n" +
            "This mail is sent again only when the limit is reached anew after a calm minute. " +
            "If this traffic is unexpected, the target's API key may have leaked.\n\n${footer()}"
        send("KNot rate limit reached: ${reachedBy.name}", body)
    }

    /**
     * Sends the stop mail and blocks until it is delivered or [timeout] has passed. Meant for the JVM shutdown
     * hook, where the process ends as soon as this returns — a slow SMTP server must not delay that for long.
     */
    fun notifyStopped(timeout: Duration = 5.seconds) {
        val body = "KNot has stopped.\n\n${footer()}"
        val job = globalScope.launch {
            withTimeout(timeout) {
                send("KNot stopped", body)
            }
        }

        runBlocking { job.join() }
    }

    private suspend fun send(subject: String, body: String) {
        when (mailSender.send(target, target.compose(subject, body))) {
            is ResultOf.Success -> infoLog { "System mail '$subject' sent via target '${target.name}'" }
            is ResultOf.Failure -> warnLog { "System mail '$subject' could not be sent, continuing without it" }
        }
    }

    private fun footer(): String = "Host: $hostname\nTime: ${now().format(timestampFormat)}"
}

private fun localHostname(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
