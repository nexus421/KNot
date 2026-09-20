package bayern.kickner.knot

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knot.config.AppConfig
import bayern.kickner.knot.config.loadConfig
import bayern.kickner.knot.notify.SystemNotifier
import bayern.kickner.knot.mail.MailSender
import bayern.kickner.knot.ratelimit.RateLimiter
import bayern.kickner.knot.routes.healthRoute
import bayern.kickner.knot.routes.hookRoute
import io.ktor.server.application.Application
import io.ktor.server.application.ServerReady
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf2
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val TAG = "Main"

/**
 * `EX_CONFIG` from sysexits.h, used when the config file is rejected or the configured address cannot be bound.
 * knot.service lists it in `RestartPreventExitStatus`: a restart cannot fix either, so systemd must not loop.
 */
private const val EXIT_CONFIG_ERROR = 78

/** Alert texts are short; anything bigger is a mistake or an attempt to exhaust memory. */
private const val MAX_BODY_BYTES = 256L * 1024

/** From the JAR manifest (`Implementation-Version`); "dev" when running from compiled classes. */
private val appVersion: String = AppConfig::class.java.`package`?.implementationVersion ?: "dev"

val globalScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("global"))

/**
 * Entry point: loads the config (`config=<path>`, default `config.json` in the working directory), then serves
 * `/hook` and `/health` until the process is stopped. A config problem or a taken port ends the process with
 * [EXIT_CONFIG_ERROR].
 */
fun main(args: Array<String>) {
    // Ktor logs through SLF4J; only its warnings and errors are worth the journal
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    KLogger.configure {
        logToConsole()
        minLevel = KLogger.Level.DEBUG
    }

    val configPath = ArgsInterpreter(args).getValue("config") ?: "config.json"
    val config = when (val result = loadConfig(configPath)) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { result.value }
            exitProcess(EXIT_CONFIG_ERROR)
        }
    }

    val mailSender = MailSender()
    val notifier = if (config.sendSystemMails) SystemNotifier(config.defaultTarget, mailSender, appVersion) else null
    val server = knotServer(config, mailSender, notifier)

    runCatching { server.start(wait = true) }.onFailure { error ->
        staticLog(KLogger.Level.ERROR, TAG) { "Could not start on ${config.listenHost}:${config.listenPort}: ${error.rootCause().message}" }
        exitProcess(EXIT_CONFIG_ERROR)
    }
}

/**
 * Builds the HTTP server. Banner, startup mail and the shutdown hook (stop the server, then send the stop mail)
 * are wired to [ServerReady], so a start that fails — typically a taken port — never produces a misleading stop
 * mail. The hook registration is injectable so tests can run the hook themselves.
 */
fun knotServer(
    config: AppConfig,
    mailSender: MailSender,
    notifier: SystemNotifier?,
    registerShutdownHook: (() -> Unit) -> Unit = { hook -> Runtime.getRuntime().addShutdownHook(Thread(hook)) }
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
