package bayern.kickner.knot.routes

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knot.config.AppConfig
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.mail.compose
import bayern.kickner.knot.notify.SystemNotifier
import bayern.kickner.knot.ratelimit.RateLimiter
import bayern.kickner.knot.ratelimit.RateLimiter.Verdict
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotnexlib.ResultOf

private const val TAG = "HookRoute"
private const val API_KEY_HEADER = "X-API-Key"
private const val API_KEY_QUERY_PARAMETER = "apiKey"

/**
 * JSON payload of a hook request. Everything else in the document is ignored.
 *
 * @property subject Optional mail subject; blank falls back to the fixed default.
 * @property body Required plain-text mail body.
 */
@Serializable
data class HookPayload(val subject: String? = null, val body: String? = null)

private val payloadJson = Json { ignoreUnknownKeys = true }

/**
 * `POST /hook` — authenticates the caller by API key, applies the per-target rate limit, then sends the
 * framed payload as a mail. Failures map to 401 (no or unknown key), 429 (rate limit), 400 (payload)
 * and 502 (delivery failed after all retries).
 *
 * When a target starts exceeding its limit, [systemNotifier] (if configured) is told once per episode; that
 * mail is sent in the background so the 429 does not wait for SMTP.
 */
fun Route.hookRoute(config: AppConfig, mailSender: MailSender, rateLimiter: RateLimiter, systemNotifier: SystemNotifier?) {
    post("/hook") {
        val target = call.apiKey(config.allowApiKeyInQuery)?.let { config.targetForApiKey(it) }
        if (target == null) {
            staticLog(KLogger.Level.WARN, TAG) { "Rejected hook request from ${call.clientAddress()}: missing or unknown API key" }
            return@post call.respond(HttpStatusCode.Unauthorized, "Missing or invalid API key")
        }

        when (rateLimiter.tryAcquire(target.apiKey)) {
            Verdict.ALLOWED -> Unit
            Verdict.LIMIT_REACHED -> {
                // Logged and mailed once per episode; the following rejections stay quiet so an attack cannot flood the journal
                staticLog(KLogger.Level.WARN, TAG) { "Rate limit of ${config.rateLimitPerMinute}/min reached for target '${target.name}', rejecting further requests" }
                if (systemNotifier != null) call.application.launch { systemNotifier.notifyRateLimitReached(target, config.rateLimitPerMinute) }
                return@post call.respond(HttpStatusCode.TooManyRequests, "Rate limit exceeded")
            }
            Verdict.REJECTED -> return@post call.respond(HttpStatusCode.TooManyRequests, "Rate limit exceeded")
        }

        // receiveText stays outside runCatching: an oversized body throws PayloadTooLargeException, which Ktor answers with 413
        val text = call.receiveText()
        val payload = runCatching { payloadJson.decodeFromString<HookPayload>(text) }
            .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON payload") }
        val body = payload.body
        if (body.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, "Field 'body' must not be empty")

        when (mailSender.send(target, target.compose(payload.subject, body))) {
            is ResultOf.Success -> {
                staticLog(KLogger.Level.INFO, TAG) { "Mail sent for target '${target.name}'" }
                call.respond(HttpStatusCode.OK, "ok")
            }
            is ResultOf.Failure -> call.respond(HttpStatusCode.BadGateway, "Mail delivery failed")
        }
    }
}

/** Behind the reverse proxy the connection peer is always the proxy; its forwarded header names the real client. */
private fun RoutingCall.clientAddress(): String = request.header("X-Forwarded-For") ?: request.origin.remoteHost

/** The presented API key: the header wins, the query parameter only counts when enabled. Blank values count as absent. */
private fun RoutingCall.apiKey(allowQueryParameter: Boolean): String? {
    val fromHeader = request.header(API_KEY_HEADER)?.takeUnless { it.isBlank() }
    val fromQuery = request.queryParameters[API_KEY_QUERY_PARAMETER]?.takeUnless { it.isBlank() }
    return fromHeader ?: fromQuery.takeIf { allowQueryParameter }
}
