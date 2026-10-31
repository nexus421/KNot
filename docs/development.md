# Development

Requirements: JDK 25 (Amazon Corretto is the pinned toolchain, Gradle downloads it if missing). Always use the
Gradle wrapper.

## Build and test

```bash
./gradlew test
```

```bash
./gradlew buildFatJar
```

`buildFatJar` builds `build/libs/knot.jar`. `./gradlew run` starts KNot from the sources and reads `config.json` in
the project directory. GitHub Actions runs the tests on every push
([test.yml](../.github/workflows/test.yml)).

`BuildConfig` (`VERSION` from `build.gradle.kts`, `BUILD_TIME` in Unix milliseconds) is generated into `build/` on
every build. It is never committed.

## Project layout

One package per concern under `bayern.kickner.knot`:

| Package / file | Content                                                                         |
|----------------|---------------------------------------------------------------------------------|
| `Main.kt`      | Argument handling, logging, config, server setup, shutdown hook                 |
| `cli`          | The `key` and `test=` commands                                                  |
| `config`       | Config model and strict loader with validation                                  |
| `mail`         | Mail framing, Jakarta Mail session properties, sending with retries             |
| `notify`       | System mails (started, stopped, rate limit reached)                             |
| `ratelimit`    | Fixed-window rate limiter on the monotonic clock                                |
| `routes`       | `POST /hook` and `GET /health`                                                  |

[Back to the README](../README.md)
