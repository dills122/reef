import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.state.*;

public class CalcifyExperiment {
  static List<byte[]> fixture(String path) throws Exception {
    var rows = new ArrayList<byte[]>();
    for (String s : Files.readAllLines(Path.of(path)))
      rows.add(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return rows;
  }

  static List<Facts.Link> links(List<byte[]> rows, int g, int p) {
    var ls = new ArrayList<Facts.Link>();
    for (int i = 0; i < rows.size(); i++) {
      int n = Facts.trades(Facts.parse(rows.get(i)), g, p, i).size();
      for (int j = 0; j < n; j++) ls.add(new Facts.Link(g, p, i, j, 1));
    }
    return ls;
  }

  static byte[] rebind(byte[] raw, int partition, String source) {
    ObjectNode b = Facts.parse(raw).deepCopy();
    b.put("partition", partition);
    b.put("eventStream", source);
    ObjectNode n = b.deepCopy();
    for (String k :
        List.of(
            "createdAt",
            "workFinishedAt",
            "timingChecksum",
            "payloadChecksum",
            "payloadChecksumAlgorithm")) n.remove(k);
    var buffer = new java.io.ByteArrayOutputStream();
    Facts.canonical(buffer, n);
    b.put("payloadChecksum", Facts.sha(buffer.toByteArray()));
    return Facts.bytes(b);
  }

  static Properties properties(String broker, String app, String dir) {
    var p = new Properties();
    p.put("application.id", app);
    p.put("bootstrap.servers", broker);
    p.put("state.dir", dir);
    p.put("processing.guarantee", "exactly_once_v2");
    p.put("replication.factor", "1");
    p.put("num.standby.replicas", "1");
    p.put("commit.interval.ms", "100");
    p.put("statestore.cache.max.bytes", "8388608");
    p.put("rocksdb.config.setter", BoundedRocks.class);
    p.put("consumer.auto.offset.reset", "earliest");
    p.put("consumer.max.poll.records", "100");
    p.put("consumer.max.poll.interval.ms", "30000");
    p.put("consumer.session.timeout.ms", "6000");
    p.put("consumer.heartbeat.interval.ms", "1000");
    p.put("producer.transaction.timeout.ms", "10000");
    return p;
  }

  static long stateBytes(KeyValueStore<String, byte[]> s, String prefix) {
    long n = 0;
    try (var it = s.range(prefix, prefix + "~")) {
      while (it.hasNext()) {
        var r = it.next();
        n += r.key.getBytes().length + r.value.length;
      }
    }
    return n;
  }

  static void unit(String path) throws Exception {
    List<byte[]> rows = fixture(path);
    List<Facts.Link> ls = links(rows, 1, 0);
    Map<String, String> hashes = new TreeMap<>();
    ObjectNode report = Facts.obj();
    for (String schedule : List.of("verified-led", "source-first", "links-first")) {
      boolean two = !schedule.equals("verified-led");
      int[] reads = {0};
      Resolver.Reader reader =
          (cursor, target) -> {
            if (cursor + 1 >= rows.size()) return null;
            reads[0]++;
            return new Resolver.Entry(cursor + 1, rows.get((int) cursor + 1));
          };
      var p =
          properties(
              "dummy:9092",
              "unit-" + schedule,
              Files.createTempDirectory("calcify-unit-").toString());
      p.put("num.standby.replicas", "0");
      try (var d =
          new TopologyTestDriver(
              Resolver.topology(two, "verified", "source", "output", 1, lane -> reader, ""), p)) {
        var v = d.createInputTopic("verified", new StringSerializer(), new ByteArraySerializer());
        var s =
            two
                ? d.createInputTopic("source", new StringSerializer(), new ByteArraySerializer())
                : null;
        var o =
            d.createOutputTopic("output", new StringDeserializer(), new ByteArrayDeserializer());
        long pending = 0;
        if (schedule.equals("source-first")) {
          for (byte[] r : rows) s.pipeInput("", r);
          pending = stateBytes(d.getKeyValueStore("facts"), "B:");
          Facts.require(o.isEmpty(), "unverified output");
        }
        if (schedule.equals("links-first")) {
          for (var l : ls) v.pipeInput(l.id(), l.wire());
          pending = stateBytes(d.getKeyValueStore("facts"), "P:");
          Facts.require(o.isEmpty(), "source absent output");
          for (byte[] r : rows) s.pipeInput("", r);
        } else for (var l : ls) v.pipeInput(l.id(), l.wire());
        var got = new TreeMap<String, String>();
        while (!o.isEmpty()) {
          var r = o.readKeyValue();
          got.put(r.key, Facts.sha(r.value));
        }
        Facts.require(got.size() == 130, "output count");
        if (hashes.isEmpty()) hashes.putAll(got);
        else Facts.require(hashes.equals(got), "schedule parity");
        for (var l : ls) v.pipeInput(l.id(), l.wire());
        Facts.require(o.isEmpty(), "duplicate output");
        ObjectNode r = report.putObject(schedule);
        r.put("resolved", got.size());
        r.put("pausePendingLogicalBytes", pending);
        r.put("sourceBatchesRead", reads[0]);
        r.put("orderLogicalBytes", stateBytes(d.getKeyValueStore("facts"), "O:"));
        r.put(
            "retainedBatchLogicalBytes",
            stateBytes(d.getKeyValueStore("facts"), two ? "B:" : "batch"));
        r.put("duplicateReplayNoOutput", true);
        r.put("outputParitySha256", Facts.sha(Facts.bytes(Facts.JSON.valueToTree(got))));
        Facts.require(
            d.getKeyValueStore("facts").get(Facts.key(1, 0, "reject")) == null,
            "rejected order indexed");
      }
    }
    // Corrupt full source facts: checksum rejection, generation fence and retention failure persist
    // lane fault.
    for (String fault :
        List.of(
            "checksum",
            "generation",
            "retention",
            "missing-order",
            "future-acceptance",
            "accepted-conflict")) {
      var bad = new ArrayList<byte[]>(rows);
      if (fault.equals("checksum")) {
        ObjectNode b = Facts.parse(bad.get(0)).deepCopy();
        b.put("payloadChecksum", "bad");
        bad.set(0, Facts.bytes(b));
      }
      if (fault.equals("missing-order")) {
        ObjectNode b = Facts.parse(bad.get(0)).deepCopy();
        b.putArray("outcomes");
        b.put("commandCount", 0);
        bad.set(0, rebind(Facts.bytes(b), 0, "source"));
      }
      if (fault.equals("future-acceptance")) {
        ObjectNode batch = Facts.parse(bad.get(1)).deepCopy();
        ArrayNode outs = (ArrayNode) batch.path("outcomes");
        ObjectNode later = outs.get(1).deepCopy();
        later.put("status", "accepted");
        ObjectNode result = Facts.obj();
        JsonNode accepted =
            Facts.parse(bad.get(0)).path("outcomes").get(0).path("result").deepCopy();
        result.set("accepted", accepted.path("accepted"));
        ObjectNode fact = accepted.path("acceptedOrder").deepCopy();
        fact.put("orderId", "future-buy");
        result.set("acceptedOrder", fact);
        later.set("result", result);
        outs.set(1, later);
        ((ObjectNode) outs.get(0).path("result").path("trades").get(0))
            .put("buyOrderId", "future-buy");
        bad.set(1, rebind(Facts.bytes(batch), 0, "source"));
      }

      if (fault.equals("accepted-conflict")) {
        ObjectNode duplicate = Facts.parse(rows.get(0)).deepCopy();
        ((ObjectNode) duplicate.path("outcomes").get(0).path("result").path("acceptedOrder"))
            .put("quantityUnits", "999");
        bad.set(1, rebind(Facts.bytes(duplicate), 0, "source"));
      }
      Resolver.Reader reader =
          (c, t) -> {
            if (fault.equals("retention"))
              throw new IllegalArgumentException("source retention lost");
            return new Resolver.Entry(c + 1, bad.get((int) c + 1));
          };
      var p =
          properties(
              "dummy:9092",
              "fault-" + fault,
              Files.createTempDirectory("calcify-fault-").toString());
      p.put("num.standby.replicas", "0");
      try (var d =
          new TopologyTestDriver(
              Resolver.topology(false, "verified", "source", "output", 1, lane -> reader, ""), p)) {
        var v = d.createInputTopic("verified", new StringSerializer(), new ByteArraySerializer());
        var l = ls.get(0);
        if (fault.equals("generation")) l = new Facts.Link(2, 0, l.offset(), l.ordinal(), 1);
        v.pipeInput(l.id(), l.wire());
        Facts.require(d.getKeyValueStore("facts").get("fault") != null, "fault unblocked " + fault);
        Facts.require(
            d.createOutputTopic("output", new StringDeserializer(), new ByteArrayDeserializer())
                .isEmpty(),
            "fault output");
        report.put(
            fault,
            "lane fault: "
                + new String((byte[]) d.getKeyValueStore("facts").get("fault"))
                + "; zero output");
      }
    }
    var malformedProperties =
        properties(
            "dummy:9092",
            "malformed-link",
            Files.createTempDirectory("calcify-malformed-").toString());
    malformedProperties.put("num.standby.replicas", "0");
    try (var d =
        new TopologyTestDriver(
            Resolver.topology(
                false, "verified", "source", "output", 1, lane -> (cursor, target) -> null, ""),
            malformedProperties)) {
      var v = d.createInputTopic("verified", new StringSerializer(), new ByteArraySerializer());
      v.pipeInput("bad", new byte[] {0});
      Facts.require(d.getKeyValueStore("facts").get("fault") != null, "malformed link unblocked");
      Facts.require(
          d.createOutputTopic("output", new StringDeserializer(), new ByteArrayDeserializer())
              .isEmpty(),
          "malformed link output");
      report.put("malformed-link", "lane fault; zero output");
    }
    // Conflicting duplicate verification cannot overwrite staged pending record.
    var pp =
        properties(
            "dummy:9092",
            "pending-conflict",
            Files.createTempDirectory("calcify-pending-").toString());
    pp.put("num.standby.replicas", "0");
    try (var d =
        new TopologyTestDriver(
            Resolver.topology(
                false, "verified", "source", "output", 1, lane -> (cursor, target) -> null, ""),
            pp)) {
      var v = d.createInputTopic("verified", new StringSerializer(), new ByteArraySerializer());
      var l = ls.get(0);
      v.pipeInput(l.id(), l.wire());
      var changed = new Facts.Link(l.generation(), l.partition(), l.offset(), l.ordinal(), 2);
      v.pipeInput(changed.id(), changed.wire());
      Facts.require(d.getKeyValueStore("facts").get("fault") != null, "pending conflict escaped");
      Facts.require(
          d.createOutputTopic("output", new StringDeserializer(), new ByteArrayDeserializer())
              .isEmpty(),
          "pending conflict output");
      report.put("pending-conflicting-policy", "lane fault; zero output");
    }
    // Identical accepted fact at another source position keeps earliest provenance.
    var duplicateRows = new ArrayList<byte[]>();
    duplicateRows.add(rows.get(0));
    duplicateRows.addAll(rows);
    var dp =
        properties(
            "dummy:9092",
            "acceptance-replay",
            Files.createTempDirectory("calcify-acceptance-").toString());
    dp.put("num.standby.replicas", "0");
    try (var d =
        new TopologyTestDriver(
            Resolver.topology(
                false,
                "verified",
                "source",
                "output",
                1,
                lane ->
                    (cursor, target) ->
                        new Resolver.Entry(cursor + 1, duplicateRows.get((int) cursor + 1)),
                ""),
            dp)) {
      var v = d.createInputTopic("verified", new StringSerializer(), new ByteArraySerializer());
      for (var l : links(duplicateRows, 1, 0)) v.pipeInput(l.id(), l.wire());
      var o = d.createOutputTopic("output", new StringDeserializer(), new ByteArrayDeserializer());
      int count = 0;
      while (!o.isEmpty()) {
        var r = Facts.parse(o.readKeyValue().value);
        Facts.require(
            r.path("buyAcceptedOrder").path("source").path("offset").asLong() == 0,
            "acceptance replay changed provenance");
        count++;
      }
      Facts.require(count == 130, "acceptance replay count");
      report.put("identical-acceptance-replay", "130 contexts; earliest provenance retained");
    }
    report.put(
        "scope",
        "TopologyTestDriver with actual Go fixture/RocksDB; transactions and rebalances excluded");
    System.out.println(Facts.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
  }

  static KafkaProducer<String, byte[]> producer(String broker) {
    var p = new Properties();
    p.put("bootstrap.servers", broker);
    p.put("acks", "all");
    p.put("max.request.size", "4194304");
    return new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer());
  }

  static void seed(String broker, String prefix, String path, int partitions) throws Exception {
    var cfg = new Properties();
    cfg.put("bootstrap.servers", broker);
    try (var a = Admin.create(cfg)) {
      a.createTopics(
              List.of(
                  new NewTopic(prefix + "-source", partitions, (short) 1),
                  new NewTopic(prefix + "-verified", partitions, (short) 1),
                  new NewTopic(prefix + "-output", partitions, (short) 1)))
          .all()
          .get();
    }
    List<byte[]> rows = fixture(path);
    try (var p = producer(broker)) {
      for (int lane = 0; lane < partitions; lane++) {
        List<byte[]> rebound = new ArrayList<>();
        for (byte[] row : rows) rebound.add(rebind(row, lane, prefix + "-source"));
        List<Long> offsets = new ArrayList<>();
        for (byte[] b : rebound)
          offsets.add(p.send(new ProducerRecord<>(prefix + "-source", lane, "", b)).get().offset());
        for (var l : links(rebound, 1, lane)) {
          var at = new Facts.Link(1, lane, offsets.get((int) l.offset()), l.ordinal(), 1);
          p.send(new ProducerRecord<>(prefix + "-verified", lane, at.id(), at.wire())).get();
        }
      }
    }
  }

  static void append(String broker, String prefix, String path, String suffix, int lane)
      throws Exception {
    List<byte[]> rows = fixture(path);
    try (var p = producer(broker)) {
      List<byte[]> rebound = new ArrayList<>();
      List<Long> offsets = new ArrayList<>();
      for (byte[] raw : rows) {
        ObjectNode b = Facts.parse(raw).deepCopy();
        suffixIds(b, suffix);
        byte[] wire = rebind(Facts.bytes(b), lane, prefix + "-source");
        rebound.add(wire);
        offsets.add(
            p.send(new ProducerRecord<>(prefix + "-source", lane, "", wire)).get().offset());
      }
      for (var l : links(rebound, 1, lane)) {
        var at = new Facts.Link(1, lane, offsets.get((int) l.offset()), l.ordinal(), 1);
        p.send(new ProducerRecord<>(prefix + "-verified", lane, at.id(), at.wire())).get();
      }
    }
  }

  static void suffixIds(JsonNode n, String suffix) {
    if (n.isObject()) {
      var keys = new ArrayList<String>();
      n.fieldNames().forEachRemaining(keys::add);
      for (String k : keys) {
        if (List.of(
                    "orderId",
                    "buyOrderId",
                    "sellOrderId",
                    "commandId",
                    "eventId",
                    "engineOrderId",
                    "clientOrderId",
                    "tradeId",
                    "executionId",
                    "batchId")
                .contains(k)
            && n.get(k).isTextual()) ((ObjectNode) n).put(k, n.get(k).asText() + suffix);
        else suffixIds(n.get(k), suffix);
      }
    } else if (n.isArray()) for (JsonNode c : n) suffixIds(c, suffix);
  }

  static void worker(String[] a) throws Exception {
    String broker = a[1], app = a[2], prefix = a[3], dir = a[4], crash = a.length > 5 ? a[5] : "";
    boolean two = a.length > 6 && a[6].equals("two");
    var props = properties(broker, app, dir);
    props.put("experiment.verified.topic", prefix + "-verified");
    props.put("experiment.source.topic.id", topicId(broker, prefix + "-source"));
    var streams =
        new KafkaStreams(
            Resolver.topology(
                two,
                prefix + "-verified",
                prefix + "-source",
                prefix + "-output",
                1,
                lane -> new Resolver.BrokerReader(broker, prefix + "-source", lane),
                crash),
            props,
            new FlowControl());
    streams.setStateListener((n, o) -> System.out.println("STATE " + o + " -> " + n));
    Runtime.getRuntime().addShutdownHook(new Thread(() -> streams.close(Duration.ofSeconds(10))));
    streams.start();
    while (true) {
      Thread.sleep(5000);
      System.out.println("TASKS " + streams.metadataForLocalThreads());
      System.out.println("LAGS " + streams.allLocalStorePartitionLags());
      try {
        var state =
            streams.store(
                StoreQueryParameters.fromNameAndType("facts", QueryableStoreTypes.keyValueStore()));
        try (var it = state.all()) {
          long pending = 0, orders = 0;
          String fault = "";
          while (it.hasNext()) {
            var kv = it.next();
            String k = (String) kv.key;
            if (k.startsWith("P:")) pending++;
            if (k.startsWith("O:")) orders++;
            if (k.equals("fault")) fault = new String((byte[]) kv.value);
          }
          System.out.println("LOCAL pending=" + pending + " orders=" + orders + " fault=" + fault);
        }
      } catch (Exception ignored) {
      }
    }
  }

  static Map<String, String> expected(List<byte[]> rows, int lane, String source, long base) {
    Map<String, JsonNode> index = new HashMap<>();
    Map<String, String> result = new TreeMap<>();
    for (int i = 0; i < rows.size(); i++) {
      JsonNode b = Facts.parse(rebind(rows.get(i), lane, source));
      int j = 0;
      for (JsonNode out : b.path("outcomes")) {
        if (out.path("commandType").asText().equals("SubmitOrder")
            && out.path("status").asText().equals("accepted")
            && out.path("result").has("accepted")) {
          var r = Facts.row(b, 1, lane, base + i, j);
          index.put(Facts.text(r.path("fact"), "orderId"), r);
        }
        j++;
      }
      var ts = Facts.trades(b, 1, lane, base + i);
      for (int k = 0; k < ts.size(); k++) {
        var t = ts.get(k);
        var tf = t.path("fact");
        var l = new Facts.Link(1, lane, base + i, k, 1);
        result.put(
            l.id(),
            Facts.sha(
                Facts.resolve(
                    l,
                    t,
                    index.get(Facts.text(tf, "buyOrderId")),
                    index.get(Facts.text(tf, "sellOrderId")))));
      }
    }
    return result;
  }

  static void inspect(String broker, String prefix, String path, int partitions) throws Exception {
    var p = new Properties();
    p.put("bootstrap.servers", broker);
    p.put("enable.auto.commit", "false");
    p.put("isolation.level", "read_committed");
    p.put("auto.offset.reset", "earliest");
    try (var c = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
      List<TopicPartition> tps = new ArrayList<>();
      for (int i = 0; i < partitions; i++) tps.add(new TopicPartition(prefix + "-output", i));
      c.assign(tps);
      c.seekToBeginning(tps);
      Map<TopicPartition, Long> end = c.endOffsets(tps);
      Map<String, String> hashes = new TreeMap<>();
      int count = 0, dup = 0;
      long bytes = 0;
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < deadline) {
        for (var r : c.poll(Duration.ofMillis(100))) {
          count++;
          bytes += r.value().length;
          String prev = hashes.put(r.key(), Facts.sha(r.value()));
          if (prev != null) dup++;
          Facts.require(
              r.partition() == Facts.Link.read(Resolver.storeLink(r.key())).partition(),
              "output partition mismatch");
        }
        boolean done = true;
        for (var tp : tps) if (c.position(tp) < end.get(tp)) done = false;
        if (done) break;
      }
      var report = Facts.obj();
      report.put("records", count);
      report.put("unique", hashes.size());
      report.put("duplicates", dup);
      report.put("outputBytes", bytes);
      report.put("outputHash", Facts.sha(Facts.bytes(Facts.JSON.valueToTree(hashes))));
      Map<String, String> expected = new TreeMap<>();
      for (int lane = 0; lane < partitions; lane++)
        expected.putAll(expected(fixture(path), lane, prefix + "-source", 0));
      boolean parity = hashes.entrySet().containsAll(expected.entrySet());
      if (count == 130 * (partitions + 1)) {
        List<byte[]> wave = new ArrayList<>();
        for (byte[] raw : fixture(path)) {
          JsonNode n = Facts.parse(raw);
          suffixIds(n, "-wave2");
          wave.add(Facts.bytes(n));
        }
        expected.putAll(expected(wave, partitions - 1, prefix + "-source", 5));
        parity = hashes.equals(expected);
      }
      report.put("fullFactParity", parity);
      System.out.println(report);
    }
  }

  static String topicId(String broker, String topic) throws Exception {
    var c = new Properties();
    c.put("bootstrap.servers", broker);
    try (var a = Admin.create(c)) {
      return a.describeTopics(List.of(topic)).allTopicNames().get().get(topic).topicId().toString();
    }
  }

  static void availability(String broker, String prefix, String action) throws Exception {
    var c = new Properties();
    c.put("bootstrap.servers", broker);
    try (var a = Admin.create(c)) {
      if (action.equals("truncate")) {
        var r =
            a.deleteRecords(
                    Map.of(
                        new TopicPartition(prefix + "-source", 0), RecordsToDelete.beforeOffset(1)))
                .all()
                .get();
        System.out.println(r);
      } else if (action.equals("recreate")) {
        String before = topicId(broker, prefix + "-source");
        a.deleteTopics(List.of(prefix + "-source")).all().get();
        a.createTopics(List.of(new NewTopic(prefix + "-source", 1, (short) 1))).all().get();
        String after = topicId(broker, prefix + "-source");
        Facts.require(!before.equals(after), "topic identity unchanged");
        System.out.println("oldTopicId=" + before + " newTopicId=" + after);
      }
    }
  }

  static void scan(String broker, String topic) throws Exception {
    topicId(broker, topic);
    var p = new Properties();
    p.put("bootstrap.servers", broker);
    p.put("enable.auto.commit", "false");
    p.put("isolation.level", "read_committed");
    p.put("allow.auto.create.topics", "false");
    try (var c = new KafkaConsumer<>(p, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
      var tp = new TopicPartition(topic, 0);
      c.assign(List.of(tp));
      c.seekToBeginning(List.of(tp));
      long end = c.endOffsets(List.of(tp)).get(tp), records = 0, bytes = 0, tombs = 0;
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (c.position(tp) < end && System.nanoTime() < deadline) {
        for (var r : c.poll(Duration.ofMillis(100))) {
          records++;
          bytes +=
              (r.key() == null ? 0 : r.key().length) + (r.value() == null ? 0 : r.value().length);
          if (r.value() == null) tombs++;
        }
      }
      System.out.println(
          "{\"topic\":\""
              + topic
              + "\",\"records\":"
              + records
              + ",\"logicalKeyValueBytes\":"
              + bytes
              + ",\"tombstones\":"
              + tombs
              + ",\"endOffset\":"
              + end
              + "}");
    }
  }

  static void poison(String broker, String prefix) throws Exception {
    try (var p = producer(broker)) {
      for (int i = 0; i < 1000; i++) {
        var l = new Facts.Link(2, 0, 4, i, 1);
        p.send(new ProducerRecord<>(prefix + "-verified", 0, l.id(), l.wire()));
      }
      p.flush();
    }
  }

  static void fence(String broker) throws Exception {
    var cfg = new Properties();
    cfg.put("bootstrap.servers", broker);
    cfg.put("transactional.id", "calcify-p2-fence");
    try (var one = new KafkaProducer<>(cfg, new StringSerializer(), new ByteArraySerializer());
        var two = new KafkaProducer<>(cfg, new StringSerializer(), new ByteArraySerializer())) {
      var adminProps = new Properties();
      adminProps.put("bootstrap.servers", broker);
      String topic = "p2-fence-" + UUID.randomUUID();
      try (var admin = Admin.create(adminProps)) {
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
      }
      one.initTransactions();
      one.beginTransaction();
      one.send(new ProducerRecord<>(topic, "fence", new byte[] {1})).get();
      two.initTransactions();
      try {
        one.commitTransaction();
        throw new AssertionError("stale producer committed");
      } catch (org.apache.kafka.common.errors.ProducerFencedException
          | org.apache.kafka.common.errors.InvalidProducerEpochException expected) {
        System.out.println("{\"stalePublisherFenced\":true}");
      }
    }
  }

  public static void main(String[] a) throws Exception {
    switch (a[0]) {
      case "unit" -> unit(a[1]);
      case "seed" -> seed(a[1], a[2], a[3], Integer.parseInt(a[4]));
      case "worker" -> worker(a);
      case "append" -> append(a[1], a[2], a[3], a[4], a.length > 5 ? Integer.parseInt(a[5]) : 0);
      case "inspect" -> inspect(a[1], a[2], a[3], Integer.parseInt(a[4]));
      case "fence" -> fence(a[1]);
      case "poison" -> poison(a[1], a[2]);
      case "availability" -> availability(a[1], a[2], a[3]);
      case "scan" -> scan(a[1], a[2]);
      default -> throw new IllegalArgumentException("unknown command");
    }
  }
}
