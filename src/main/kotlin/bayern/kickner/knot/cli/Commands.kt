package bayern.kickner.knot.cli

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knot.config.AppConfig
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.mail.compose
import bayern.kickner.knot.notify.localHostname
import bayern.kickner.knot.notify.timestampFormat
import kotnexlib.ResultOf
import java.security.SecureRandom
import java.time.ZonedDateTime
import java.util.Base64

private const val TAG = "Commands"

/** 256 bits of entropy. The key is the only authentication KNot has, so there is no reason to be stingy. */
private const val API_KEY_BYTES = 32

/**
 * A fresh API key: [API_KEY_BYTES] random bytes as unpadded URL-safe Base64 (43 characters). URL-safe so the
 * key survives a `?apiKey=` query parameter unencoded. Standard Base64's `+` would turn into a space.
 *
 * @param random Source of randomness, injectable for tests.
 */
fun generateApiKey(random: SecureRandom = SecureRandom()): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(API_KEY_BYTES).also(random::nextBytes))

/**
 * Sends one test mail through the target named [targetName], framed with the target's prefixes so they get
 * checked along with the SMTP account. The failure message names the unknown target and the configured ones,
 * or repeats [MailSender]'s delivery summary.
 *
 * @param version Application version shown in the mail.
 * @param hostname Name of the machine KNot runs on.
 * @param now Current time, injectable for tests.
 */
suspend fun sendTestMail(
    config: AppConfig,
    targetName: String,
    mailSender: MailSender,
    version: String,
    hostname: String = localHostname(),
    now: () -> ZonedDateTime = { ZonedDateTime.now() }
): ResultOf<Unit> {
    val target = config.allTargets.firstOrNull { it.name == targetName }
    if (target == null) {
        val message = "Unknown target '$targetName'. Configured targets: ${config.allTargets.joinToString { it.name }}"
        staticLog(KLogger.Level.ERROR, TAG) { message }
        return ResultOf.Failure(message)
    }

    val body = "Test mail for target '${target.name}' from KNot $version.\nHost: $hostname\nTime: ${now().format(timestampFormat)}"
    // MailSender logs a failed delivery itself
    return mailSender.send(target, target.compose("KNot test mail", body))
        .also { if (it is ResultOf.Success) staticLog(KLogger.Level.INFO, TAG) { "Test mail sent for target '${target.name}' to ${target.to}" } }
}
