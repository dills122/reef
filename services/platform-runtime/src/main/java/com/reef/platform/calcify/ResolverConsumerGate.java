package com.reef.platform.calcify;

import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.KafkaClientSupplier;

/**
 * Partition flow control, plus a fail-closed retention gate on every
 * rebalance assignment. Kafka Streams owns transactions and restoration;
 * this class only pauses/resumes partitions for lane-fault backpressure
 * (unrelated to retention) and validates, before any record is delivered
 * to Streams, that a restored checkpoint still falls within the broker's
 * retained range for the verified-topic consumer. auto.offset.reset is
 * configured as "none" precisely so that an out-of-range offset surfaces
 * here as a thrown exception instead of a silent, undetectable reset.
 */
public final class ResolverConsumerGate implements KafkaClientSupplier {
  static final ThreadLocal<GateConsumer> CURRENT = new ThreadLocal<>();
  private static final Duration OFFSET_LOOKUP_TIMEOUT = Duration.ofSeconds(3);

  /**
   * Pure: for each partition with retained range [beginning, end], a
   * missing committed offset means a brand-new consumer group, seeking
   * explicitly to beginning rather than relying on any automatic reset
   * policy. A committed offset outside [beginning, end] means retention
   * has already advanced past a position Streams still depends on -
   * that is unrecoverable data loss, not a transient condition, so it
   * throws rather than silently seeking anywhere.
   */
  static Map<TopicPartition, Long> seekTargets(
      Map<TopicPartition, Long> beginning,
      Map<TopicPartition, Long> end,
      Map<TopicPartition, Long> committed) {
    Map<TopicPartition, Long> seeks = new HashMap<>();
    for (var entry : beginning.entrySet()) {
      TopicPartition tp = entry.getKey();
      long b = entry.getValue();
      long e = end.getOrDefault(tp, b);
      Long c = committed.get(tp);
      if (c == null) {
        seeks.put(tp, b);
      } else if (c < b || c > e) {
        throw new IllegalStateException(
            "Calcify verified consumer retention lost for " + tp + ": committed=" + c
                + " beginning=" + b + " end=" + e);
      }
    }
    return seeks;
  }

  public static void blocked(String topic, int partition, boolean blocked) {
    var c = CURRENT.get();
    if (c != null) c.setBlocked(new TopicPartition(topic, partition), blocked);
  }

  public Admin getAdmin(Map<String, Object> c) {
    return Admin.create(c);
  }

  public Producer<byte[], byte[]> getProducer(Map<String, Object> c) {
    return new KafkaProducer<>(c, new ByteArraySerializer(), new ByteArraySerializer());
  }

  public Consumer<byte[], byte[]> getConsumer(Map<String, Object> c) {
    return new GateConsumer(c);
  }

  public Consumer<byte[], byte[]> getRestoreConsumer(Map<String, Object> c) {
    return new KafkaConsumer<>(c, new ByteArrayDeserializer(), new ByteArrayDeserializer());
  }

  public Consumer<byte[], byte[]> getGlobalConsumer(Map<String, Object> c) {
    return getRestoreConsumer(c);
  }

  static class GateConsumer extends KafkaConsumer<byte[], byte[]> {
    final Set<TopicPartition> blocked = new HashSet<>();

    GateConsumer(Map<String, Object> p) {
      super(p, new ByteArrayDeserializer(), new ByteArrayDeserializer());
    }

    void setBlocked(TopicPartition p, boolean b) {
      if (b) {
        if (blocked.add(p) && assignment().contains(p)) super.pause(List.of(p));
      } else {
        if (blocked.remove(p) && assignment().contains(p)) super.resume(List.of(p));
      }
    }

    public void resume(Collection<TopicPartition> ps) {
      List<TopicPartition> ok = new ArrayList<>();
      for (var p : ps) if (!blocked.contains(p)) ok.add(p);
      super.resume(ok);
    }

    public void subscribe(Collection<String> topics, ConsumerRebalanceListener listener) {
      super.subscribe(topics, gated(listener));
    }

    public void subscribe(java.util.regex.Pattern pattern, ConsumerRebalanceListener listener) {
      super.subscribe(pattern, gated(listener));
    }

    // Wrap Streams' own rebalance listener rather than replace it: Streams
    // needs onPartitionsAssigned/Revoked/Lost for its own state-restoration
    // bookkeeping. The retention check runs first, on every assignment, and
    // only forwards to Streams' listener once it passes (or seeks have been
    // issued for a brand-new group).
    private ConsumerRebalanceListener gated(ConsumerRebalanceListener original) {
      return new ConsumerRebalanceListener() {
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
          if (!partitions.isEmpty()) {
            Map<TopicPartition, Long> beginning = beginningOffsets(partitions, OFFSET_LOOKUP_TIMEOUT);
            Map<TopicPartition, Long> end = endOffsets(partitions, OFFSET_LOOKUP_TIMEOUT);
            Map<TopicPartition, Long> committedOffsets = new HashMap<>();
            committed(new HashSet<>(partitions), OFFSET_LOOKUP_TIMEOUT).forEach((tp, md) -> {
              if (md != null) committedOffsets.put(tp, md.offset());
            });
            seekTargets(beginning, end, committedOffsets).forEach(GateConsumer.this::seek);
          }
          original.onPartitionsAssigned(partitions);
        }

        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
          original.onPartitionsRevoked(partitions);
        }

        public void onPartitionsLost(Collection<TopicPartition> partitions) {
          original.onPartitionsLost(partitions);
        }
      };
    }

    public ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
      CURRENT.set(this);
      // Keep remembered blocks across revocation/assignment. Processor init
      // clears healthy restored tasks; KafkaConsumer pause itself is not durable.
      var assignedBlocked = new HashSet<>(blocked);
      assignedBlocked.retainAll(assignment());
      if (!assignedBlocked.isEmpty()) super.pause(assignedBlocked);
      return super.poll(timeout);
    }
  }
}
