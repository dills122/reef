# Calcify finite P3 role sign-off packet (for the owner)

Status: **signed off by the owner on 2026-10-13** (decisions below). Written 2026-10-12 as a proposal. This page gathers every principal and grant the finite P3 financial path needs, the test that proves each,
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
| `calcify_finite_projector` | USAGE on schema `boundary`; SELECT, INSERT, UPDATE on the five `calcify_finance_*` tables | a stale owner epoch writes nothing (tested). DELETE, TRUNCATE, the ingress tables and the registrar/runtime functions refused (asserted in PR 518, `FinanceProjectorPostgresTest`) |
| `calcify_finite_reader` | USAGE on schema `boundary`; SELECT on the same five tables | INSERT, UPDATE, DELETE, TRUNCATE and the ingress tables (all asserted in `JdbcFinanceReadStorePostgresTest`) |

All four are `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE` with secrets from a secret store. The function owner is trusted and must not be used by a service.

## Owner decisions, 2026-10-13

- **Decision 1, the matrices: approved.** The Kafka and Postgres matrices above are the finite P3 provisioning list.
- **Decision 2, session timeout: decided earlier, 6,000 ms** (see below).
- **Decision 3, transport: the proof run is a closed network.** Plaintext transport is acceptable for it. TLS is filed as a follow-up: GitHub issue 527.
- **Decisions 4 and 5 (operator-route exposure, credential delivery and rotation): not decided.** For the closed-network local recording they are covered by construction (the routes stay local-only; the credentials are random disposable files, created and deleted by `scripts/dev/calcify-financial/finite-p3-live-fixture.sh`). They must be decided before any non-disposable deployment.

## Decisions for you (as originally posed)

0. **Projector-role test gap: closed in PR 518** (DELETE, TRUNCATE, the ingress tables and the finite functions are asserted refused; checked against two mutants). Review PR 518 with this packet.

1. **Approve the matrices above** as the finite P3 provisioning list (Kafka and Postgres). If yes, the DB roles document stops being a "draft for operator review" and the
   open item 4 in it is closed.
2. **Finance app `session.timeout.ms`: decided 2026-10-13, pinned at 6,000** (heartbeat 1,000), in `FinanceTopology.streamsProperties` (PR 519) and the binding v2 spec section 8. Measured 2026-10-12: a SIGKILL restart took 44.1 s on the Kafka 45 s default and 6.1 s at 6,000 (one run each). The remaining risk is rebalance sensitivity if processing ever stalls longer than the timeout; the finance processor does no long in-thread work by design. `CALCIFY_FINANCE_SESSION_TIMEOUT_MS` can only raise it.
3. **Transport.** The fixtures use SASL_PLAINTEXT and the Go matcher's TLS defaults off. A real proof run needs TLS on the broker listener and Postgres, or a stated reason it is
   a closed network. This is a deployment decision, not a code change.
4. **Operator route exposure.** The internal finance read routes carry every participant's balances and action keys and rely on the internal-HTTP exposure mode (default
   local only). Confirm the proof run will not enable it on a reachable interface without an authenticating proxy in front.
5. **Credential delivery and rotation** for the login roles and the SCRAM users (open item 2 in the DB roles document).

## Not covered

Production secrets handling, network policy, backup of the Redpanda data directory and the Postgres projection, and the owner-epoch source (the projector's epoch is
caller-supplied today; the ownership-epoch integration is a separate item).
