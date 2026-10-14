# Finite P3 finance projection: where the owner epoch comes from (decision record, proposed)

Status: **decided 2026-10-13: option B** (owner approved in chat; implemented in the PR that carries this edit). Original text follows; "proposed" and "nothing is implemented" below describe the state before the decision. It closes the review-3 finding P2-2 ("the owner fence is an operator-supplied monotone counter and protects little")
and the recorded open item in [`CALCIFY_FINITE_P3_FINANCE_RUNTIME.md`](CALCIFY_FINITE_P3_FINANCE_RUNTIME.md).

## What the epoch protects, and what it does not

`calcify_finance_checkpoint.owner_epoch` fences the **Postgres projection writer**: `FinanceProjector.apply` locks the checkpoint row, refuses `epoch < stored` (`StaleProjectionEpoch`),
accepts `==`, and raises the stored value when `epoch > stored`. Today the epoch is `CALCIFY_FINANCE_OWNER_EPOCH`, chosen by the operator.

It does **not** protect the decision log or the ledger. Those are protected by Kafka: one Streams application id means one task owner, EOS v2 transactional ids fence a zombie producer at the
broker, and the control writer has its own literal transactional id (tested in `FinanceAclTest` and the SIGKILL suites). A second finance process therefore cannot fork the decision log.

What can still go wrong with a bad epoch: two projectors with the same epoch both write. Every write is idempotent and re-verified (hash link, input digest, stored checksum), so they cannot make
the projection diverge from the log; the cost is wasted work and no signal that two instances are running. A zombie that was started with a *lower* epoch than the stored one is refused; one started with an *equal or
higher* epoch is not. So the operator-supplied epoch gives a correctness-neutral but silent failure mode.

## Options

| | Source of the epoch | Fences | Needs | Failure mode |
| --- | --- | --- | --- | --- |
| A. Status quo | operator environment variable | zombies with a lower epoch only | nothing | silent duplicates; a typo (too low) makes a new instance fail at once, a typo (too high) makes the right one fail later |
| B. Claim on start (recommended) | the projection itself: at start the process atomically increments `owner_epoch` in the checkpoint row and uses the result for every write | any older instance on its next write | `UPDATE` on `calcify_finance_checkpoint` (the projector role already has it); one new method | two supervised instances take turns stealing the projection: loud (each exits with `StaleProjectionEpoch`, fatal by design), never corrupting |
| C. Reuse the ingress run's owner epoch | `calcify_finite_runs.owner_epoch` (`calcify_finite_claim_owner`, a CAS that also requires the run to be STOPPED) | ties finance to ingress ownership | `EXECUTE` on `calcify_finite_snapshot` for the finance database role, or a new function | couples two roles that are deliberately separate; the ingress epoch changes when ingress restarts, which says nothing about the finance projector; adds a privilege to the least-privilege projector |
| D. Kafka-derived (consumer generation or producer epoch) | the finance group's generation, or a transactional id epoch | would tie the projection to the actual Streams owner | a new coupling between Kafka client internals and SQL | generation is not monotone across group resets; not a stable, auditable value |

## Recommendation: B

1. **Mechanism.** Add `FinanceProjector.claim(bindingDigest): Long`, one transaction: `SELECT ... FOR UPDATE` the checkpoint row (created at genesis by `open` with epoch 1 if absent), set
   `owner_epoch = owner_epoch + 1`, return the new value. The launcher calls it once, between "restore cut verified" and "start Streams", and uses the result as the `ownerEpoch` for every
   `apply`. `CALCIFY_FINANCE_OWNER_EPOCH` is removed (or kept only as an optional *minimum*, refusing a claim that would produce a lower value).
2. **Why this is safe.** Claiming happens after the restore cut, so a refused cut still touches nothing. A takeover is an explicit, recorded event: the stored epoch is a count of how many times a
   projector has started for this binding. The fence is unchanged (`apply` still refuses `epoch < stored`), so the existing tests keep their meaning.
3. **What it does not solve.** It does not make two live instances impossible; it makes the second one *loud* (the first dies with a fatal `StaleProjectionEpoch` the next time it writes, and a
   supervisor restart steals the projection back). It adds no authenticity: the decision log is still an unkeyed chain.
4. **Tests that would prove it** (each mutation-checked as in the earlier suites): a second `claim` raises the stored epoch by one; an instance holding the old epoch gets `StaleProjectionEpoch`
   and writes nothing; two launcher children against the same Redpanda and Postgres converge on one projection that equals the replay; a refused restore cut leaves the stored epoch unchanged.
5. **Cost.** About 40 lines of product code, one removed environment variable, four tests, a runbook edit. No migration: the column and the grants already exist.

## Not recommended: a single global lock

A Postgres advisory lock held for the lifetime of the process would give mutual exclusion, but it disappears on a dropped connection, so a partitioned zombie keeps writing from its own cached state
until it notices; the epoch fence is checked on every write and survives restarts, which is the property we want.

## Decision requested

Approve B (and removal of the operator-supplied epoch), choose A with a clearer runbook warning, or ask for C if finance is meant to follow ingress ownership. Until decided, the runbook's known
limit stands and the process role keeps `CALCIFY_FINANCE_OWNER_EPOCH`.
