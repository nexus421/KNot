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
the project directory. GitHub Actions runs the tests and builds the fat JAR on every push
([test.yml](../.github/workflows/test.yml)), then checks that Klogger is the SLF4J provider in it.

`BuildConfig` (`VERSION` from `build.gradle.kts`, `BUILD_TIME` in Unix milliseconds) is generated into `build/` on
every build. It is never committed.

## Release

1. Set `version` in `build.gradle.kts`, commit and push.
2. Publish a release on GitHub with the tag `v<version>`, e.g. `v1.2.0` for `version = "1.2.0"`.

[release.yml](../.github/workflows/release.yml) then checks that the tag matches the version, runs all tests, builds
`knot.jar` and attaches it to the release together with `knot.jar.sha256`. A tag that does not match fails the run
and attaches nothing.

The actions in both workflows are pinned to commit hashes, the comment names the version. Update both together.
`gradle/actions/setup-gradle` v6 keeps its caching in a closed-source component under the Gradle Terms of Use
(https://gradle.com/legal/terms-of-use/), using it means accepting them. `cache-provider: basic` would switch to an
open-source cache instead.

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
