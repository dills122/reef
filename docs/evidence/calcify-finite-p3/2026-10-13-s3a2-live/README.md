# S3-A2 live recording, attempt live-s3a2-1 (2026-10-13)

Result: **pass**. One approved attempt, no retry.

## What ran

`record-attempt.mjs live-s3a2-1 services/platform-runtime ./gradlew test -PbrokerTests` with six `--tests` filters, against a disposable Postgres
(`scripts/dev/calcify-financial/finite-p3-live-fixture.sh up`, env sourced) and Redpanda started by the suites. Exact command and head are in `attempts.jsonl`.

- Head: `7cea032701b60a415ffef57165ab991d8613332a` (master after PR 528). Exit code 0, 1m50s wall clock.
- Host: macOS 26.6.2, Apple M1 Max, 64 GiB, Docker 29.7.2, `java` on PATH 25.0.1 (Gradle toolchain choice not recorded; `javaHome` null in the record).
- Images: `redpandadata/redpanda:v26.2.3` (`sha256:9e83cfa99278f30d0133271c26bf670cd69c94ffa6ba0b42830dd0c3bd9dcfd9`), `postgres:16-alpine` (`sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`).

## Per-suite result (from the Gradle XML; skipped = 0 everywhere)

| Suite | Tests | Skipped | Failures | Time (s) |
| --- | --- | --- | --- | --- |
| FiniteP3EndToEndTest | 1 | 0 | 0 | 27.5 |
| FiniteFinanceLauncherCrashTest | 1 | 0 | 0 | 42.9 |
| FiniteFinanceRuntimeBrokerTest | 3 | 0 | 0 | 11.8 |
| FiniteFinanceLauncherBrokerTest | 3 | 0 | 0 | 11.6 |
| FinanceProjectorPostgresTest | 8 | 0 | 0 | 10.3 |
| JdbcFinanceReadStorePostgresTest | 2 | 0 | 0 | 0.3 |

- End to end: the test asserts eight decisions (three captures, two funds, three settles) and a checkpoint at sequence 8; it passed.
- F8: `F8 OK kills=3 afterProgress=3 insideTransaction=1 children=4 decisions=50`; kill rounds at 0, 14 and 25 decisions.

## Limits

Single host, closed network, plaintext transport (TLS: issue 527), one attempt, no capacity claim. Gradle stdout does not contain the test-level lines; those come from the XML under
`services/platform-runtime/build/test-results/test/`, which is not committed. Teardown: fixture `down` removed the container, its volume and the credential directory; `docker ps -a` and `docker volume ls` were empty.
The logs hold no credential material (grep for "password" and "secret" found nothing).
