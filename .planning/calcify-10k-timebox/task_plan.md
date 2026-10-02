# Calcify 10k timebox
Start 2026-10-02 03:38:05 UTC. Deadline 05:38:05 UTC.
Baseline 037971d63ebee882799e0bd78b7dd0035af1c3a6; branch codex/calcify-10k-timebox.
Target defaults to sustained 10,000 accepted order commands/s through current Phase1/2; unit clarification pending. Paired workload ~5,000 resolved trades/s.
Preserve durable ingress acknowledgement, same-lane ordering, deterministic replay, exact source/output facts. Stub verifier remains declared.
1. Refresh cached runtime/matching images and prepare owned local stack.
2. Establish joined HTTP baseline; inspect bottleneck.
3. Tune bounded current path and run frozen 300s target with exact receipts/resolved facts and bounded drain.
4. Preserve every attempt; report scope, gates, remaining risks. Stop timed work at deadline.
Worker json_spike owns test-only bounded paired fixture producer and independent actual-topic observer. Parent owns runtime/scripts/load/stack/evidence/Git.
