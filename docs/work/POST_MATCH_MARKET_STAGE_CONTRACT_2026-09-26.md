# Post-match market stage contract — 2026-09-26

The live consumer commits a market change window in the isolated operational
store alongside order state, execution/trade facts, source coverage, and its
frontier. Every live source window produces one change window, even when no
visible price level changes. This lets the market maintainer prove contiguous
progress without assuming that every command changes the public book.

Each change records the prior and final displayed contribution of one order.
The live writer coalesces repeated states for the same order within a window,
then emits the net change. Only `LIMIT` orders in `ACCEPTED` or
`PARTIALLY_FILLED` state with positive remaining quantity contribute; hidden
and terminal orders contribute zero. The canonical order directory supplies
run, venue session, instrument, currency, and side. A cancellation removes the
remaining displayed quantity without inventing an execution.

The independent market maintainer locks its own frontier, takes exactly the
next committed change window, aggregates deltas by run/session/instrument/currency/
side/price, and updates only those price levels. It refreshes snapshots with
indexed best-bid and best-ask lookups for affected books. Price levels and
frontier commit in one transaction. Gaps, negative levels, source-generation
changes, and change-count mismatches fail closed. Replay sees no next window
after committed progress. No current API route reads these tables yet.

The market frontier may lag the live frontier. A later read adapter must use
both frontiers and explicit as-of metadata; an own-order response cannot imply
market freshness. Rebuild begins at source origin in a new generation. Hosted
throughput qualification must measure both frontiers and market snapshot
visibility on the same command cohort.
