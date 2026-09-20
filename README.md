# KNot

**K**otlin **Not**ification: a small webhook-to-mail bridge. KNot accepts an HTTP webhook and sends its content
as a plain-text e-mail over SMTP. It exists for services that can call a webhook but cannot send mail
themselves — Grafana alerts, CI/CD pipelines, uptime checks, small internal tools.

KISS by design: no UI, no database, no runtime configuration. One JSON config file, one fat JAR, one systemd
service. TLS termination is left to a reverse proxy (e.g. Caddy, Zoraxy); KNot itself listens on plain HTTP, by default
only on `127.0.0.1`.

## Quick start

Requirements: JDK 25 to build (Amazon Corretto is the pinned toolchain; the wrapper downloads it if missing) and a
Java 25 runtime on the machine that runs `knot.jar`.

```bash
cp config.example.json config.json   # then fill in API keys, recipients and SMTP accounts
./gradlew run                        # development: reads ./config.json
./gradlew buildFatJar                # production: build/libs/knot.jar
java -jar build/libs/knot.jar config=/opt/knot/config.json
```

Then:

```bash
curl -X POST http://127.0.0.1:8080/hook \
  -H "X-API-Key: <apiKey of a target>" \
  -H "Content-Type: application/json" \
  -d '{"subject": "HighCPU firing", "body": "CPU > 90% for 5 minutes"}'
```
Minimal call from a Kotlin service with the [Ktor client](https://ktor.io/docs/client-requests.html) (any
engine, no content negotiation needed):

```kotlin
val response = client.post("https://knot.example.com/hook") {
    header("X-API-Key", "<apiKey of a target>")
    contentType(ContentType.Application.Json)
    setBody("""{"subject": "HighCPU firing", "body": "CPU > 90% for 5 minutes"}""")
}
```

Tests: `./gradlew test` — GitHub Actions runs them on every push (`.github/workflows/test.yml`).

## Configuration

One JSON file, `config.json` in the working directory by default; override with the `config=<path>` argument.
The file is read once at startup — after a change, restart the service. A syntax error is reported with its
position; validation problems (blank fields, bad addresses, duplicate keys, ...) are all reported at once. In
both cases KNot exits with code 78 (`EX_CONFIG`) instead of running with a half-valid config — the same happens
when the configured address cannot be bound, e.g. because the port is taken. Unknown keys are errors too, so a
typo cannot silently fall back to a default.

| Field                     | Type           | Default       | Description                                                                 |
|---------------------------|----------------|---------------|-----------------------------------------------------------------------------|
| `listenHost`              | String         | `"127.0.0.1"` | Interface to bind to. Use `"0.0.0.0"` only if the reverse proxy runs elsewhere. |
| `listenPort`              | Int            | `8080`        | Port to listen on.                                                          |
| `sendSystemMails`         | Boolean        | `true`        | Send system mails through `default`: KNot started/stopped, rate limit reached. |
| `rateLimitPerMinute`      | Int            | `10`          | Maximum hook requests per target and minute.                                |
| `allowApiKeyInQuery`      | Boolean        | `false`       | Additionally accept the API key as `?apiKey=` query parameter. Only for senders that cannot set headers: the key then may shows up in the reverse proxy's access log. |
| `default`                 | Target         | required      | Receives the system mails; usable as a regular webhook target as well.      |
| `targets`                 | List\<Target\> | `[]`          | Further webhook targets.                                                    |

A `Target`:

| Field           | Type   | Default      | Description                                                               |
|-----------------|--------|--------------|---------------------------------------------------------------------------|
| `name`          | String | required     | Only used in log messages.                                                |
| `apiKey`        | String | required     | Authenticates the caller and selects the target. Unique, at least 16 characters. |
| `to`            | String | required     | Recipient address.                                                        |
| `subjectPrefix` | String | `""`         | Prepended to the subject as-is.                                           |
| `bodyPrefix`    | String | `""`         | Prepended to the body as-is.                                              |
| `bodyPostfix`   | String | `""`         | Appended to the body as-is.                                               |
| `smtp.host`     | String | required     | SMTP server.                                                              |
| `smtp.port`     | Int    | required     | SMTP port (typically 587 for `starttls`, 465 for `ssl`).                  |
| `smtp.username` | String | required     | SMTP login.                                                               |
| `smtp.password` | String | required     | SMTP password.                                                            |
| `smtp.from`     | String | required     | Sender address.                                                           |
| `smtp.tls`      | String | `"starttls"` | `"starttls"` (required, not optional), `"ssl"` (implicit TLS) or `"none"` (plaintext — internal relays only). |

The server certificate is always verified when TLS is used. See [config.example.json](config.example.json)
for a complete example. Generate API keys with something like `openssl rand -base64 32`.

## Endpoints

### `POST /hook`

Authentication: header `X-API-Key: <key>`. If `allowApiKeyInQuery` is enabled, `?apiKey=<key>` is accepted as
well; the header wins if both are present. The key alone identifies the target — there is no path routing and
nothing to enumerate.

Body: a JSON object. Unknown fields are ignored, the `Content-Type` header is not checked.

```json
{"subject": "HighCPU firing", "body": "CPU > 90% for 5 minutes"}
```

- `body` (required): plain text, sent as-is.
- `subject` (optional): falls back to `KNot Message` when missing or blank. Line breaks are collapsed to spaces.

Subject and body are then framed by the target: `subjectPrefix + subject` and `bodyPrefix + body + bodyPostfix`.
Nothing is inserted between the parts — put a trailing space or a line break into the prefix yourself.

| Status | Meaning                                                        |
|--------|----------------------------------------------------------------|
| `200`  | Mail delivered to the SMTP server.                             |
| `400`  | Not a JSON object, or `body` missing/blank.                    |
| `401`  | API key missing or unknown.                                    |
| `413`  | Payload larger than 256 KiB.                                   |
| `429`  | Rate limit of the target exceeded; nothing was sent.           |
| `502`  | SMTP delivery failed after all retries; details are in the log.|

### `GET /health`

No authentication. Answers `200 ok` as long as the process runs — intended for monitoring.

## Behaviour

**Delivery.** Mails are sent synchronously inside the request: 3 attempts with 1 s and 3 s pauses in between,
5 s connect and 10 s read/write timeouts per attempt. Rejected credentials are not retried (repeated failed
logins get SMTP accounts locked). Failed attempts are logged with the target name, never with credentials.
After the last failed attempt the caller gets `502`. With an unreachable SMTP server a request can therefore
take up to about 50 s before it fails — give the sending side a matching timeout, or it may retry and produce
duplicate mails.

**Rate limiting.** A fixed one-minute window per target, counted in memory from the first request of the
window. Requests beyond `rateLimitPerMinute` are answered with `429` without sending anything. A restart resets
the counters, which is harmless. This limits the damage of a leaked key; it is not an anti-DoS measure.
The first rejection of a target is logged; the following ones are not, so an attack cannot flood the journal.

**System mails.** Unless `sendSystemMails` is `false`, `default` receives:

- `KNot started` (host, time, version) when KNot starts up. Best effort — a failure is logged and the
  service keeps running.
- `KNot stopped` (host, time) on shutdown, sent from a JVM shutdown hook; the process ends once the delivery
  attempt is over. The mail therefore only exists for an orderly stop (`systemctl stop`, SIGTERM, Ctrl+C),
  not after a crash or `kill -9`.
- `KNot rate limit reached: <target>` when a target starts exceeding its rate limit — once per episode, not
  per rejected request: while the target keeps exceeding the limit minute after minute, no further mail is
  sent. The report is re-armed after a full minute within the limit (or without any requests), so the next
  time the limit is reached, a new mail goes out. The mail never contains the API key, and the `429` response
  does not wait for it.

Like every other mail, system mails carry the default target's prefixes.

**Logging.** Everything goes to stdout/stderr (Klogger), which systemd forwards to the journal. Neither API
keys nor SMTP passwords are ever logged.

## Deployment

```bash
./gradlew buildFatJar            # -> build/libs/knot.jar
sudo useradd --system --home /opt/knot --shell /usr/sbin/nologin knot
sudo mkdir -p /opt/knot && sudo cp build/libs/knot.jar /opt/knot/
sudo cp config.example.json /opt/knot/config.json   # edit it, then:
sudo chown -R knot:knot /opt/knot && sudo chmod 600 /opt/knot/config.json
sudo cp knot.service /etc/systemd/system/knot.service
sudo systemctl daemon-reload
sudo systemctl enable --now knot.service
```

[knot.service](knot.service) restarts KNot on failure, but not after exit code 78 (rejected config or a port
that cannot be bound) — fix the cause and run `sudo systemctl restart knot.service`. Put a reverse proxy in
front for TLS, e.g. Caddy:

```
knot.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

## Not in scope (deliberately)

No templating, no attachments, no HTML mails, no config hot reload, no TLS in the app, no persistence.
Ideas for later: per-target rate limits, `cc`/`bcc`, HMAC-signed requests, a metrics endpoint.

## License

[MIT](LICENSE)
