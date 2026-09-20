package bayern.kickner.knot.config

import jakarta.mail.internet.InternetAddress
import kotlinx.serialization.json.Json
import kotnexlib.ResultOf2
import java.io.File

/** Shorter keys are trivially brute-forced. The key is the only authentication KNot has. */
private const val MIN_API_KEY_LENGTH = 16

/**
 * Loads and validates the configuration file at [path].
 *
 * Unknown keys are errors, so a typo cannot silently fall back to a default. Every validation issue is
 * collected into one message, so a broken config needs only one restart to fix.
 *
 * @return the config, or a human-readable failure message that never contains the file content (it holds secrets).
 */
fun loadConfig(path: String): ResultOf2<AppConfig, String> {
    val file = File(path)
    if (file.exists().not()) return ResultOf2.Failure("Config file not found: ${file.absolutePath}")

    val text = runCatching { file.readText() }
        .getOrElse { return ResultOf2.Failure("Config file ${file.absolutePath} could not be read: ${it.message}") }
    val config = runCatching { Json.decodeFromString<AppConfig>(text) }
        .getOrElse { return ResultOf2.Failure("Config file ${file.absolutePath} could not be parsed: ${sanitize(it.message)}") }

    val issues = validate(config)
    if (issues.isNotEmpty()) return ResultOf2.Failure("Config file ${file.absolutePath} is invalid:\n" + issues.joinToString("\n") { "- $it" })

    return ResultOf2.Success(config)
}

/**
 * Keeps only the first line of a kotlinx.serialization message: the following lines are either a developer
 * hint ("Use 'ignoreUnknownKeys = true' ...") or the offending document ("JSON input: ..."), which holds secrets.
 */
private fun sanitize(message: String?): String =
    message?.substringBefore("JSON input")?.lineSequence()?.first()?.trim()?.ifEmpty { null } ?: "unknown error"

private fun validate(config: AppConfig): List<String> {
    val issues = mutableListOf<String>()

    if (config.listenHost.isBlank()) issues += "listenHost must not be blank"
    if (config.listenPort.isValidPort().not()) issues += "listenPort must be between 1 and 65535"
    if (config.rateLimitPerMinute < 1) issues += "rateLimitPerMinute must be at least 1"

    issues += validateTarget("default", config.defaultTarget)
    config.targets.forEachIndexed { index, target -> issues += validateTarget("targets[$index] ('${target.name}')", target) }

    val duplicateKeys = config.allTargets.groupBy { it.apiKey }.filterValues { it.size > 1 }.values
    duplicateKeys.forEach { targets -> issues += "apiKey of ${targets.joinToString(" and ") { "'${it.name}'" }} is not unique" }
    // Names only appear in log lines, but a duplicate would make those lines ambiguous
    val duplicateNames = config.allTargets.groupBy { it.name }.filterValues { it.size > 1 }.keys
    duplicateNames.forEach { name -> issues += "target name '$name' is not unique" }

    return issues
}

private fun validateTarget(owner: String, target: Target): List<String> {
    val issues = mutableListOf<String>()
    fun report(message: String) = issues.add("$owner: $message")

    if (target.name.isBlank()) report("name must not be blank")
    if (target.apiKey.isBlank()) report("apiKey must not be blank")
    else if (target.apiKey.length < MIN_API_KEY_LENGTH) report("apiKey must be at least $MIN_API_KEY_LENGTH characters long")
    if (target.to.isValidAddress().not()) report("'to' is not a valid mail address")

    val smtp = target.smtp
    if (smtp.host.isBlank()) report("smtp.host must not be blank")
    if (smtp.port.isValidPort().not()) report("smtp.port must be between 1 and 65535")
    if (smtp.username.isBlank()) report("smtp.username must not be blank")
    if (smtp.password.isBlank()) report("smtp.password must not be blank")
    if (smtp.from.isValidAddress().not()) report("smtp.from is not a valid mail address")

    return issues
}

private fun Int.isValidPort() = this in 1..65535

/** Jakarta Mail is the authority on address syntax. A strict parse also rejects address lists. */
private fun String.isValidAddress() = runCatching { InternetAddress(this, true).validate() }.isSuccess
