# KNot

[![Tests](https://github.com/nexus421/KNot/actions/workflows/test.yml/badge.svg)](https://github.com/nexus421/KNot/actions/workflows/test.yml)
![Kotlin](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKNot%2Fmaster%2Fbuild.gradle.kts&search=kotlin%5C%28%22jvm%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Kotlin&logo=kotlin&logoColor=white&color=7F52FF)
![Ktor](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKNot%2Fmaster%2Fbuild.gradle.kts&search=id%5C%28%22io%5C.ktor%5C.plugin%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Ktor&logo=ktor&logoColor=white&color=087CFA)
![JDK](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FKNot%2Fmaster%2Fbuild.gradle.kts&search=JavaLanguageVersion%5C.of%5C%28%28%5Cd%2B%29%5C%29&replace=%241%20%28Corretto%29&label=JDK&logo=openjdk&logoColor=white&color=ED8B00)
[![Release](https://img.shields.io/github/v/release/nexus421/KNot)](https://github.com/nexus421/KNot/releases)
[![License](https://img.shields.io/github/license/nexus421/KNot)](LICENSE)

**K**otlin **Not**ification: a small webhook-to-mail bridge. KNot accepts an HTTP webhook and sends its content
as a plain-text e-mail over SMTP. It exists for services that can call a webhook but cannot send mail
themselves: Grafana alerts, CI/CD pipelines, uptime checks, small internal tools.

KISS by design: no UI, no database, no runtime configuration. One JSON config file, one fat JAR, one systemd
service. TLS termination is left to a reverse proxy (e.g. Caddy, Zoraxy). KNot itself listens on plain HTTP,
by default only on `127.0.0.1`.

## Quick start

Requirements: JDK 25 to build (Amazon Corretto is the pinned toolchain, Gradle downloads it if missing) and a
Java 25 runtime on the machine that runs `knot.jar`.

```bash
cp config.example.json config.json   # then fill in API keys, recipients and SMTP accounts
./gradlew run                        # development: reads ./config.json
./gradlew buildFatJar                # production: build/libs/knot.jar
java -jar build/libs/knot.jar config=/path/to/config.json
```

With the server running, send a hook:

```bash
curl -X POST http://127.0.0.1:8080/hook \
  -H "X-API-Key: <apiKey of a target>" \
  -H "Content-Type: application/json" \
  -d '{"subject": "HighCPU firing", "body": "CPU > 90% for 5 minutes"}'
```

Tests: `./gradlew test`. GitHub Actions runs them on every push (`.github/workflows/test.yml`).

## Command line

| Argument        | Default       | Description                                                                                                                                                                                                |
|-----------------|---------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `config=<path>` | `config.json` | Config file to load (see Configuration).                                                                                                                                                                   |
| `check`         |               | Only loads and validates the config, then exits. No mail, no server. Useful before `systemctl restart knot`.                                                                                               |
| `key`           |               | Prints a fresh API key and exits. Needs no config. Only the key goes to stdout (the hint to stderr), so `KEY=$(java -jar knot.jar key)` captures just the key.                                             |
| `test=<name>`   |               | Sends one test mail through the target `<name>` and exits without starting the server. The config is validated as on a normal start, and the mail carries the target's prefixes, so those are checked too. |

Without `check`, `key` or `test=`, KNot starts the server. Exit codes:

| Code  | Meaning                                                                                                                                                                                                               |
|-------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `0`   | `check`: the config is valid. `key`: key printed. `test=`: the test mail was delivered.                                                                                                                               |
| `1`   | `test=`: the target is unknown, or the delivery failed after all retries. The log says why. Server: it could not start, e.g. the port is taken or the address is not assigned (yet). systemd retries, see Deployment. |
| `78`  | The config file is missing or invalid. systemd does not retry, see Deployment.                                                                                                                                        |
| `143` | The JVM's exit code after SIGTERM (`systemctl stop`). A clean stop, not a failure.                                                                                                                                    |

```bash
java -jar build/libs/knot.jar check config=/path/to/config.json
java -jar build/libs/knot.jar key
java -jar build/libs/knot.jar test=ops config=/path/to/config.json
```

## Configuration

One JSON file, `config.json` in the working directory by default (`config=<path>` overrides). It is read once
at startup, so restart after a change. A syntax error is reported with its position, validation problems
(blank fields, bad addresses, duplicate keys, ...) all at once, and unknown keys are errors too, so a typo
cannot silently fall back to a default. In every case KNot exits with code 78 (`EX_CONFIG`) instead of running
with a half-valid config.

| Field                | Type           | Default       | Description                                                                                                                                                          |
|----------------------|----------------|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `listenHost`         | String         | `"127.0.0.1"` | Interface to bind to. Use `"0.0.0.0"` only if the reverse proxy runs elsewhere.                                                                                      |
| `listenPort`         | Int            | `8080`        | Port to listen on.                                                                                                                                                   |
| `sendSystemMails`    | Boolean        | `true`        | Send system mails through `default`: KNot started/stopped, rate limit reached.                                                                                       |
| `rateLimitPerMinute` | Int            | `10`          | Maximum hook requests per target and minute.                                                                                                                         |
| `allowApiKeyInQuery` | Boolean        | `false`       | Additionally accept the API key as `?apiKey=` query parameter. Only for senders that cannot set headers: the key then may show up in the reverse proxy's access log. |
| `default`            | Target         | required      | Receives the system mails, usable as a regular webhook target as well.                                                                                               |
| `targets`            | List\<Target\> | `[]`          | Further webhook targets.                                                                                                                                             |

A `Target`:

| Field           | Type   | Default      | Description                                                                                                  |
|-----------------|--------|--------------|--------------------------------------------------------------------------------------------------------------|
| `name`          | String | required     | Names the target in logs, system mails and `test=<name>`. Unique.                                            |
| `apiKey`        | String | required     | Authenticates the caller and selects the target. Unique, at least 16 characters.                             |
| `to`            | String | required     | Recipient address.                                                                                           |
| `subjectPrefix` | String | `""`         | Prepended to the subject as-is.                                                                              |
| `bodyPrefix`    | String | `""`         | Prepended to the body as-is.                                                                                 |
| `bodyPostfix`   | String | `""`         | Appended to the body as-is.                                                                                  |
| `smtp.host`     | String | required     | SMTP server.                                                                                                 |
| `smtp.port`     | Int    | required     | SMTP port (typically 587 for `starttls`, 465 for `ssl`).                                                     |
| `smtp.username` | String | required     | SMTP login.                                                                                                  |
| `smtp.password` | String | required     | SMTP password.                                                                                               |
| `smtp.from`     | String | required     | Sender address.                                                                                              |
| `smtp.tls`      | String | `"starttls"` | `"starttls"` (required, not optional), `"ssl"` (implicit TLS) or `"none"` (plaintext, internal relays only). |

The server certificate is always verified when TLS is used. See [config.example.json](config.example.json)
for a complete example. Generate API keys with `java -jar knot.jar key`.

## Endpoints

### `POST /hook`

Authentication: header `X-API-Key: <key>`. If `allowApiKeyInQuery` is enabled, `?apiKey=<key>` is accepted as
well, the header wins if both are present. The key alone identifies the target: there is no path routing and
nothing to enumerate.

Body: a JSON object. Unknown fields are ignored, the `Content-Type` header is not checked.

```json
{"subject": "HighCPU firing", "body": "CPU > 90% for 5 minutes"}
```

- `body` (required): plain text, sent as-is.
- `subject` (optional): falls back to `KNot Message` when missing or blank. Line breaks are collapsed to spaces.

Subject and body are then framed by the target: `subjectPrefix + subject` and `bodyPrefix + body + bodyPostfix`.
Nothing is inserted between the parts, put a trailing space or a line break into the prefix yourself.

| Status | Meaning                                                                            |
|--------|------------------------------------------------------------------------------------|
| `200`  | Mail delivered to the SMTP server.                                                 |
| `400`  | Not a JSON object, or `body` missing/blank.                                        |
| `401`  | API key missing or unknown.                                                        |
| `413`  | Payload larger than 256 KiB.                                                       |
| `429`  | Rate limit of the target exceeded, nothing was sent. Comes with `Retry-After: 60`. |
| `502`  | SMTP delivery failed after all retries, details are in the log.                    |

From Kotlin with the [Ktor client](https://ktor.io/docs/client-requests.html) (any engine, no content
negotiation needed):

```kotlin
val response = client.post("https://knot.example.com/hook") {
    header("X-API-Key", "<apiKey of a target>")
    contentType(ContentType.Application.Json)
    setBody("""{"subject": "HighCPU firing", "body": "CPU > 90% for 5 minutes"}""")
}
```

### `GET /health`

No authentication. Answers `200 ok` as long as the process runs, intended for monitoring.

## Behaviour

**Delivery.** Synchronous within the request: 3 attempts with 1 s and 3 s pauses, 5 s connect and 10 s
read/write timeout. Rejected credentials are not retried, to avoid SMTP account lockouts. If all attempts fail,
the caller gets `502` after up to about 50 s. Give the client a matching timeout to avoid duplicate mails.
Every mail carries `Auto-Submitted: auto-generated` (RFC 3834), so out-of-office replies do not answer it.

**Rate limiting.** `rateLimitPerMinute` per target in a fixed one-minute window (monotonic clock, in memory,
reset on restart). Excess requests get `429`. Only the first rejection is logged. Meant against leaked keys,
not against DoS.

**System mails.** Sent to `default` (with its prefixes) unless `sendSystemMails` is `false`:

- `KNot started` (host, time, version) on startup, before the port is bound. A failure is only logged.
- `KNot stopped` (host, time) on every orderly shutdown, including a failed start. Not after a crash or `kill -9`.
- `KNot rate limit reached: <target>` once per episode. Re-armed after a full minute within the limit.

Timestamps use `dd.MM.yyyy HH:mm:ss z` in the host time zone with English abbreviations, e.g.
`19.09.2026 18:40:12 CEST`.

**Logging.** stdout/stderr, forwarded to the journal by systemd. Rejected API keys are logged with the client
address (`X-Forwarded-For` if set). API keys and SMTP passwords are never logged.

## Deployment

[knot.service](knot.service) is an example systemd unit for `/etc/systemd/system/knot.service`. systemd
restarts KNot after a failed start (exit code 1), but not after a rejected config (78).

- Use the fat JAR from the release. It needs Java 25 or newer, Amazon Corretto is recommended.
- Start it with `java -jar /path/to/knot.jar`, from a run script or directly in `ExecStart`.
- Run it as its own unprivileged user, e.g. `knot`.
- `chmod 600 config.json`, owned by that user. It holds SMTP passwords and API keys, other local users must not read it.

## Not in scope (deliberately)

No templating, no attachments, no HTML mails, no config hot reload, no TLS in the app, no persistence.

## License

[MIT](LICENSE)
