import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Par {
  static final java.util.concurrent.atomic.AtomicLong sink = new java.util.concurrent.atomic.AtomicLong();
  static long work(long seed, int iters) {
    HashMap<String, Integer> m = new HashMap<>();
    StringBuilder sb = new StringBuilder();
    long acc = seed;
    for (int i = 0; i < iters; i++) {
      sb.setLength(0);
      sb.append("acct-").append((acc + i) & 1023).append("/inst-").append(i & 63);
      String k = sb.toString();
      byte[] b = k.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      m.merge(k, b.length, Integer::sum);
      acc += m.get(k) + b[b.length - 1];
    }
    return acc;
  }
  public static void main(String[] a) throws Exception {
    int[] ns = {1, 2, 4, 6, 8, 10, 12, 16};
    work(1, 3_000_000);
    for (int n : ns) {
      ExecutorService ex = Executors.newFixedThreadPool(n);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Double>> fs = new ArrayList<>();
      for (int t = 0; t < n; t++) { final int s = t; fs.add(ex.submit(() -> { go.await(); long t0 = System.nanoTime(); sink.addAndGet(work(s, 6_000_000)); return 6_000_000 / ((System.nanoTime() - t0) / 1e9); })); }
      go.countDown();
      double sum = 0; for (Future<Double> f : fs) sum += f.get();
      ex.shutdown();
      System.out.printf("threads=%2d  per-thread %.2f M ops/s   total %.1f M ops/s%n", n, sum / n / 1e6, sum / 1e6);
    }
  }
}
