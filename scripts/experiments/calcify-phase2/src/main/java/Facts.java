import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Experiment codec: complete JSON facts, not proposed production wire contract. */
final class Facts {
  static final ObjectMapper JSON = new ObjectMapper();

  static JsonNode parse(byte[] b) {
    try {
      return JSON.readTree(b);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  static byte[] bytes(JsonNode n) {
    try {
      return JSON.writeValueAsBytes(n);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  static ObjectNode obj() {
    return JSON.createObjectNode();
  }

  static void require(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(message);
  }

  static String text(JsonNode n, String k) {
    JsonNode v = n.get(k);
    require(v != null && v.isTextual() && !v.asText().isBlank(), "missing " + k);
    return v.asText();
  }

  static String sha(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static void token(java.io.ByteArrayOutputStream b, char c, String s) {
    byte[] x = s.getBytes(StandardCharsets.UTF_8);
    b.write(c);
    b.writeBytes((x.length + ":").getBytes(StandardCharsets.UTF_8));
    b.writeBytes(x);
  }

  static void canonical(java.io.ByteArrayOutputStream b, JsonNode n) {
    if (n.isNull()) {
      token(b, 'n', "");
    } else if (n.isTextual()) {
      token(b, 's', n.asText());
    } else if (n.isNumber()) {
      token(b, 'd', n.asText());
    } else if (n.isBoolean()) {
      token(b, 'b', n.asBoolean() ? "1" : "0");
    } else if (n.isArray()) {
      token(b, 'a', "" + n.size());
      n.forEach(v -> canonical(b, v));
    } else {
      token(b, 'o', "" + n.size());
      List<String> keys = new ArrayList<>();
      n.fieldNames().forEachRemaining(keys::add);
      Collections.sort(keys);
      for (String k : keys) {
        token(b, 's', k);
        canonical(b, n.get(k));
      }
    }
  }

  static void validate(JsonNode batch, int partition) {
    require(batch.path("partition").asInt(-1) == partition, "partition mismatch");
    require(
        text(batch, "payloadChecksumAlgorithm").equals("sha256-reef-canonical-v1"),
        "checksum algorithm");
    ObjectNode n = batch.deepCopy();
    for (String k :
        List.of(
            "createdAt",
            "workFinishedAt",
            "timingChecksum",
            "payloadChecksum",
            "payloadChecksumAlgorithm")) n.remove(k);
    var b = new java.io.ByteArrayOutputStream();
    canonical(b, n);
    require(sha(b.toByteArray()).equals(text(batch, "payloadChecksum")), "checksum mismatch");
    require(
        batch.path("commandCount").asInt(-1) == batch.path("outcomes").size(),
        "commandCount mismatch");
  }

  record Link(int generation, int partition, long offset, int ordinal, int policy) {
    Link {
      require(
          generation > 0
              && partition >= 0
              && offset >= 0
              && ordinal >= 0
              && policy > 0
              && policy <= 65535,
          "invalid verified link fields");
    }

    String id() {
      return generation + ":" + partition + ":" + offset + ":" + ordinal;
    }

    byte[] wire() {
      return ByteBuffer.allocate(23)
          .put((byte) 1)
          .putInt(generation)
          .putInt(partition)
          .putLong(offset)
          .putInt(ordinal)
          .putShort((short) policy)
          .array();
    }

    static Link read(byte[] b) {
      require(b.length == 23, "link length");
      var v = ByteBuffer.wrap(b);
      require(v.get() == 1, "link version");
      return new Link(v.getInt(), v.getInt(), v.getLong(), v.getInt(), v.getShort() & 65535);
    }
  }

  static ObjectNode provenance(
      JsonNode batch, int generation, int partition, long offset, int outcome) {
    var n = obj();
    n.put("generation", generation);
    n.put("partition", partition);
    n.put("offset", offset);
    n.put("outcomeOrdinal", outcome);
    n.put("batchId", text(batch, "batchId"));
    n.put("batchChecksum", text(batch, "payloadChecksum"));
    n.put("commandId", text(batch.path("outcomes").get(outcome), "commandId"));
    return n;
  }

  static ObjectNode row(JsonNode batch, int g, int p, long off, int i) {
    JsonNode o = batch.path("outcomes").get(i);
    var n = obj();
    n.set("fact", o.path("result").get("acceptedOrder"));
    n.put("acceptanceEventId", text(o.path("result").get("accepted"), "eventId"));
    n.set("source", provenance(batch, g, p, off, i));
    return n;
  }

  static ArrayNode trades(JsonNode batch, int g, int p, long off) {
    ArrayNode all = JSON.createArrayNode();
    int i = 0;
    for (JsonNode o : batch.path("outcomes")) {
      for (JsonNode t : o.path("result").path("trades")) {
        var n = obj();
        n.set("fact", t);
        n.set("source", provenance(batch, g, p, off, i));
        all.add(n);
      }
      i++;
    }
    return all;
  }

  // Bootstrap with lane-local order ID; run is absent from TradeCreated and ModifyOrder outcome.
  // Scope key includes source generation and partition (task-local store already supplies
  // partition).
  static String key(int g, int p, String id) {
    return "O:" + g + ":" + p + ":" + id.length() + ":" + id;
  }

  static byte[] resolve(Link l, JsonNode trade, JsonNode buy, JsonNode sell) {
    require(buy != null && sell != null, "missing accepted order");
    var b = buy.path("fact");
    var s = sell.path("fact");
    var t = trade.path("fact");
    require(
        trade.path("source").path("offset").asLong() == l.offset
            && trade.path("source").path("partition").asInt() == l.partition
            && trade.path("source").path("generation").asInt() == l.generation,
        "target provenance mismatch");
    require(text(b, "side").equals("BUY") && text(s, "side").equals("SELL"), "side mismatch");
    for (String k : List.of("runId", "venueSessionId", "instrumentId"))
      require(text(b, k).equals(text(s, k)), "scope mismatch " + k);
    require(text(b, "instrumentId").equals(text(t, "instrumentId")), "trade instrument mismatch");
    require(
        text(b, "orderId").equals(text(t, "buyOrderId"))
            && text(s, "orderId").equals(text(t, "sellOrderId")),
        "order reference mismatch");
    for (JsonNode r : List.of(buy, sell)) {
      var src = r.path("source");
      require(
          src.path("generation").asInt() == l.generation
              && src.path("partition").asInt() == l.partition,
          "source generation/lane");
      long off = src.path("offset").asLong();
      require(
          off < l.offset
              || (off == l.offset
                  && src.path("outcomeOrdinal").asInt()
                      <= trade.path("source").path("outcomeOrdinal").asInt()),
          "acceptance follows trade");
    }
    var n = obj();
    n.put("type", "MatchContextResolvedV1");
    n.put("commitmentId", l.id());
    n.put("policyVersion", l.policy);
    n.put("runId", text(b, "runId"));
    n.set("trade", trade);
    n.set("buyAcceptedOrder", buy);
    n.set("sellAcceptedOrder", sell);
    return bytes(n);
  }
}
