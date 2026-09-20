package bayern.kickner.knot

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knot.cli.generateApiKey
import bayern.kickner.knot.cli.sendTestMail
import bayern.kickner.knot.config.AppConfig
import bayern.kickner.knot.config.loadConfig
import bayern.kickner.knot.notify.SystemNotifier
import bayern.kickner.knot.notify.timestampFormat
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.ratelimit.RateLimiter
import bayern.kickner.knot.routes.healthRoute
import bayern.kickner.knot.routes.hookRoute
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf
import kotnexlib.ResultOf2
import java.time.Instant
import java.time.ZoneId
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val TAG = "Main"

/**
 * `EX_CONFIG` from sysexits.h, used when the config file is rejected or the configured address cannot be bound.
 * knot.service lists it in `RestartPreventExitStatus`: a restart cannot fix either, so systemd must not loop.
 */
private const val EXIT_CONFIG_ERROR = 78

/** Alert texts are short. Anything bigger is a mistake or an attempt to exhaust memory. */
private const val MAX_BODY_BYTES = 256L * 1024

/** Version and build time for the log banner and the mails, e.g. `1.0.0 (built 2026-09-20 12:06:19 CEST)`. */
internal val appVersion: String =
    "${BuildConfig.VERSION} (built ${Instant.ofEpochMilli(BuildConfig.BUILD_TIME).atZone(ZoneId.systemDefault()).format(timestampFormat)})"

val globalScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("global"))

/**
 * Entry point. Loads the config (`config=<path>`, default `config.json` in the working directory), then serves
 * `/hook` and `/health` until the process is stopped. A config problem or a taken port ends the process with
 * [EXIT_CONFIG_ERROR].
 *
 * Two arguments run a one-off command instead of the server: `key` prints a fresh API key (no config needed),
 * `test=<target name>` sends a test mail through that target and exits with 0 on success, 1 otherwise.
 */
fun main(args: Array<String>) {
    // Ktor logs through SLF4J, only its warnings and errors are worth the journal
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    KLogger.configure {
        logToConsole()
        minLevel = KLogger.Level.DEBUG
    }

    // ArgsInterpreter only knows `key=value` pairs and `-flags`, a bare word is checked directly
    if (args.contains("key")) {
        // Key on stdout, hint on stderr, so `KEY=$(java -jar knot.jar key)` captures only the key
        System.err.println("Fresh API key for a target's apiKey. Keep it secret:")
        println(generateApiKey())
        return
    }

    val arguments = ArgsInterpreter(args)
    val config = when (val result = loadConfig(arguments.getValue("config") ?: "config.json")) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { result.value }
            exitProcess(EXIT_CONFIG_ERROR)
        }
    }

    val mailSender = MailSender()
    arguments.getValue("test")?.let { targetName ->
        val result = runBlocking { sendTestMail(config, targetName, mailSender, appVersion) }
        exitProcess(if (result is ResultOf.Success) 0 else 1)
    }

    val notifier = if (config.sendSystemMails) SystemNotifier(config.defaultTarget, mailSender, appVersion) else null
    val server = knotServer(config, mailSender, notifier)

    runCatching { server.start(wait = true) }.onFailure { error ->
        staticLog(KLogger.Level.ERROR, TAG) { "Could not start on ${config.listenHost}:${config.listenPort}: ${error.rootCause().message}" }
        exitProcess(EXIT_CONFIG_ERROR)
    }
}

/**
 * Builds the HTTP server: logs the banner, sends the startup mail in the background and registers the JVM
 * shutdown hook that sends the stop mail. Ktor's own shutdown hook stops the engine.
 */
fun knotServer(
    config: AppConfig,
    mailSender: MailSender,
    notifier: SystemNotifier?
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    staticLog(KLogger.Level.INFO, TAG) {
        "KNot $appVersion listening on ${config.listenHost}:${config.listenPort} with ${config.allTargets.size} target(s): ${config.allTargets.joinToString { it.name }}"
    }

    globalScope.launch {
        notifier?.notifyStarted()
    }

    val server = embeddedServer(CIO, host = config.listenHost, port = config.listenPort) {
        knot(config, mailSender, RateLimiter(config.rateLimitPerMinute), notifier)
    }

    Runtime.getRuntime().addShutdownHook(thread(false) {
        staticLog(KLogger.Level.INFO, TAG) { "Shutting down" }
        notifier?.notifyStopped()
    })

    return server
}

/**
 * Ktor module wiring the two endpoints. Kept apart from the server setup so route tests can host it with fakes.
 */
fun Application.knot(config: AppConfig, mailSender: MailSender, rateLimiter: RateLimiter, systemNotifier: SystemNotifier?) {
    install(RequestBodyLimit) {
        bodyLimit { MAX_BODY_BYTES }
    }
    routing {
        healthRoute()
        hookRoute(config, mailSender, rateLimiter, systemNotifier)
    }
}

private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()
