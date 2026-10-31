# Using KNot

## Sending a hook

`POST /hook` with the API key of a target in the header `X-API-Key`. If `allowApiKeyInQuery` is enabled,
`?apiKey=<key>` is accepted as well, and the header wins if both are present. The key alone identifies the target:
there is no path routing and nothing to enumerate.

The body is a JSON object. Unknown fields are ignored and the `Content-Type` header is not checked.

```json
{"subject": "HighCPU firing", "body": "CPU > 90% for 5 minutes"}
```

| Field     | Required | Description                                                                         |
|-----------|----------|-------------------------------------------------------------------------------------|
| `body`    | yes      | Plain text, sent as-is                                                              |
| `subject` | no       | Falls back to `KNot Message` when missing or blank. Line breaks become spaces       |

The target frames both: the mail gets `subjectPrefix + subject` and `bodyPrefix + body + bodyPostfix`. Nothing is
inserted between the parts, so put a trailing space or a line break into the prefix yourself.

From Kotlin with the [Ktor client](https://ktor.io/docs/client-requests.html) (any engine, no content negotiation
needed):

```kotlin
val response = client.post("https://knot.example.com/hook") {
    header("X-API-Key", "<apiKey of a target>")
    contentType(ContentType.Application.Json)
    setBody("""{"subject": "HighCPU firing", "body": "CPU > 90% for 5 minutes"}""")
}
```

## Responses

| Status | Meaning                                                                            |
|--------|------------------------------------------------------------------------------------|
| `200`  | Mail delivered to the SMTP server                                                  |
| `400`  | Not a JSON object, or `body` missing or blank                                      |
| `401`  | API key missing or unknown                                                         |
| `413`  | Payload larger than 256 KiB                                                        |
| `429`  | Rate limit of the target exceeded, nothing was sent. Comes with `Retry-After: 60`  |
| `502`  | SMTP delivery failed after all retries, the log has the details                    |

## Delivery

Delivery is synchronous within the request: 3 attempts with 1 s and 3 s pauses, 5 s connect and 10 s read and write
timeout. Rejected credentials are not retried, to avoid SMTP account lockouts. If all attempts fail, the caller gets
`502`, with an unreachable SMTP server after about 50 s. Give the client a matching timeout to avoid duplicate mails.

Every mail carries `Auto-Submitted: auto-generated` (RFC 3834), so compliant autoresponders such as out-of-office
replies do not answer it.

## System mails

Sent to the `default` target (with its prefixes) unless `sendSystemMails` is `false`:

| Mail                                 | When                                                                                     |
|--------------------------------------|------------------------------------------------------------------------------------------|
| `KNot started`                       | On startup, before the port is bound. Holds host, time and version                       |
| `KNot stopped`                       | On every orderly shutdown, including a failed start. Not after a crash or `kill -9`      |
| `KNot rate limit reached: <target>`  | Once per episode, re-armed after a full minute within the limit                          |

A system mail that cannot be sent is only logged. Timestamps use `dd.MM.yyyy HH:mm:ss z` in the host time zone with
English abbreviations, e.g. `19.09.2026 18:40:12 CEST`.

## Health check

`GET /health` needs no authentication and answers `200 ok` as long as the process runs. It is meant for monitoring.

[Back to the README](../README.md)
