import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

/** Local store+codec microbenchmark; excludes source parsing, Kafka, transactions and standby. */
public final class Capacity {
  static byte[] key(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  static long size(Path p) throws Exception {
    try (var fs = Files.walk(p)) {
      return fs.filter(Files::isRegularFile)
          .mapToLong(
              f -> {
                try {
                  return Files.size(f);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              })
          .sum();
    }
  }

  public static void main(String[] args) throws Exception {
    RocksDB.loadLibrary();
    int aged = Integer.parseInt(args[1]),
        seconds = Integer.parseInt(args[2]),
        rate = Integer.parseInt(args[3]),
        lanes = Integer.parseInt(args[4]);
    Path dir = Path.of(args[5]);
    Files.createDirectories(dir);
    var rows = CalcifyExperiment.fixture(args[0]);
    JsonNode b = Facts.parse(rows.get(1));
    ObjectNode buy = Facts.row(Facts.parse(rows.get(0)), 1, 0, 0, 0),
        sell = Facts.row(b, 1, 0, 1, 0);
    JsonNode trade = Facts.trades(b, 1, 0, 1).get(0);
    long rowBytes = Facts.bytes(buy).length,
        v1Bytes = Facts.resolve(new Facts.Link(1, 0, 1, 0, 1), trade, buy, sell).length;
    List<RocksDB> dbs = new ArrayList<>();
    List<Options> opts = new ArrayList<>();
    List<Cache> caches = new ArrayList<>();
    try (var writes = new WriteOptions().setDisableWAL(true);
        var flush = new FlushOptions().setWaitForFlush(true)) {
      for (int lane = 0; lane < lanes; lane++) {
        var cache = new LRUCache(32L * 1024 * 1024);
        caches.add(cache);
        var o =
            new Options()
                .setCreateIfMissing(true)
                .setTableFormatConfig(new BlockBasedTableConfig().setBlockCache(cache))
                .setWriteBufferSize(16L * 1024 * 1024)
                .setMaxWriteBufferNumber(2);
        opts.add(o);
        dbs.add(RocksDB.open(o, dir.resolve("lane-" + lane).toString()));
      }
      long seed = System.nanoTime();
      for (int i = 0; i < aged; i++) {
        ObjectNode r = buy.deepCopy();
        ((ObjectNode) r.path("fact")).put("orderId", "aged-" + i);
        dbs.get(i % lanes).put(writes, key("aged-" + i), Facts.bytes(r));
      }
      for (var db : dbs) db.flush(flush);
      double seedSeconds = (System.nanoTime() - seed) / 1e9;
      long initialDisk = size(dir);
      long start = System.nanoTime(),
          deadline = start + (long) seconds * 1000000000L,
          count = 0,
          outBytes = 0,
          insertBytes = 0,
          nextSample = start + 5000000000L,
          lastCount = 0,
          maxLag = 0;
      List<Long> latency = new ArrayList<>();
      ArrayNode samples = Facts.JSON.createArrayNode();
      while (System.nanoTime() < deadline) {
        long i = count;
        var db = dbs.get((int) (i % lanes));
        String bid = "buy-" + i, sid = "sell-" + i;
        ObjectNode br = buy.deepCopy(), sr = sell.deepCopy(), tr = trade.deepCopy();
        ((ObjectNode) br.path("fact")).put("orderId", bid);
        ((ObjectNode) sr.path("fact")).put("orderId", sid);
        ((ObjectNode) tr.path("fact")).put("buyOrderId", bid).put("sellOrderId", sid);
        long t = System.nanoTime();
        byte[] bv = Facts.bytes(br), sv = Facts.bytes(sr);
        db.put(writes, key(bid), bv);
        db.put(writes, key(sid), sv);
        byte[] out =
            Facts.resolve(
                new Facts.Link(1, 0, 1, 0, 1),
                tr,
                Facts.parse(db.get(key(bid))),
                Facts.parse(db.get(key(sid))));
        Facts.require(
            Facts.text(Facts.parse(out).path("buyAcceptedOrder").path("fact"), "orderId")
                .equals(bid),
            "lookup parity");
        if (count % 100 == 0) {
          latency.add(System.nanoTime() - t);
          db.get(key("aged-" + ((count * 7919) % aged)));
        }
        count++;
        outBytes += out.length;
        insertBytes += bv.length + sv.length;
        long now = System.nanoTime();
        if (rate > 0) {
          maxLag = Math.max(maxLag, (now - start) * rate / 1000000000L - count);
          long sleep = start + count * 1000000000L / rate - System.nanoTime();
          if (sleep > 0) java.util.concurrent.locks.LockSupport.parkNanos(sleep);
        }
        if (now >= nextSample) {
          var r = samples.addObject();
          r.put("seconds", (now - start) / 1e9);
          r.put("resolved", count);
          r.put("rateLast5s", (count - lastCount) / 5.0);
          r.put(
              "offeredMinusResolved",
              rate > 0 ? Math.max(0, (now - start) * rate / 1000000000L - count) : 0);
          lastCount = count;
          nextSample += 5000000000L;
          System.err.println(r);
        }
      }
      double elapsed = (System.nanoTime() - start) / 1e9;
      for (var db : dbs) db.flush(flush);
      long disk = size(dir);
      Collections.sort(latency);
      var r = Facts.obj();
      r.put(
          "scope",
          "single Java thread RocksDB full-fact inserts/two gets/JSON V1/aged sparse lookup; no"
              + " source parsing/Kafka/changelog/standby/intake");
      r.put("agedRows", aged);
      r.put("lanesSequential", lanes);
      r.put("cacheMiBPerLane", 32);
      r.put("memtableMiBPerLane", 16);
      r.put("WAL", false);
      r.put("seedSeconds", seedSeconds);
      r.put("initialDiskBytes", initialDisk);
      r.put("requestedSeconds", seconds);
      r.put("requestedTradesPerSecond", rate);
      r.put("resolvedTrades", count);
      r.put("elapsedSeconds", elapsed);
      r.put("resolvedPerSecond", count / elapsed);
      r.put("impliedNewAcceptedOrdersPerSecond", 2 * count / elapsed);
      r.put("fullExampleRowJsonBytes", rowBytes);
      r.put("fullExampleV1JsonBytes", v1Bytes);
      r.put("insertLogicalBytes", insertBytes);
      r.put("outputLogicalBytes", outBytes);
      r.put("finalDiskBytes", disk);
      r.put("physicalGrowthPerNewOrderBytes", (disk - initialDisk) / (2.0 * count));
      r.put("maxOfferedMinusResolvedTrades", maxLag);
      r.put("sampledLocalServiceP95Micros", latency.get((int) (latency.size() * .95)) / 1000.0);
      r.set("samples", samples);
      System.out.println(Facts.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(r));
    } finally {
      for (var db : dbs) db.close();
      for (var o : opts) o.close();
      for (var c : caches) c.close();
    }
  }
}
