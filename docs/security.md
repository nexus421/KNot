# Security

How KNot authenticates callers, limits abuse and handles its secrets, and why.

## API keys

The API key is the only authentication KNot has, and it also selects the target. Keys are compared in constant
time, so response timing reveals nothing about how much of a guessed key was right. The config rejects keys shorter
than 16 characters and keys used by two targets. Responses never echo a key.

KNot does not slow down wrong keys. A brake for all requests would let anybody without a key block every valid
sender, and a brake per client address needs the real address, which only the reverse proxy knows. So the key itself
has to make guessing hopeless:

| Key                                           | Entropy | Time to guess at 4000 requests per second |
|-----------------------------------------------|---------|-------------------------------------------|
| From `java -jar knot.jar key` (43 characters) | 256 bit | out of reach                              |
| 16 random characters (the minimum)            | ~96 bit | about 10²⁰ years                          |
| Made up by a person, e.g. `grafana-alerts-01` | low     | a dictionary attack is realistic          |

Generate every key with `java -jar knot.jar key` (256 random bits, URL-safe Base64, so it also survives a query
parameter unencoded). If KNot is reachable from the internet, add a limit per client address in the reverse proxy,
see [Deployment](deployment.md#reverse-proxy).

The header `X-API-Key` is the default. `?apiKey=` is off by default, because a key in the URL may end up in access
logs.

## Rate limit

`rateLimitPerMinute` applies per target in a fixed one-minute window, in memory and reset on restart. The window uses
the monotonic clock, so an NTP step cannot keep it from expiring. Excess requests get `429` with `Retry-After: 60` and
send nothing. The first rejection of an episode is logged and, with `sendSystemMails`, reported by mail, the rest stay
quiet. The episode ends after a full minute within the limit. The limit is meant to dampen a leaked key, not to stop
a DoS.

## Request limits

- Bodies larger than 256 KiB are rejected with `413` while they are read. Unauthenticated and rate-limited requests
  are answered before the body is read at all.
- Only `subject` and `body` of the JSON payload are used. Line breaks in the subject become spaces and non-ASCII
  subjects are RFC 2047 encoded, so a caller cannot add mail headers. The SMTP layer escapes a line with a single dot
  in the body, so a caller cannot inject SMTP commands either.
- Recipient, sender and SMTP account come from the config only. A caller cannot change where a mail goes.

## SMTP

- `starttls` requires STARTTLS. If the server does not offer it, the delivery fails instead of falling back to
  plaintext, and no credentials are sent.
- `starttls` and `ssl` verify the certificate against the JVM's trust store and check that it matches `smtp.host`.
- `none` sends credentials and content in plaintext. Use it only for a relay on a trusted network.
- Connect, read and write timeouts are finite (5 s and 10 s), Jakarta Mail's defaults would wait forever.
- Rejected credentials are not retried, repeated failed logins get accounts locked.

## Logging

- Delivered mails, failed attempts, reached rate limits, system mails, start and stop.
- Rejected requests with the client address, at most 10 per minute plus one line when that is exceeded. Without
  that cap a flood of wrong keys would exceed journald's rate limit, and journald would then drop the real entries
  of the service as well. Every rejected request still gets its `401`.
- The client address is the `X-Forwarded-For` header if present, else the connection peer. KNot takes the header as
  sent, so it is only as trustworthy as the proxy that sets it.
- API keys and SMTP passwords are never logged. `Target` and `SmtpConfig` leave them out of `toString`.

## Config file

The config file holds every secret KNot has. Keep it at `0600`, owned by the service user, see
[Deployment](deployment.md#systemd). It is never versioned, `.gitignore` excludes `config.json`.

[Back to the README](../README.md)
