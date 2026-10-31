# KNot

[![Tests](https://github.com/nexus421/KNot/actions/workflows/test.yml/badge.svg)](https://github.com/nexus421/KNot/actions/workflows/test.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-25-ED8B00?logo=openjdk&logoColor=white)
![Ktor](https://img.shields.io/badge/Ktor-3.6-087CFA?logo=ktor&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue)

**K**otlin **Not**ification, a lean webhook-to-mail bridge. KNot accepts an HTTP webhook and sends its content as a
plain-text mail over SMTP. It is for services that can call a webhook but cannot send mail themselves: Grafana
alerts, CI/CD pipelines, uptime checks, small internal tools. One JSON config file, one fat JAR, one systemd service.

## Features

- One endpoint, `POST /hook` with a small JSON body. The API key alone selects the target
- Any number of targets, each with its own recipient, SMTP account and subject and body framing
- STARTTLS or implicit TLS with certificate and host name verification, plaintext only on request for internal relays
- Retries for transient SMTP failures, none for rejected credentials, finite SMTP timeouts
- Rate limit per target, with one system mail when a target reaches it
- System mails when KNot starts and stops
- Strict config: unknown keys and invalid values stop the start, all problems are reported at once
- `check`, `key` and `test=<target>` on the command line
- No database, no UI, no state beyond the running process

## Quick start

Requires JDK 25 to build and Java 25 to run.

```bash
./gradlew buildFatJar
```

```bash
cp config.example.json config.json
```

Fill in recipients and SMTP accounts in `config.json` and give every target a fresh API key from
`java -jar build/libs/knot.jar key`. Check the SMTP account of a target with a test mail, then start the server:

```bash
java -jar build/libs/knot.jar test=ops config=config.json
```

```bash
java -jar build/libs/knot.jar config=config.json
```

Send a hook:

```bash
curl -X POST http://127.0.0.1:8080/hook \
  -H "X-API-Key: <apiKey of a target>" \
  -H "Content-Type: application/json" \
  -d '{"subject": "HighCPU firing", "body": "CPU > 90% for 5 minutes"}'
```

KNot listens on plain HTTP, by default only on `127.0.0.1`. TLS is left to a reverse proxy such as Caddy or Zoraxy,
see [Deployment](docs/deployment.md).

## Documentation

- [Usage](docs/usage.md): sending hooks, payload, responses, delivery and system mails
- [Configuration](docs/configuration.md): command line, exit codes, config file
- [Deployment](docs/deployment.md): systemd, reverse proxy, file permissions
- [Security](docs/security.md): API keys, rate limit, request limits, SMTP transport, logging
- [Development](docs/development.md): build, tests, project layout

## Why KNot

KNot does one thing: it turns a webhook into a mail. It is reduced to what that needs and aims to be simple, stable,
secure and reliable. KISS by design, so there is deliberately no templating, no attachments, no HTML mails, no config
hot reload, no TLS in the app and no persistence.

KNot is written with AI. Code, tests and documentation were created with Claude by Anthropic and fully reviewed by
me.

## License

[MIT](LICENSE), Copyright (c) 2026 nexus421.
