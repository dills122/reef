# Calcify finite P3 role sign-off packet (for the owner)

Status: proposed for owner sign-off, 2026-10-12. This page gathers every principal and grant the finite P3 financial path needs, the test that proves each,
and the points that need a decision. It adds no new requirement: the sources are [`CALCIFY_FINITE_P3_DB_ROLES.md`](CALCIFY_FINITE_P3_DB_ROLES.md),
[`CALCIFY_FINITE_P3_BINDING_V2_SPEC.md`](CALCIFY_FINITE_P3_BINDING_V2_SPEC.md) section 9 and
[`CALCIFY_FINITE_P3_S2_CLOSEOUT_RESEARCH.md`](CALCIFY_FINITE_P3_S2_CLOSEOUT_RESEARCH.md) section 5. Nothing here is provisioned by a migration; the operator creates every role.

## Kafka principals (Redpanda, SASL/SCRAM, authorization on)

Proven by `FinanceAclTest` on Redpanda v26.2.3 (one superuser; users created with `rpk`; every principal exercised against every topic; run in the `calcify-tests` CI job
with an executed-not-skipped gate).

| Principal | Needs (and nothing more) | Proven refused |
| --- | --- | --- |
| `ingress` | Write + Describe on COMMAND | write to CAPTURE, CONTROL, DECISIONS; read CONTROL, DECISIONS; either transactional id |
| `capture` | Write + Describe on CAPTURE (plus its existing SOURCE read) | write to COMMAND, CONTROL, DECISIONS; read CONTROL, DECISIONS; either transactional id |
| `control` (operator CLI) | Write + Read + Describe on CONTROL; Write + Describe on its literal transactional id | read CAPTURE or DECISIONS; write elsewhere; a finance-prefixed transactional id |
| `finance` (Streams app) | Read + Describe on CAPTURE and CONTROL; Write + Describe on FINANCE_DECISIONS; All on topics with the `financeApplicationId` prefix; Read + Describe on the group prefix; Write + Describe on the transactional-id prefix | write to CAPTURE, COMMAND, CONTROL; `initTransactions` with the control writer's id (it cannot fence or abort the control writer) |
| `observer` (activation verifier, read only) | Read + Describe on CAPTURE, CONTROL, DECISIONS and the changelog; Describe on the group | any write |
| `matcher` (existing) | unchanged | read or write CONTROL and FINANCE_* |

Authentication: anonymous and wrong-password clients are refused.

**New finding, closes the S2 close-out's open question.** The `ingress` and `capture` producers are idempotent. The test grants them the cluster-level
`IdempotentWrite` ACL. On 2026-10-12 the same test was run with both cluster grants removed and **passed unchanged on Redpanda v26.2.3** (1 test, 0 skipped): those
producers write without it. The grant is therefore not required on this broker. Recommendation: **keep it in the provisioning list anyway** (it is harmless, it
costs nothing, and it keeps the list valid if the broker or its authorizer changes), and mark it "not required on Redpanda v26.2.3" in the operator runbook.

## Postgres roles

Proven by `FiniteBindingStorePostgresTest`, `CalcifyTopicRoleMigrationTest`, `FinanceProjectorPostgresTest` and `JdbcFinanceReadStorePostgresTest` (CI fixture roles
`fin_runtime`, `fin_registrar`, `fin_projector`, `fin_reader`; executed-not-skipped gates in `ci.yml`).

| Role | Grants | Proven refused |
| --- | --- | --- |
| `calcify_finite_registrar` | EXECUTE on `register`, `stages`, `release`, `snapshot` | admit, send, acknowledge |
| `calcify_finite_runtime` | EXECUTE on `claim_owner`, `begin_attempt`, `authorize_send`, `ack`, `unknown`, `stop`, `snapshot`, `receipt` | register an opening, release a stopped run |
| `calcify_finite_projector` | USAGE on schema `boundary`; SELECT, INSERT, UPDATE on the five `calcify_finance_*` tables | a stale owner epoch writes nothing (tested). **Not asserted by any test (gap):** that DELETE, TRUNCATE, the ingress tables and the registrar/runtime functions are refused; that follows from the grant list only |
| `calcify_finite_reader` | USAGE on schema `boundary`; SELECT on the same five tables | INSERT, UPDATE, DELETE, TRUNCATE and the ingress tables (all asserted in `JdbcFinanceReadStorePostgresTest`) |

All four are `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE` with secrets from a secret store. The function owner is trusted and must not be used by a service.

## Decisions for you

0. **Close the projector-role test gap?** A small Postgres test that the projector role is refused DELETE, TRUNCATE and the ingress tables would make the matrix fully proven. Recommended; not done in this packet.

1. **Approve the matrices above** as the finite P3 provisioning list (Kafka and Postgres). If yes, the DB roles document stops being a "draft for operator review" and the
   open item 4 in it is closed.
2. **Finance app `session.timeout.ms`.** Production `FinanceTopology.streamsProperties` sets none, so a crashed finance app (SIGKILL, not a clean stop) holds its partition
   for the default 45 s before a restart can take over. The test fixture and the resolver runtime (`CALCIFY_RESOLVER_SESSION_TIMEOUT_MS`, 6,000) already lower it. Pinning
   a lower value (for example 6,000 with a 1,000 ms heartbeat) speeds crash recovery and raises rebalance sensitivity; static membership is the alternative. Clean
   stops are already fixed (PR 516). Recommendation: **pin 6,000 for the finance app** and add it to the section 8 pinned properties, but only after a crash-restart measurement.
3. **Transport.** The fixtures use SASL_PLAINTEXT and the Go matcher's TLS defaults off. A real proof run needs TLS on the broker listener and Postgres, or a stated reason it is
   a closed network. This is a deployment decision, not a code change.
4. **Operator route exposure.** The internal finance read routes carry every participant's balances and action keys and rely on the internal-HTTP exposure mode (default
   local only). Confirm the proof run will not enable it on a reachable interface without an authenticating proxy in front.
5. **Credential delivery and rotation** for the login roles and the SCRAM users (open item 2 in the DB roles document).

## Not covered

Production secrets handling, network policy, backup of the Redpanda data directory and the Postgres projection, and the owner-epoch source (the projector's epoch is
caller-supplied today; the ownership-epoch integration is a separate item).
