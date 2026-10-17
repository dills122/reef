# Repository retention audit

Checked October 16, 2026 against `origin/master` at
`cab570aa518530dada1f7b9a5e5f07f33880129d`. This is the current
file-retention decision log. Recheck callers and current work before retiring
another path; a dated source reference does not establish current use.

Recent merged context: #581 added an Arena-lite projection profile design note;
#579 retired 19 superseded Calcify harnesses/spikes through Records; #578 added
clean-close lane snapshot restart and a SIGKILL test; #577 reconciled current
Calcify status; #574 enabled bounded lane memory by default. These merges make
older research harness names especially poor evidence of current ownership.

## Scope and method

Enumerated all 1,605 tracked files (20,903,198 working-tree bytes), read the
October 16 merged history, classified script consumers with
`node scripts/dev/script-surface-check.mjs --inventory`, checked exact tracked
references, Make/CI/package wiring, current Calcify and documentation owners,
and refreshed the codebase graph. Graph coverage is current for the selected
prototype and handoff paths. It has partial SQL parsing and excludes
`scripts/deploy/`; schema migrations and deployment tools were checked from
source and operational wiring instead. A zero-reference result alone was not
used as a deletion decision. Its 53 zero-degree function candidates mostly
resolve to externally invoked Make targets, SQL functions, Svelte template
helpers and test doubles; no whole production source file was selected from
that signal.

| Tracked area | Files | Decision |
| --- | ---: | --- |
| `services/` | 674 | Keep runtime, tests and checked-in contract bindings; deletion needs contract/build review. |
| `scripts/` | 463 | Keep migrations, runnable commands, imports and tests; retire 20 isolated experiment/research files below. |
| `docs/` | 202 | Keep owners, contracts and focused current evidence; archive 71 closed records and raw proofs below. |
| `apps/`, `packages/`, `contracts/` | 141 | Keep product source, shared code and versioned contracts. |
| `infra/`, `.github/` | 83 | Keep deployment/CI definitions and their separate trust boundaries. |
| Other tracked files | 42 | Keep root build/config and local agent guidance; no isolated bulk asset found. |

`scripts/dev/` has 431 tracked paths, including 138 ordered SQL migrations and
59 `lib/` files. Script checker sees 295 JavaScript, TypeScript and shell files:
117 tests, 94 runnable commands, 47 imported helpers, 24 test-only modules,
7 operational-documentation tools and 6 evidence-only scripts. These are
different roles, not 431 independent commands. Migrations are checksum-tracked
and applied in order; removing an old one breaks fresh database bootstrap.
Tests and fixtures support deterministic replay and cannot be classified as
dead solely because production does not import them.

## Retired in this pass

| Selection | Files | Reason |
| --- | ---: | --- |
| `scripts/experiments/calcify-phase2/` | 17 | Isolated September 30 Java/Python/Go prototype. README states no production matcher or resolver wiring; source fixture and decision report preserve proof, and current Phase 1/2 implementation has separate production paths. No Make, CI or package command calls this directory. |
| `docs/work/handoffs/` October 2–10 sessions and unfinished heap-guard patch | 6 | Closed session state. October 4 and 10 handoffs explicitly mark themselves historical; current tasking is in `WORK_PLAN.md` and Calcify program plan. |
| `.planning/calcify-p3-o2-2026-10-10/` | 9 | Completed O2 session alignment and review; current O3 packet and program plan own next work. |
| `docs/research/` | 16 | Dated Calcify measurements and review inputs; accepted conclusions remain in current design, baseline and program owners. |
| Other `docs/work/` | 17 | Read-only history, completed overnight plan, dated investigations and old raw financial text; active decisions and tasks remain in owner plans. |
| Prior raw evidence and receipts in `docs/evidence/` | 32 | Complete R08/terminal logs, reviews and prior financial receipts; focused checkpoints, current fixtures and complete archive proof remain available. |
| Three Calcify financial research probes and one RocksDB lookup spike test | 4 | Isolated dated measurements, outside default CI and supported run paths; conclusions are retained in current owners and exact source is archived. |

Total: 101 originals, 1,000,786 bytes. Exact originals, source commit and
SHA-256 values are in the two [relocation inventories](records/2026-10-16-repository-audit-migration.json)
and [expanded inventory](records/2026-10-16-repository-audit-broad-migration.json).
The original Phase 2 evidence manifest remains unchanged; its historical
source paths resolve through the Reef inventories. Source `cab570aa518530dada1f7b9a5e5f07f33880129d`;
[Records PR #16](https://github.com/dills122/reef-records/pull/16) carries
immutable imports `282d302db3adcff9bb38cd88a2b31447511c09b6` and
`f3c9941d5cd35df81b32970e9873ad8ca9f86391`.

## Retained after review

- The October 16 merged #579 cleanup already retired 19 superseded Calcify
  harnesses and research spikes. Its supported direct/full-chain path, current
  finance tools and capacity tests remain. No second retirement of those paths.
- `scripts/dev/db/migrations/` stays intact. Existing applied migrations are
  immutable bootstrap and upgrade inputs, even if their tables changed later.
- Checked-in Protobuf Java and Go bindings stay with `contracts/proto/`; builds
  and service code consume them. Byte-identical Gradle wrappers in separate
  services remain independent service build entrypoints.
- Phase 2 source fixture JSONL files and `docs/evidence/calcify-phase2/manifest.json`
  stay as exact provenance inputs. Current terminal-retention and runtime-order
  focused checkpoints stay local; complete logs and prior reviews resolve to
  immutable Records imports.
- The 24 test-only and 6 evidence-only script warnings remain review signals.
  Active finite-P3/financial work and distinct projection/arena proof contracts
  have no verified replacement. Do not mass-delete them from reference counts.

## Script-use audit, October 16 follow-up

Rechecked all 443 tracked `scripts/` paths after the first pass. The
[443-path inventory](https://github.com/dills122/reef-records/blob/7f1aa5d94107c307357d5681d931df94853ef777/docs/imports/2026-10-16-reef-script-surface-audit.tsv)
records each path's role, last source edit, static callers and decision. Of 294
JavaScript, TypeScript and shell paths, 117 are tests, 94 runnable commands,
48 imported helpers, 23 test-only modules, 8 operational-documentation tools
and 4 evidence-only scripts. The other 149 paths include 137 ordered SQL
migrations plus support files. These counts describe source roles, not run
frequency. Git gives last edit, not last execution; manually invoked Make
commands have no central run telemetry. September 25 projection proof and
October 11 contention proof bound known use of selected tools. No newer
retained invocation was found for the July campaign.

| Selection | Files | Current setup check |
| --- | ---: | --- |
| July throughput campaign/comparator and ablation ladder | 3 | Campaign defaults target 300–1,500 requests/s on old quality/abuse lanes, not the named 10k direct or full-chain stack. Ablation unconditionally removes Compose volumes. Make/manual wiring only; current profiles and baselines own qualification. |
| September downstream/projection capacity proof drivers and tests | 6 | Only their self-tests were in a Make aggregate; no current run target or production caller. Current source-cohort tests and projection measurement tools remain. |
| October 11 aged/matched contention runner, analyzers, tests and Kotlin SQL contender | 6 | Frozen proof hardcodes dated Docker project/image and host load binary. Historical results and original source snapshots are already in Records; current local configuration now points to supported profiles. |
| October 11 budget arithmetic model | 1 | Historical architecture-pressure-test input only; no operational caller. Original provenance manifest stays unchanged. |

Follow-up retires 16 originals, 150,104 bytes, after exact-byte publication in
[Records PR #17](https://github.com/dills122/reef-records/pull/17). The
[relocation inventory](records/2026-10-16-script-use-audit-migration.json)
pins source blob, SHA-256 and archive commit. Current direct/full-chain Calcify
commands, finance/credit and Arena controls, droplet microbench sources,
scenario paths, CI tests, fixtures and 137 ordered migrations remain. Python
financial reference encoder checks pass. This audit does not claim every
retained manual command has been run under today's configuration; retained
command owners still need profile-specific validation before use.

## Next bounded reviews

1. Review remaining `scripts/dev/calcify-financial/research/` paths against
   recovery, credit-calibration and mutation-proof tasks before retirement.
   Financial mutation check appears in the live program plan; the dated
   `run-contention.mjs` proof was archived in the follow-up above.
2. Review remaining `docs/work/` when each owner changes. Recent footprint work
   is in progress in another checkout, so this pass does not rewrite its evidence.
3. Review script command aliases against supported workflows, not script count.
   The October 13 script review retained projection and arena diagnostics for
   owner review; no equivalent replacement was established here.

These passes change no runtime, API, schema, scenario or throughput behavior.
No new performance qualification is claimed.
