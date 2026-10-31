# Deployment

Take `knot.jar` from a [GitHub release](https://github.com/nexus421/KNot/releases) (check it with
`sha256sum -c knot.jar.sha256`) or build it with `./gradlew buildFatJar` (then it is `build/libs/knot.jar`). It needs Java 25 or newer, Amazon Corretto is
recommended. Write `config.json` from [config.example.json](../config.example.json).

## systemd

[knot.service](../knot.service) is an example unit for `/etc/systemd/system/knot.service`. It runs KNot as the
unprivileged user `knot` from `/home/knot`, where it finds `config.json`, and starts it through `/home/knot/run.sh`:

```bash
useradd --create-home --shell /usr/sbin/nologin knot
install -o knot -g knot -m 0600 config.json /home/knot/config.json
install -o knot -g knot -m 0644 knot.jar /home/knot/knot.jar
printf '#!/bin/sh\nexec java -jar /home/knot/knot.jar\n' > /home/knot/run.sh
chmod 0755 /home/knot/run.sh
install -m 0644 knot.service /etc/systemd/system/knot.service
systemctl daemon-reload
systemctl enable --now knot
```

`config.json` holds SMTP passwords and API keys. Keep it at `0600` and owned by the service user, so other local
users cannot read it. Check it with `java -jar knot.jar check` before every `systemctl restart knot`.

systemd restarts KNot after a failed start (exit code `1`), at most 5 times in 5 minutes, because every attempt sends
a started and a stopped mail. It does not restart after a wrong command line (`64`) or a rejected config (`78`), a
restart cannot fix either. See [Configuration](configuration.md#command-line) for all exit codes.

## Reverse proxy

KNot speaks plain HTTP and leaves TLS to a reverse proxy such as Caddy or Zoraxy. Keep `listenHost` at `127.0.0.1`
when the proxy runs on the same machine. If it runs elsewhere, bind to the address the proxy reaches and allow the
port only from the proxy, because the hop between them carries API keys and mail content in plaintext.

- Let the proxy set or append `X-Forwarded-For`. KNot logs its last entry as the client address of rejected
  requests, see [Security](security.md#logging).
- If KNot is reachable from the internet, limit requests per client address in the proxy, e.g. with a rate limit
  rule or fail2ban on the proxy's access log. KNot cannot do that itself, it only sees the proxy, see
  [Security](security.md#api-keys).
- With `allowApiKeyInQuery` the key is part of the URL and may end up in the proxy's access log.

## Logs

KNot logs to stdout and stderr, systemd forwards both to the journal (`journalctl -u knot`). Libraries log through
Klogger's SLF4J provider, Ktor only from WARN on. What is logged and what never is, is described in
[Security](security.md#logging).

[Back to the README](../README.md)
