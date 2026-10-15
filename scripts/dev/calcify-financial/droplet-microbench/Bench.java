import java.security.MessageDigest;
import java.util.zip.CRC32C;

public class Bench {
  static volatile long sink;

  /** Best of `reps` timed runs after three warm-up runs: the fastest run is the least disturbed by other work, which is what a cross-host CPU comparison wants. */
  static double timeIt(String name, int reps, Runnable r) {
    for (int i = 0; i < 3; i++) r.run();
    long best = Long.MAX_VALUE;
    for (int i = 0; i < reps; i++) { long t = System.nanoTime(); r.run(); best = Math.min(best, System.nanoTime() - t); }
    return best;
  }

  public static void main(String[] a) throws Exception {
    final int N = 2_000_000;
    byte[] in = new byte[64]; for (int i = 0; i < in.length; i++) in[i] = (byte) i;
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    double t = timeIt("sha256-64B", 5, () -> { byte[] d = null; for (int i = 0; i < N; i++) { in[0] = (byte) i; d = md.digest(in); } sink = d[0]; });
    System.out.printf("sha256 64B     : %.0f ns/op%n", t / N);
    byte[] big = new byte[512];
    t = timeIt("sha256-512B", 5, () -> { byte[] d = null; for (int i = 0; i < N / 4; i++) { big[0] = (byte) i; d = md.digest(big); } sink = d[0]; });
    System.out.printf("sha256 512B    : %.0f ns/op%n", t / (N / 4));
    CRC32C c = new CRC32C();
    t = timeIt("crc32c", 5, () -> { long s = 0; for (int i = 0; i < N; i++) { c.reset(); c.update(big, 0, 512); s += c.getValue(); } sink = s; });
    System.out.printf("crc32c 512B    : %.0f ns/op%n", t / N);
    t = timeIt("int-loop", 5, () -> { long x = 1; for (int i = 0; i < 400_000_000; i++) { x = x * 6364136223846793005L + 1442695040888963407L; } sink = x; });
    System.out.printf("lcg mul/add    : %.2f ns/iter%n", t / 400_000_000.0);
    int M = 1 << 24; int[] next = new int[M];
    java.util.Random r = new java.util.Random(1); for (int i = 0; i < M; i++) next[i] = i;
    for (int i = M - 1; i > 0; i--) { int j = r.nextInt(i); int tmp = next[i]; next[i] = next[j]; next[j] = tmp; }
    t = timeIt("chase", 3, () -> { int p = 0; for (int i = 0; i < 10_000_000; i++) p = next[p]; sink = p; });
    System.out.printf("ptr chase 64MB : %.1f ns/hop%n", t / 10_000_000.0);
    byte[] src = new byte[256 << 20], dst = new byte[256 << 20];
    t = timeIt("copy", 3, () -> System.arraycopy(src, 0, dst, 0, src.length));
    System.out.printf("memcpy 256MB   : %.1f GB/s%n", (256.0 / 1024) / (t / 1e9));
  }
}
