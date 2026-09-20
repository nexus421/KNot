package bayern.kickner.knot.mail

import bayern.kickner.knot.config.Target

/** Subject used when the caller sends none. Deliberately not configurable. */
const val DEFAULT_SUBJECT = "KNot Message"

private val lineBreaks = Regex("[\r\n]+")

/**
 * Final subject and plain-text body of a mail, ready to be sent.
 */
data class MailContent(val subject: String, val body: String)

/**
 * Frames [subject] and [body] with this target's prefixes and postfix. Nothing is inserted between the parts;
 * a wanted space or line break has to be part of the configured prefix/postfix.
 *
 * A blank [subject] falls back to [DEFAULT_SUBJECT]. Line breaks in the subject are collapsed to a space,
 * so a caller can never smuggle additional mail headers in through it.
 */
fun Target.compose(subject: String?, body: String): MailContent {
    val plainSubject = subject?.takeUnless { it.isBlank() }?.replace(lineBreaks, " ") ?: DEFAULT_SUBJECT
    return MailContent(subject = subjectPrefix + plainSubject, body = bodyPrefix + body + bodyPostfix)
}
