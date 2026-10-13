# Calcify finite P3 finance runtime (process role)

Status: implemented 2026-10-12 (`FiniteFinanceRuntime`, selected in `Main.kt`). It gives the S3-A2 launcher its production caller. It is an opt-in process role like the
finite ingress/capture JVM: it runs only when `CALCIFY_FINANCE_BINDING_DIGEST` is set, and it refuses to start if `CALCIFY_STAGE` or `CALCIFY_FINITE_BINDING_DIGEST` is also set.

## What it does

1. Reads the registered v2 opening from a private file and requires its digest to equal `CALCIFY_FINANCE_BINDING_DIGEST` (it holds no registrar credential).
2. Builds the launcher configuration from that opening only (`FinanceProcessorConfig.fromOpening`): application id, store and every topic.
3. `FiniteFinanceLauncher.start()`: evidence under the read-only `observer` principal, restore-cut verification (a refusal starts nothing and exits non-zero), the owner-fenced
   projection opened with the projector database role, then the Streams app under the `finance` principal, then projection passes.
4. Supervises: if the Streams app or the projection loop dies, it closes and exits 1 so the supervisor restarts it and the restore cut runs again. A clean stop (SIGTERM, or any close) ends supervision quietly with exit 0 and is never reported as a failure; a second close is a no-op. A clean stop (SIGTERM)
   closes the app and leaves the consumer group (`FinanceTopology.closeLeavingGroup`, PR 516), so a clean restart does not wait out the 45 s session timeout.

It holds exactly two broker credentials (`finance`, `observer`) and one database credential (the projector role). It does not hold the control-writer, registrar, runtime
or reader credentials. The read routes are served by the platform server (a different role) from the projection this role writes.

## Environment

| Variable | Required | Meaning |
| --- | --- | --- |
| `CALCIFY_FINANCE_BINDING_DIGEST` | yes | 64 lowercase hex; must equal the opening's digest |
| `CALCIFY_FINANCE_OPENING_FILE` | yes | registered v2 opening JSON (private regular file, at most 16,384 bytes) |
| `CALCIFY_FINANCE_KAFKA_BOOTSTRAP_SERVERS` | yes | broker list |
| `CALCIFY_FINANCE_KAFKA_SECURITY_PROTOCOL` | no | `SASL_PLAINTEXT` (default) or `SASL_SSL` |
| `CALCIFY_FINANCE_KAFKA_SASL_MECHANISM` | no | `PLAIN` (default, like finite ingress), `SCRAM-SHA-256`, `SCRAM-SHA-512` |
| `CALCIFY_FINANCE_KAFKA_USERNAME`, `CALCIFY_FINANCE_KAFKA_PASSWORD_FILE` | yes | the `finance` Streams principal |
| `CALCIFY_FINANCE_OBSERVER_KAFKA_USERNAME`, `CALCIFY_FINANCE_OBSERVER_KAFKA_PASSWORD_FILE` | yes | the read-only `observer` principal; must differ from `finance` |
| `CALCIFY_FINANCE_POSTGRES_JDBC_URL`, `CALCIFY_FINANCE_POSTGRES_USER`, `CALCIFY_FINANCE_POSTGRES_PASSWORD_FILE` | yes | the projector role. A URL containing a `password=` parameter is refused (use the file). Database TLS (`sslmode=`) is not required by the code; it is a deployment decision |
| `CALCIFY_FINANCE_STATE_DIRECTORY` | yes | owner-only directory for the RocksDB state (not a symlink; group or other permission bits are refused, because the state holds the full ledger) |
| `CALCIFY_FINANCE_OWNER_EPOCH` | yes | positive integer; the projection's owner fence (see below) |
| `CALCIFY_FINANCE_PROJECTION_INTERVAL_MS` | no | 50 to 10000, default 500 |
| `CALCIFY_FINANCE_SESSION_TIMEOUT_MS` | no | 6000 to 45000; unset keeps the 45 s default. A crash restarted in 44 s at the default and 6 s at 6,000 (CAL-FIN-S0). Owner decision, see the role sign-off packet |

Secrets are read only from `*_FILE` paths (private regular files, owner read/write only); an inline secret variable (for example `CALCIFY_FINANCE_KAFKA_PASSWORD`) is refused.

## Known limits (recorded, not hidden)

- **The owner epoch is operator-supplied.** It is not derived from the ingress owner claim, so two instances started with the same epoch are not told apart by the projection
  fence; run exactly one instance per binding and raise the epoch on every deliberate takeover. Integrating it with the ingress owner CAS is a separate item.
- Not run live. The proof is `FiniteFinanceRuntimeTest` (the environment contract and every refusal; mutation-checked) and `FiniteFinanceRuntimeBrokerTest` (a SASL/SCRAM Redpanda with
  authorization on, the finance app under its minimal grants, a real Postgres projector role; a wrong observer credential starts nothing). Both run in the `calcify-tests` CI job.
- The Streams client pins `replication.factor=1` (a single-broker proof). A multi-broker deployment needs that changed in the spec section 8 pins first.
- Each projection pass opens fresh evidence consumers (about two SASL connections per pass); reusing one consumer per pass, or polling only when the log end advanced, is the deferred F6 item.
- The Kafka transport defaults to `SASL_PLAINTEXT` like the other finite roles; TLS is a deployment decision (see the role sign-off packet).
