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
