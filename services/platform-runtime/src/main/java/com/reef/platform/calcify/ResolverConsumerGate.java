package com.reef.platform.calcify;

import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.KafkaClientSupplier;

/** Partition flow control only. Kafka Streams owns transactions and restoration. */
public final class ResolverConsumerGate implements KafkaClientSupplier {
  static final ThreadLocal<GateConsumer> CURRENT = new ThreadLocal<>();

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
