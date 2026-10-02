import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.*;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.*;

/** Isolated prototype: no runtime wiring, no settlement. */
final class Resolver implements Processor<String, byte[], String, byte[]> {
  interface Reader extends AutoCloseable {
    Entry next(long cursor, long target);

    default void close() {}
  }

  record Entry(long offset, byte[] payload) {}

  static class BrokerReader implements Reader {
    final KafkaConsumer<String, byte[]> consumer;
    final TopicPartition tp;
    final Deque<Entry> queue = new ArrayDeque<>();
    boolean initialized;

    BrokerReader(String broker, String topic, int partition) {
      var p = new Properties();
      p.put("bootstrap.servers", broker);
      p.put("enable.auto.commit", "false");
      p.put("isolation.level", "read_committed");
      p.put("auto.offset.reset", "none");
      p.put("max.poll.records", "64");
      consumer = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer());
      tp = new TopicPartition(topic, partition);
      consumer.assign(List.of(tp));
    }

    public Entry next(long cursor, long target) {
      long next = cursor + 1;
      if (!initialized) {
        Facts.require(
            consumer.beginningOffsets(List.of(tp)).get(tp) <= next, "source retention lost");
        consumer.seek(tp, next);
        System.err.println("SOURCE_SEEK partition=" + tp.partition() + " next=" + next);
        initialized = true;
      }
      if (queue.isEmpty()) {
        Facts.require(
            consumer.beginningOffsets(List.of(tp)).get(tp) <= next, "source retention lost");
        for (var r : consumer.poll(Duration.ofMillis(10)))
          queue.add(new Entry(r.offset(), r.value()));
      }
      Entry e = queue.peek();
      if (e == null) return null;
      Facts.require(e.offset <= target, "target source offset absent");
      return queue.remove();
    }

    public void close() {
      consumer.close(Duration.ofSeconds(2));
    }
  }

  final boolean twoInput;
  final int generation;
  final String sourceTopic;
  String verifiedTopic;
  final java.util.function.IntFunction<Reader> factory;
  final String crashAt;
  ProcessorContext<String, byte[]> context;
  KeyValueStore<String, byte[]> store;
  Reader reader;
  int partition;
  long decoded, gets, outputBytes, pendingPeak;
  long cachedOffset = -1;
  JsonNode cachedTrades;

  Resolver(
      boolean twoInput,
      int generation,
      String sourceTopic,
      java.util.function.IntFunction<Reader> factory,
      String crashAt) {
    this.twoInput = twoInput;
    this.generation = generation;
    this.sourceTopic = sourceTopic;
    this.factory = factory;
    this.crashAt = crashAt;
  }

  public void init(ProcessorContext<String, byte[]> c) {
    context = c;
    store = c.getStateStore("facts");
    partition = c.taskId().partition();
    verifiedTopic = (String) c.appConfigs().getOrDefault("experiment.verified.topic", "verified");
    if (!twoInput) reader = factory.apply(partition);
    c.schedule(Duration.ofMillis(10), PunctuationType.WALL_CLOCK_TIME, t -> drain());
  }

  long cursor() {
    byte[] c = store.get("cursor");
    return c == null ? -1 : java.nio.ByteBuffer.wrap(c).getLong();
  }

  void crash(String point) {
    if (point.equals(crashAt)) Runtime.getRuntime().halt(91);
  }

  public void process(Record<String, byte[]> r) {
    if (twoInput && context.recordMetadata().orElseThrow().topic().equals(sourceTopic)) {
      ingest(context.recordMetadata().orElseThrow().offset(), r.value());
    } else {
      Facts.Link l;
      try {
        l = Facts.Link.read(r.value());
      } catch (IllegalArgumentException error) {
        store.put("fault", error.getMessage().getBytes());
        store.put("faultRecord", r.value());
        FlowControl.blocked(verifiedTopic, partition, true);
        return;
      }
      if (l.generation() != generation || l.partition() != partition) {
        enqueue(l, r.value());
        store.put("fault", ("generation/lane mismatch " + l.id()).getBytes());
        return;
      }
      String done = "D:" + l.id();
      byte[] old = store.get(done);
      if (old != null) {
        if (!Arrays.equals(old, r.value())) {
          store.put("fault", "conflicting duplicate verification".getBytes());
          FlowControl.blocked(verifiedTopic, partition, true);
        }
        return;
      }
      enqueue(l, r.value());
    }
    drain();
  }

  void enqueue(Facts.Link link, byte[] wire) {
    byte[] old = store.get(pendingKey(link));
    if (old != null && !Arrays.equals(old, wire)) {
      store.put("fault", "conflicting pending verification".getBytes());
      FlowControl.blocked(verifiedTopic, partition, true);
      return;
    }
    Facts.require(old != null || pendingCount() < 200, "bounded verified pending overflow");
    store.put(pendingKey(link), wire);
  }

  long pendingCount() {
    long n = 0;
    try (var it = store.range("P:", "P;")) {
      while (it.hasNext()) {
        it.next();
        n++;
      }
    }
    return n;
  }

  static String pendingKey(Facts.Link l) {
    return String.format(java.util.Locale.ROOT, "P:%020d:%010d", l.offset(), l.ordinal());
  }

  void ingest(long off, byte[] payload) {
    JsonNode b = Facts.parse(payload);
    Facts.validate(b, partition);
    int i = 0;
    for (JsonNode o : b.path("outcomes")) {
      if (Facts.text(o, "commandType").equals("SubmitOrder")
          && o.path("status").asText().equals("accepted")
          && o.path("result").has("accepted")) {
        JsonNode row = Facts.row(b, generation, partition, off, i);
        String k = Facts.key(generation, partition, Facts.text(row.path("fact"), "orderId"));
        byte[] value = Facts.bytes(row), old = store.get(k);
        if (old != null) {
          JsonNode prior = Facts.parse(old);
          Facts.require(
              prior.path("fact").equals(row.path("fact"))
                  && prior.path("acceptanceEventId").equals(row.path("acceptanceEventId"))
                  && prior
                      .path("source")
                      .path("commandId")
                      .equals(row.path("source").path("commandId")),
              "accepted fact conflict");
          // Same immutable acceptance replay: preserve earliest exact source provenance.
        } else {
          store.put(k, value);
        }
      }
      i++;
    }
    cachedTrades = Facts.trades(b, generation, partition, off);
    cachedOffset = off;
    byte[] ts = Facts.bytes(cachedTrades);
    if (twoInput) store.put("B:" + off, ts);
    else store.put("batch", ts);
    store.put("cursor", java.nio.ByteBuffer.allocate(8).putLong(off).array());
    decoded++;
    System.err.println("SOURCE_DECODE partition=" + partition + " offset=" + off);
    crash("state");
  }

  boolean identityChecked;

  void checkIdentity() {
    if (identityChecked) return;
    String current =
        (String) context.appConfigs().getOrDefault("experiment.source.topic.id", "fixture");
    byte[] old = store.get("sourceTopicId");
    Facts.require(old == null || new String(old).equals(current), "source topic identity changed");
    if (old == null) store.put("sourceTopicId", current.getBytes());
    identityChecked = true;
  }

  void drain() {
    try {
      checkIdentity();
    } catch (IllegalArgumentException e) {
      store.put("fault", e.getMessage().getBytes());
    }
    if (store.get("fault") != null) {
      FlowControl.blocked(verifiedTopic, partition, true);
      return;
    }
    while (true) {
      String pk;
      Facts.Link l;
      try (var it = store.range("P:", "P;")) {
        if (!it.hasNext()) {
          FlowControl.blocked(verifiedTopic, partition, false);
          return;
        }
        var kv = it.next();
        pk = kv.key;
        l = Facts.Link.read(kv.value);
      }
      try {
        if (!twoInput) {
          while (cursor() < l.offset()) {
            Entry e = reader.next(cursor(), l.offset());
            if (e == null) {
              FlowControl.blocked(verifiedTopic, partition, true);
              return;
            }
            ingest(e.offset, e.payload);
          }
        } else if (cursor() < l.offset()) return;
        byte[] batch = store.get(twoInput ? "B:" + l.offset() : "batch");
        Facts.require(batch != null, "missing target batch");
        if (cachedOffset != l.offset() || cachedTrades == null) {
          cachedTrades = Facts.parse(batch);
          cachedOffset = l.offset();
        }
        JsonNode trades = cachedTrades;
        Facts.require(l.ordinal() < trades.size(), "trade ordinal absent");
        JsonNode t = trades.get(l.ordinal()), tf = t.path("fact");
        JsonNode b = get(Facts.text(tf, "buyOrderId")), s = get(Facts.text(tf, "sellOrderId"));
        byte[] out = Facts.resolve(l, t, b, s);
        context.forward(new Record<>(l.id(), out, 0));
        outputBytes += out.length;
        crash("forward");
        store.put("D:" + l.id(), l.wire());
        store.delete(pk);
        context.commit();
      } catch (IllegalArgumentException e) {
        store.put("fault", e.getMessage().getBytes());
        FlowControl.blocked(verifiedTopic, partition, true);
        return;
      }
    }
  }

  JsonNode get(String id) {
    gets++;
    byte[] row = store.get(Facts.key(generation, partition, id));
    return row == null ? null : Facts.parse(row);
  }

  public void close() {
    if (reader != null) reader.close();
  }

  static Topology topology(
      boolean two,
      String verified,
      String source,
      String output,
      int g,
      java.util.function.IntFunction<Reader> factory,
      String crash) {
    var t = new Topology();
    t.addSource("verified", new StringDeserializer(), new ByteArrayDeserializer(), verified);
    if (two) t.addSource("source", new StringDeserializer(), new ByteArrayDeserializer(), source);
    t.addProcessor(
        "resolve",
        () -> new Resolver(two, g, source, factory, crash),
        two ? new String[] {"verified", "source"} : new String[] {"verified"});
    t.addStateStore(
        Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("facts"), Serdes.String(), Serdes.ByteArray())
            .withCachingDisabled(),
        "resolve");
    t.addSink(
        "output",
        output,
        new StringSerializer(),
        new ByteArraySerializer(),
        (topic, key, value, n) -> Optional.of(Set.of(Facts.Link.read(storeLink(key)).partition())),
        "resolve");
    return t;
  }

  static byte[] storeLink(String id) {
    String[] a = id.split(":");
    return new Facts.Link(
            Integer.parseInt(a[0]),
            Integer.parseInt(a[1]),
            Long.parseLong(a[2]),
            Integer.parseInt(a[3]),
            1)
        .wire();
  }
}
