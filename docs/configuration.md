# Configuration

## Command line

Arguments are `key=value` pairs and bare words. Without `check`, `key` or `test=`, KNot starts the server. Any
other argument is rejected.

| Argument        | Default       | Meaning                                                                                                                  |
|-----------------|---------------|--------------------------------------------------------------------------------------------------------------------------|
| `config=<path>` | `config.json` | Config file, relative to the working directory                                                                           |
| `check`         |               | Load and validate the config, then exit. No mail, no server. Useful before `systemctl restart knot`                      |
| `key`           |               | Print a fresh API key and exit. Needs no config. Only the key goes to stdout, so `KEY=$(java -jar knot.jar key)` works   |
| `test=<name>`   |               | Send one test mail through the target `<name>` and exit. The config is validated as on a normal start, and the mail carries the target's prefixes, so those are checked too |

```bash
java -jar knot.jar check config=/path/to/config.json
```

```bash
java -jar knot.jar test=ops config=/path/to/config.json
```

| Exit code | Meaning                                                                                                                                       |
|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `0`       | `check`: the config is valid. `key`: key printed. `test=`: the test mail was delivered                                                        |
| `1`       | `test=`: unknown target, or delivery failed after all retries. Server: it could not start, e.g. the port is taken or the address is not assigned yet. systemd retries |
| `64`      | Unknown command line argument (`EX_USAGE`). systemd does not retry                                                                            |
| `78`      | The config file is missing or invalid (`EX_CONFIG`). systemd does not retry                                                                   |
| `143`     | The JVM's exit code after SIGTERM (`systemctl stop`). A clean stop, not a failure                                                             |

## Config file

One JSON file, read once at startup, so restart after a change. A syntax error is reported with its position, all
validation problems (blank fields, bad addresses, duplicate keys or names, short keys) at once, and unknown keys are
errors too, so a typo cannot silently fall back to a default. In every case KNot exits with `78` instead of running
with a half-valid config. The file holds SMTP passwords and API keys, see [Deployment](deployment.md) for its
permissions.

| Field                | Type           | Default       | Description                                                                                         |
|----------------------|----------------|---------------|-----------------------------------------------------------------------------------------------------|
| `listenHost`         | string         | `"127.0.0.1"` | Bind address. Use `"0.0.0.0"` only if the reverse proxy runs on another machine                     |
| `listenPort`         | int            | `8080`        | HTTP port                                                                                           |
| `sendSystemMails`    | bool           | `true`        | Send the [system mails](usage.md#system-mails) through `default`                                    |
| `rateLimitPerMinute` | int            | `10`          | Maximum hook requests per target and minute                                                         |
| `allowApiKeyInQuery` | bool           | `false`       | Also accept the key as `?apiKey=`. Only for senders that cannot set headers, the key then may end up in the reverse proxy's access log |
| `default`            | Target         | required      | Receives the system mails, usable as a regular webhook target as well                               |
| `targets`            | Target[]       | `[]`          | Further webhook targets                                                                             |

Target:

| Field           | Type   | Default      | Description                                                                                       |
|-----------------|--------|--------------|---------------------------------------------------------------------------------------------------|
| `name`          | string | required     | Names the target in logs, system mails and `test=<name>`. Unique                                  |
| `apiKey`        | string | required     | Authenticates the caller and selects the target. Unique, random, at least 16 characters. Generate it with `java -jar knot.jar key`, see [Security](security.md#api-keys) |
| `to`            | string | required     | Recipient address                                                                                 |
| `subjectPrefix` | string | `""`         | Prepended to the subject as-is                                                                    |
| `bodyPrefix`    | string | `""`         | Prepended to the body as-is                                                                       |
| `bodyPostfix`   | string | `""`         | Appended to the body as-is                                                                        |
| `smtp`          | object | required     | See below                                                                                         |

`smtp`:

| Field      | Type   | Default      | Description                                                                                   |
|------------|--------|--------------|-----------------------------------------------------------------------------------------------|
| `host`     | string | required     | SMTP server                                                                                   |
| `port`     | int    | required     | SMTP port, typically 587 for `starttls` and 465 for `ssl`                                     |
| `username` | string | required     | SMTP login                                                                                    |
| `password` | string | required     | SMTP password                                                                                 |
| `from`     | string | required     | Sender address                                                                                |
| `tls`      | string | `"starttls"` | `"starttls"` (required, not optional), `"ssl"` (implicit TLS) or `"none"` (plaintext, internal relays only) |

See [config.example.json](../config.example.json) for a complete example. Its placeholder API keys are too short on
purpose, so KNot refuses to start until they are replaced.

[Back to the README](../README.md)
