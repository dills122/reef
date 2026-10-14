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
| ~~`CALCIFY_FINANCE_OWNER_EPOCH`~~ | removed | The process now **claims** the owner epoch itself at start (`FinanceProjector.claim`, after the restore cut): the first claim for a binding creates the checkpoint with epoch 1, each later start raises it by one, and an older instance is fenced on its next write. Setting the old variable is refused so it cannot be silently ignored |
| `CALCIFY_FINANCE_PROJECTION_INTERVAL_MS` | no | 50 to 10000, default 500 |
| `CALCIFY_FINANCE_SESSION_TIMEOUT_MS` | no | 6000 to 45000; unset keeps the pinned **6,000** (owner decision 2026-10-13: a SIGKILL restart took 44.1 s at the Kafka 45 s default and 6.1 s at 6,000, CAL-FIN-S0). This can only raise it |

Secrets are read only from `*_FILE` paths (private regular files, owner read/write only); an inline secret variable (for example `CALCIFY_FINANCE_KAFKA_PASSWORD`) is refused.

## Known limits (recorded, not hidden)

- **The owner epoch is claimed by the process** (decision record `CALCIFY_FINITE_P3_OWNER_EPOCH_DESIGN.md`, option B). Two live instances are no longer silent: the newer claim fences the older one, which exits with a fatal `StaleProjectionEpoch`, and a supervisor restart steals the projection back, so a misconfigured pair shows up as churn rather than as quiet duplicate work. The decision log is protected separately by Kafka (one application id, EOS transactional fencing). Still not authenticity: the chain is unkeyed.
- Not run live. The proof is `FiniteFinanceRuntimeTest` (the environment contract and every refusal; mutation-checked) and `FiniteFinanceRuntimeBrokerTest` (a SASL/SCRAM Redpanda with
  authorization on, the finance app under its minimal grants, a real Postgres projector role; a wrong observer credential starts nothing). Both run in the `calcify-tests` CI job.
- The Streams client pins `replication.factor=1` (a single-broker proof). A multi-broker deployment needs that changed in the spec section 8 pins first.
- The projection loop is **incremental** (`FinanceProjectionRunner.tailing`, `FinanceEvidenceReader.LogReader.readNext`): on ONE consumer kept for the life of the loop it reads only the decisions it has not read yet. The first pass after a start streams the log from offset 0 once and checks the SQL cut as it passes the stored position (the record at the stored sequence must be on the stored chain; a stored position beyond the log's end is a divergence); records at or before the stored progress are not re-applied; later passes check only that new records continue the sequence, and apply them in chunks of 2,000 (each chunk one projector transaction, so progress is durable at chunk boundaries). Records read but not yet written because a write failed are kept for the next pass. **Changed from the whole-log pass** (which re-read and re-checked the whole bounded log every pass): the full prefix check now happens at start, not every pass. A decision log that is rewritten or truncated under a running process is therefore caught by the consumer or by the sequence check, not by a fresh comparison of the whole prefix; owner decision if a periodic full re-check is wanted (default: no, restart re-checks). The whole-log mode remains for callers that supply a complete log.
- The Kafka transport defaults to `SASL_PLAINTEXT` like the other finite roles; TLS is a deployment decision (see the role sign-off packet).
