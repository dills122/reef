# Hot Book Sharding And In-Memory Book Plan

## Purpose

Define the next matching-engine storage and sharding direction after the durable-ingress and no-DB benchmark work.

The goal is to keep the matching hot book in engine-local memory while making shard ownership, replay, snapshotting, and cancel/modify performance explicit.

## Current Direction

The target venue-core path is:

```text
API / command router
  -> durable command log/topic acknowledgement
  -> matching-engine shard consumes assigned partitions
  -> shard mutates engine-local in-memory books
  -> shard publishes compact venue event batches
  -> command ack/offset commit only after event-batch durability
```

The hot book is not a database and is not distributed mutable state. It is shard-local memory owned by one deterministic writer at a time.

## Sharding Contract

Book ownership is based on the same routing key used for durable command partitioning:

```text
BookKey = runId + venueSessionId + instrumentId
partition = hash(BookKey) % partitionCount
shard = current owner of partition
```

Rules:

- submit, cancel, and modify commands for the same `BookKey` must route to the same partition.
- one partition is owned by one matching-engine shard at a time.
- one book is mutated by one shard-local command loop at a time.
- a hot single instrument/session has a legitimate single-lane ceiling unless the domain model changes.
- shard reassignment requires a drain/stop, snapshot/replay handoff, and explicit ownership transition.

## Operational Routing Model

Logical partition lanes are the unit of deterministic serialization. They do not need to map one-to-one to ticker symbols, worker processes, or matching-engine deployables.

For early and medium-scale markets, prefer a fixed configured lane count with deterministic routing:

```text
BookKey = runId + venueSessionId + instrumentId
partition = hash(BookKey) % partitionCount
```

One worker or engine shard may own many logical partitions. This lets Reef run `10`, `40`, or hundreds of instruments without creating one mostly idle process or consumer per instrument. Cold instruments can share lanes safely as long as each individual `BookKey` remains single-lane ordered.

When real traffic shows skew, use explicit routing overrides for hot books instead of making every symbol dedicated by default:

```text
(runId, venueSessionId, AAPL) -> partition 3
(runId, venueSessionId, TSLA) -> partition 4
other instruments              -> hash pool partitions 8..31
```

The first implementation should keep these overrides static configuration loaded at startup. Runtime routing changes are a later operational feature and must be versioned and replayable before they are used in production-shaped runs.

## Hot-Symbol Handling

Do not split one mutable instrument/session book across multiple concurrent writers as a quick scaling response. Price-level partitioning or cross-worker matching would change fairness, ordering, cancel/modify ownership, replay, and execution determinism; it is a separate market-model design, not a small sharding optimization.

Preferred responses when one symbol becomes hot:

1. Pin the hot `BookKey` to its own logical lane.
2. Move cold neighboring instruments away from the hot lane.
3. Increase physical workers only when logical lanes are already cleanly separable and telemetry shows CPU or drain capacity pressure.

Moving cold symbols away is the lowest-risk runtime posture because the hot book can stay on its current owner while other books drain and resume elsewhere.

True live migration of the hot book itself is deferred. If implemented later, it must be a pause-and-drain ownership transfer:

```text
stop or durably buffer new commands for BookKey
drain old lane through sequence N
snapshot or reconstruct book state at N
publish an audited routing-epoch transition
resume commands from N+1 on the new lane
verify replay checksum
```

Routing epochs must be recorded as durable operational facts. Replay must use the recorded routing decision and effective sequence; it must not rediscover hot symbols from current metrics.

## In-Memory Book Structure

The current implementation is moving from heap-backed side queues to a purpose-built book package:

```text
Book
  bids: ordered price levels
  asks: ordered price levels
  ordersByID: map[orderId]*OrderNode
  nextSequence

PriceLevel
  price
  FIFO order queue

OrderNode
  orderId
  side
  price
  sequence
  prev/next
  level pointer
```

Expected operation shape:

| Operation | Expected shape |
|---|---|
| submit at existing price | `O(1)` enqueue after price-level lookup |
| submit at new price | `O(log priceLevels)` |
| best-price lookup | tree edge lookup |
| match at best price | `O(1)` queue pop plus possible level delete |
| cancel | `O(1)` node unlink plus possible `O(log priceLevels)` empty-level delete |
| modify | cancel/replace with explicit priority reset |

The first implementation uses `github.com/tidwall/btree` only for ordered price levels. Matching semantics, order lifecycle, event IDs, replay expectations, and checksums remain Reef-owned.

## Persistence And Recovery Contract

The in-memory book is rebuilt from durable facts:

```text
latest shard/book snapshot
  + command/event replay after snapshot point
  + checksum verification
```

Snapshots are recovery accelerators, not the source of truth. Start with local compressed snapshot files before considering object storage or embedded KV stores.

Minimum snapshot metadata:

- `runId`
- `venueSessionId`
- `instrumentId`
- `partitionId`
- `shardId`
- `bookSequence`
- command stream/topic sequence or offset
- event stream/topic sequence or offset
- open orders and price levels
- checksum
- engine version and contract version

## Benchmark Gates

The hot-book change is not complete until focused benchmarks cover:

- alternating-cross workload
- deep resting-book growth
- deep-book cancel
- deep-book modify
- lifecycle mix
- hot single-instrument partition
- many instruments across partitions
- snapshot plus replay checksum

Capacity claims must distinguish:

- single hot-book ceiling
- multi-book partitionable capacity
- durable command-log ceiling
- durable event-batch publish ceiling
- projection/materialization throughput

## July 4, 2026 Checkpoint

The first ordered price-level implementation passed the current hot-book gate and ceiling probes locally:

- single hot book:
  - `10000/sec` for `60s`, `600000` processed, `0` failures
  - `15000/sec` for `30s`, `450000` processed, `0` failures
  - `20000/sec` for `30s`, `600000` processed, `0` failures
  - `30000/sec` for `30s`, `900000` processed, `0` failures
- deep resting-book growth:
  - `20000/sec` for `30s`, `600000` processed, `0` failures
- deep lifecycle cancel/modify:
  - `20000/sec` for `30s`, `600000` processed, `0` failures
- partitionable multi-book:
  - `60000/sec` with `6` instruments/workers, `1800000` processed, `0` failures
  - `80000/sec` with `8` instruments/workers, `2400000` processed, `0` failures
  - `100000/sec` with `10` instruments/workers, `3000000` processed, `0` failures

Detailed artifact paths are recorded in [`PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md).

The hot book is therefore no longer the active limiter for the current persistence-layer work. The next hot-book-adjacent work should be snapshot/replay checksums, longer mixed lifecycle soaks, and integration with durable event-batch publication.

## Deferred Options

Postpone:

- Redis for canonical order-book state.
- RocksDB/Pebble for hot-book storage.
- CGO/C++ matching-engine embedding.
- internal concurrent matching inside one book.
- object-storage snapshot archive until local snapshots and replay prove the operational shape.

Use external matching engines such as Liquibook, exchange-core, or C++ limit-order-book projects as design references only unless a future decision explicitly changes the implementation language or runtime boundary.
