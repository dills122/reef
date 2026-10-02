# Calcify Phase 2 experiments

Isolated research harness. No production resolver or matcher wiring. Do not apply
`run-scope-prototype.patch` to production: it covers command/batch scope only;
compatibility queries, contracts and deployment ownership still need implementation.

Read [matrix](MATRIX.md) and [decision report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/CALCIFY_PHASE2_EXPERIMENTS_2026-09-30.md).

## Reproduce

From repository root, with Go, Java 25, Python 3 and Docker installed:

```sh
python3 scripts/experiments/calcify-phase2/run-fixtures.py
services/platform-runtime/gradlew -p scripts/experiments/calcify-phase2 --no-daemon classes dependenciesForRun
```

Run fixtures with actual Kafka Streams/RocksDB. Separate streams transaction and
rebalance tests require dedicated broker below; TopologyTestDriver proves neither.

```sh
CP='scripts/experiments/calcify-phase2/build/classes/java/main:scripts/experiments/calcify-phase2/build/deps/*'
java -Dorg.slf4j.simpleLogger.defaultLogLevel=error -cp "$CP" CalcifyExperiment unit docs/evidence/calcify-phase2/source-fixture.jsonl
```

Dedicated disposable RF1 broker; existing Reef stack need not run. Port 29092 must
be free. No shared topics or volumes are used. Docker resources: 2 CPU / 2 GiB.

```sh
docker run -d --name reef-calcify-p2-experiment --label reef.experiment=calcify-phase2 --cpus 2 --memory 2g -p 127.0.0.1:29092:29092 redpandadata/redpanda:v26.2.3 redpanda start --overprovisioned --smp 2 --memory 1G --reserve-memory 0M --node-id 0 --check=false --kafka-addr 0.0.0.0:29092 --advertise-kafka-addr 127.0.0.1:29092
python3 scripts/experiments/calcify-phase2/run-recovery.py
python3 scripts/experiments/calcify-phase2/run-availability.py
docker rm -f reef-calcify-p2-experiment
```

Recovery/availability scripts create fresh topic names, state directories and
`docs/evidence/calcify-phase2/E3-*` / `E5-*` artifacts per attempt. They stop their
workers in `finally`. Broker removal deletes only this disposable container's
experiment data. Raw logs and selected JSON survive in repository.

Local-store measurements, **excluding** Kafka, changelog/standby, source checksum
and parsing, producer acknowledgements, matching and intake. Unique empty output
directory required for each comparison; do not reuse an aged directory silently.
`lanes=4` uses four stores sequentially on one Java thread, not parallel scaling.

```sh
CP='scripts/experiments/calcify-phase2/build/classes/java/main:scripts/experiments/calcify-phase2/build/deps/*'
java -Xms256m -Xmx1g -cp "$CP" Capacity docs/evidence/calcify-phase2/source-fixture.jsonl 250000 20 0 1 /tmp/calcify-p2-knee-hot-fresh
java -Xms256m -Xmx1g -cp "$CP" Capacity docs/evidence/calcify-phase2/source-fixture.jsonl 250000 20 0 4 /tmp/calcify-p2-knee-spread-fresh
java -Xms256m -Xmx1g -cp "$CP" Capacity docs/evidence/calcify-phase2/source-fixture.jsonl 1000000 300 10000 1 /tmp/calcify-p2-aged-paced-fresh
/usr/bin/time -l java -Xms256m -Xmx1g -cp "$CP" Capacity docs/evidence/calcify-phase2/source-fixture.jsonl 1000000 30 10000 1 /tmp/calcify-p2-resource-fresh
```

`Capacity` measures complete fact-row serialization, two RocksDB puts, two gets,
V1 assembly/serialization and equality check. Synthetic unique order IDs are used;
trade IDs/source coordinates repeat deliberately because no durable output or
deduplication is measured. One sparse aged lookup per 100 joins does not establish
cold random lookup performance. WAL disabled matches disposable managed-store
measurement intent; there is no local durability claim from this benchmark.

## Harness limits

- Prototype JSON envelope measures full-fact bytes; production contract must use
  versioned Protobuf per steering. Short fixture IDs/repeated fields compress well.
- Source UUID checked on worker startup against managed state. Production also
  needs registered generation-to-topic identity and live change monitoring.
- `FlowControl` is experimental public `KafkaClientSupplier` integration. It
  gates consumer partitions on stream thread, respects faulted lanes across
  Streams resume calls, and hard-stops on pending overflow. Hard limit 200,
  poll limit 100. Production needs rebalance/overflow/clear-disposition tests.
- Faulted links remain in managed pending state; checkpoint may include durable
  pending links. No output/semantic completion occurs for them. Remaining links
  stay on broker. This is not a claim that broker offset never advances to stage
  a blocked link. Production must define staged versus completed frontiers.
- Two-input comparison deliberately exposes source-first backlog. It does not
  implement completed-frontier garbage collection; verifier may omit links.
- Worker debug logging scans tiny fixture state every five seconds. This diagnostic
  scan is excluded from local-store capacity measurements and must not enter
  production resolver hot path.
- Single RF1 broker on laptop cannot prove broker loss, RF3, cross-host failover,
  full-stack throughput, large-state restore SLO or production availability.
