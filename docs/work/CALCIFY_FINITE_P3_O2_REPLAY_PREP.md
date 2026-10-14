# Finite P3 O2 live replay: preparation (S1-C2)

Status: prep only, prepared 2026-10-13. **Nothing in this document has been run.** The replay needs the owner's go-ahead for exactly one attempt.

## Purpose

The sprint plan (S1-C2) asks for one approved live replay of O2 gates G1-G8 and G10 on master, on the existing harness (plain Compose, no new monitor), with a 5-second broker interruption to answer the `assertOwned` question. Today G1-G8/G10 live evidence is inherited from the 2026-10-10 Codex campaign (MAIN12-MAIN35 and the G9 follow-ups), not replayed on master. G9 transport was re-proven on master (see the O2 verification record).
Timebox: 2 h, 20 min blocker timebox, one attempt, no retry without a new owner approval.

## What the harness needs (verified by reading `finite-o2-proof.mjs`, `finite-o2/`)

- **A manager resource lease JSON** (`--lease`): project name, prefix (`calcify-o2-*`), pinned image ids, network subnet, `proofRoot`, `secretRoot`, scope. Every live phase refuses without it. No example lease is committed; the previous one lives under the untracked `.planning/calcify-p3-o2-2026-10-10/`. This is the largest gap: it must be written fresh and reviewed.
- **Fresh build inputs:** a static probe binary (`FINITE_PROBE_BINARY`), the matcher image, and a pinned Java 21 runtime image holding `platform-runtime` (`FINITE_RUNTIME_IMAGE`). The compose file refuses to start without them, and the plan requires fresh build hashes.
- **Pinned images already cached locally:** Redpanda `sha256:9e83cfa9...` and Postgres `sha256:cf78e766...` (both confirmed present on this host today).
- **Limits baked in:** 3,968 MiB container memory, 4 GiB disk ceiling with abort at 3.625 GiB, 64 MiB proof bytes, 5 s samples, retained volume inventory counted by the harness.
- **Phases** (CLI `finite-o2-proof.mjs <phase> --lease ...`): `plan`, `prepare`, `pg-test-start`, `pg-test-init`, `bootstrap*`, `readiness`, `release`, `prove-flow`, `sample`, `crash`, `late-ack`, `cold-repair`, `restart`, `prove-budget`, `prove-attempt-budget`, `prove-authority`, `prove-api-isolation`, `missing-managed-state`, `mixed-prefix`, `wire-fit`, `stop`.
- **Offline gate before any live phase:** `make test-finite-o2-harness` (bun; macOS-local, not in Linux CI).

## Gate to phase map (from the acceptance matrix)

| Gate | Phases that evidence it |
| --- | --- |
| G1 identity | covered by golden-byte tests, no live phase |
| G2 durable budget | `prove-budget`, `prove-attempt-budget` |
| G3 ACK and uncertainty | `crash`, `late-ack`, `cold-repair`, `mixed-prefix` |
| G4 writer isolation | `prove-authority`, `prove-api-isolation` |
| G5 verified engine | `bootstrap`, `readiness` |
| G6 actual capture | `prove-flow`, `missing-managed-state` |
| G7 trusted release | `release` |
| G8 closure and restart | `restart` |
| G9 physical limits | `wire-fit`, `sample` (transport already re-proven on master) |
| G10 delivery | review and retention, not a live phase |

## The 5-second broker interruption

Not implemented as a phase. It needs either a new phase (pause or stop and restart the broker container for 5 s while the runtime holds ownership) or a manual step inside the recorded session. The `assertOwned` question: whether a short broker outage makes the runtime fail its ownership assertion and fence itself, or recover. The finite finance path already measured the equivalent consumer-session behaviour (6,000 ms session timeout), but the O2 ingress runtime is a different process. Recommend implementing it as a small, test-covered phase **before** the window rather than improvising live.

## Risks and costs

- The previous campaign needed about 38 attempts across MAIN12-MAIN38 (disk aborts, a 30 s initialization wait, canonical LF failure). "One attempt" is realistic only if the build inputs and lease are right. Expect a blocker report as a likely outcome and say so up front.
- Docker VM disk: retained volume inventory is counted. Clean up leaked volumes before starting (`docker volume ls`, `docker rm -f -v`).
- macOS-local only; hosted CI cannot reproduce it.
- Review budget: O2 review 2 of 3 consumed; instance 3 (targeted at `FiniteIngress` and the SQL path) is still pending and is not part of this replay.

## Built in this PR

`scripts/dev/calcify-financial/finite-o2-build-inputs.mjs --id NAME --subnet 10.x.y.z/28` (builds only; starts no container): refuses a dirty tracked tree, builds the static probe and matcher (Go, linux), runs `installDist` for the runtime, copies the distribution, writes the build logs as receipts, writes the source/build manifest (hashes of the harness, probe source, migration 0013, `FiniteRuntime.kt`, binding goldens) and a 2-hour full-scope lease under `/private/tmp/calcify-o2-NAME-*`. Runtime image defaults to the cached `reef-platform-runtime:latest` and matcher image to the cached `alpine:latest` (image ids only, no pull). The test `finite-o2-build-inputs.test.mjs` runs the builder's manifest and lease through the harness's own `verifyArtifacts` and shows tampered binaries and extra distribution files are refused (`make test-finite-o2-harness`).

**Not covered by the base lease:** the fault and adverse cases (`crash`, `late-ack`, `cold-repair`, `mixed-prefix`, `missing-managed-state`, topic-identity and command-history refusals) each use their own lease carrying `fault` or `adverse` fields and a fresh prefix. The previous campaign ran them as separate passes. The base lease supports the canonical flow (`prepare`, `bootstrap`, `readiness`, `release`, `prove-flow`, `restart`) and the budget, authority and API-isolation proofs. Covering the fault cases is a separate, larger step.

## Remaining work before the window

1. A 5-second broker interruption phase with an offline test (next PR, after this one merges).
2. Decide whether one attempt means the canonical-flow lease only (recommended; fault cases stay inherited) or the full multi-lease campaign (a multi-day effort last time).

## Owner decisions needed

- Scope of "one attempt": canonical-flow lease only (default) or the full fault campaign.
- Choose the window and confirm exactly one attempt, local Docker (default).
- Confirm whether the replay should include the interruption phase (default: yes).
