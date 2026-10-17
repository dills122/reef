// Benchmark topic provisioning for local Redpanda runs. The runtime producer and the matching engine auto-create
// their topics with NO config, which leaves broker-default write.caching and a 1 MiB max.message.bytes. These values
// match scripts/bench/profiles (REEF_TOPIC_WRITE_CACHING, REEF_TOPIC_MAX_MESSAGE_BYTES) and calcify-direct-bootstrap.mjs.
export const BENCH_TOPIC_WRITE_CACHING = "false";
export const BENCH_TOPIC_MAX_MESSAGE_BYTES = "4194304";

export function benchTopicConfigArgs(flag) {
  return [flag, `write.caching=${BENCH_TOPIC_WRITE_CACHING}`, flag, `max.message.bytes=${BENCH_TOPIC_MAX_MESSAGE_BYTES}`];
}

// rpk argument vectors (run inside the redpanda container). `create` fails when a topic exists, so callers run
// `alter` afterwards for every topic: that also fixes a topic left over from an earlier run.
export function benchTopicRpkCommands(topics, partitions) {
  const names = topics.filter(Boolean);
  if (names.length === 0) throw new Error("benchTopicRpkCommands: no topic names");
  if (!Number.isSafeInteger(partitions) || partitions <= 0) throw new Error(`invalid partition count ${partitions}`);
  return {
    create: ["rpk", "topic", "create", ...names, "-p", String(partitions), "-r", "1", ...benchTopicConfigArgs("-c")],
    alter: names.map((name) => ["rpk", "topic", "alter-config", name, ...benchTopicConfigArgs("--set")]),
  };
}
