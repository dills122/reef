# Independent review: Calcify Phase 2 nightly checkpoint

2026-09-30; fresh-context read-only second-model engineering review of implementation branch. No reviewer edits or tests; source inspection plus installed Kafka4.3.1 bytecode inspection. Findings remain open; draft PR must not merge.

## P1 — verified-input retention can silently skip commitments

`CalcifyResolverRuntime.kt` configures input `auto.offset.reset=earliest`. When existing group checkpoint falls outside retained verified history, restart may reset and miss unstaged commitments. Source reader's retained-prefix guard cannot detect missing verified inputs. Existing broker retention test truncates source, not verified input.

Concrete fix: disable offset reset with unprefixed `auto.offset.reset=none`; pin `group.protocol=classic`, since Kafka4.3.1 Streams group protocol can bypass `KafkaClientSupplier.getConsumer` and therefore consumer gate. Wrap collection and pattern subscribe listeners in `ResolverConsumerGate.java`. On assigned nonempty set, obtain batched beginning/end offsets and committed checkpoints with bounded3s calls. Require beginning≤existing checkpoint≤end. Missing checkpoint only bootstraps beginning0, with explicit seek0. Propagate invalid range/timeouts, preserve original revoke/lost callbacks and invoke original assigned listener after validation/initial seeks. Kafka ConsumerCoordinator assigns before callback; Streams callback updates state and pauses initialization, without resetting these seeks. Resetnone covers retention occurring after assignment.

Real broker test: consume130, stop; seed130 unconsumed; delete verified prefix through260; seed130; restart same application. Require infrastructure failure/nonzero exit and no new output, with prior130 unchanged. Preserve current source-retention case.

## P1 — verified/output namespaces are not bound to persisted identity

Recreated output currently passes startup checks. Restored completed identities suppress replay, leaving missing output history while resolver can report healthy. Recreated verified topic with overlapping offsets can reuse checkpoint against different input history despite retention-range validation. `ensureTopics` can create missing output while existing application changelog survives.

Concrete fix: persist generation plus names/UUIDs for source, verified and output. Return actual verified/output IDs from `ensureTopics`, reject missing output before creation when application changelog exists, compare restored identity before processing, and extend existing once-per-second metadata check to all three configured UUIDs. Production broker-reader construction must reject zero/blank IDs; optional blank defaults only for injected-reader topology fixtures. Existing source-only checkpoint requires explicit migration/repair failure; prior verified/output UUIDs cannot be recovered retrospectively.

Tests: same-UUID restart; missing output restart; pre-recreated output; recreated verified with overlapping offsets; live output recreation. Missing-topic metadata can stop client as infrastructure failure; mismatch should persist lane fault. Output-deletion test asserts zero new output/failure, not preservation of history already deleted by test.

Limits: periodic metadata checks are not atomic cross-topic protection; simultaneous changelog plus namespace destruction cannot be recognized without separate external history. Do not claim those cases covered.

## Qualification remains open

No additional actionable code defect established in inspected Go run scope/snapshots, full-fact contracts, pure resolver, bounded queue/cache/source processing, Rocks configuration or canonical JSON framing. This is bounded review, not proof of absent defects. Later checkpoint adds shared string-token memo and infrastructure failure latch; final local tests pass, supervised failure exit still needs operational proof. All sustained attempts fail at least one frozen gate; final fixed-candidate repeats required.
