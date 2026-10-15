package com.reef.platform.calcify.finance

import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.util.Random
import javax.sql.DataSource
import kotlin.test.Test
import org.apache.kafka.streams.processor.api.MockProcessorContext
import org.apache.kafka.streams.processor.api.Record
import reef.contracts.calcify.v1.MatchContextResolvedV1

/**
 * Contention-immune cost of one capacity lane decision, without a broker: the processing thread's own CPU time and allocated bytes per decision, measured with
 * [ThreadMXBean] on the real [CapacityProcessor] fed by the real [CapacityResolvedContextDecoder] (a resolved match context per trade, as on the product path), then
 * the projector's in-JVM work per decision (record decode, input digest, chain link, row arrays) against a no-op JDBC stub, so SQL and pgjdbc are excluded.
 *
 * Wall-clock trades/s is noisy when other work shares the machine; CPU time of one thread and allocated bytes are not. Research only (-PresearchTests); env:
 * CAP_TRADES (per segment), CAP_SEGMENTS, CAP_ACCOUNTS, CAP_INSTRUMENTS. Each segment prints one `REENC` line.
 */
@org.junit.jupiter.api.Tag("research")
class CapacityReencodingCostTest {
    private fun env(name: String, default: Long) = System.getenv(name)?.toLong() ?: default
    private val usd = AssetId("USD"); private val opening = AccountId("opening")
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private class Measured(val cpuNs: Long, val allocBytes: Long)
    private inline fun measure(block: () -> Unit): Measured {
        val id = Thread.currentThread().id
        val c0 = threads.currentThreadCpuTime; val a0 = threads.getThreadAllocatedBytes(id)
        block()
        return Measured(threads.currentThreadCpuTime - c0, threads.getThreadAllocatedBytes(id) - a0)
    }

    /** A JDBC stub that answers the projector's fence with a settable checkpoint and swallows every write, so only the projector's own JVM work remains. */
    private class StubDb {
        @Volatile var sequence = 0L; @Volatile var chain = ""
        private fun <T> proxy(type: Class<T>, handler: (String, Array<Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, a -> handler(m.name, a ?: emptyArray()) })
        private fun rs(): java.sql.ResultSet { var served = false
            return proxy(java.sql.ResultSet::class.java) { n, a -> when (n) {
                "next" -> (!served).also { served = true }; "getLong" -> if ((a[0] as Int) == 1) 1L else sequence; "getString" -> chain; "close" -> null; else -> null } } }
        private fun statement(): java.sql.PreparedStatement { var firstArray = 1
            return proxy(java.sql.PreparedStatement::class.java) { n, a -> when (n) {
                "setArray" -> { if ((a[0] as Int) == 2 || (a[0] as Int) == 1) firstArray = (a[1] as java.sql.Array).let { arr -> (arr.array as Array<*>).size }; null }
                "executeQuery" -> rs(); "executeUpdate" -> firstArray; "close" -> null; else -> null } } }
        val dataSource: DataSource = proxy(DataSource::class.java) { n, _ -> if (n == "getConnection") connection() else null }
        private fun connection(): java.sql.Connection = proxy(java.sql.Connection::class.java) { n, a -> when (n) {
            "prepareStatement" -> statement()
            "createArrayOf" -> proxy(java.sql.Array::class.java) { m, _ -> if (m == "getArray") a[1] else null }
            else -> null } }
    }

    /** Programmatic JFR over the lane segments when CAP_JFR=1: leaf and inclusive CPU samples and allocation weight by the nearest Reef frame. */
    private fun jfrReport(file: java.nio.file.Path, thread: String) {
        val self = HashMap<String, Long>(); val incl = HashMap<String, Long>(); var samples = 0L
        val allocBy = HashMap<String, Long>(); val allocClass = HashMap<String, Long>(); var allocTotal = 0L
        jdk.jfr.consumer.RecordingFile(file).use { f ->
            while (f.hasMoreEvents()) {
                val e = f.readEvent()
                if (e.eventType.name == "jdk.ExecutionSample" && e.getThread("sampledThread")?.javaName == thread) {
                    val frames = e.stackTrace?.frames ?: continue; samples++
                    frames.firstOrNull()?.let { fr -> self.merge(fr.method.type.name.substringAfterLast('.') + "." + fr.method.name, 1, Long::plus) }
                    val seen = HashSet<String>()
                    for (fr in frames) { val n = fr.method.type.name.substringAfterLast('.') + "." + fr.method.name; if (seen.add(n)) incl.merge(n, 1, Long::plus) }
                } else if (e.eventType.name == "jdk.ObjectAllocationSample" && e.getThread("eventThread")?.javaName == thread) {
                    val w = e.getLong("weight"); allocTotal += w
                    val frames = e.stackTrace?.frames ?: continue
                    val site = frames.firstOrNull { it.method.type.name.startsWith("com.reef") }?.let { it.method.type.name.substringAfterLast('.') + "." + it.method.name + ":" + it.lineNumber } ?: "(no reef frame)"
                    allocBy.merge(site, w, Long::plus); allocClass.merge(e.getClass("objectClass").name, w, Long::plus)
                }
            }
        }
        println("REENC-JFR cpu samples=$samples")
        for ((n, c) in self.entries.sortedByDescending { it.value }.take(25)) println("REENC-JFR self %5.1f%% %s".format(100.0 * c / samples, n))
        for ((n, c) in incl.entries.sortedByDescending { it.value }.filter { it.key.contains("Capacity") || it.key.contains("State") || it.key.contains("Canon") || it.key.contains("Finance") || it.key.contains("Message") || it.key.contains("Writer") }.take(30)) println("REENC-JFR incl %5.1f%% %s".format(100.0 * c / samples, n))
        for ((n, c) in allocBy.entries.sortedByDescending { it.value }.take(25)) println("REENC-JFR alloc %5.1f%% %s".format(100.0 * c / allocTotal, n))
        for ((n, c) in allocClass.entries.sortedByDescending { it.value }.take(12)) println("REENC-JFR alloc-class %5.1f%% %s".format(100.0 * c / allocTotal, n))
    }

    @Test fun laneAndProjectorCpuAndAllocationPerDecision() {
        val trades = env("CAP_TRADES", 100_000).toInt(); val segments = env("CAP_SEGMENTS", 3).toInt()
        val accountCount = env("CAP_ACCOUNTS", 2_000).toInt(); val instrumentCount = env("CAP_INSTRUMENTS", 8).toInt()
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }; val instruments = (0 until instrumentCount).map { "I$it" }
        val plan = CapacityPlan("cost", "run", "session", usd, opening, listOf(instruments.toSet()), 100_000_000, 100_000_000, 100_000_000, 100_000_000, sequenceBound = 1_000_000_000L)
        val cfg = CapacityProcessorConfig(plan, "CAPTURE", "CONTROL", "DECISIONS", "reenc-cost")
        val decoder = CapacityResolvedContextDecoder(plan)
        val context = MockProcessorContext<String, ByteArray>()
        val store = CapacityTopology.storeBuilder(cfg, persistent = false).withLoggingDisabled().build()
        store.init(context.getStateStoreContext(), store); context.addStateStore(store)
        val processor = CapacityProcessor(cfg, decoder); processor.init(context)
        var offset = 0L
        fun send(topic: String, raw: ByteArray) { context.setRecordMetadata(topic, 0, offset++); processor.process(Record(ByteArray(0), raw, 0L)) }
        val forwarded = ArrayList<ByteArray>()
        fun drain() { for (f in context.forwarded()) forwarded += f.record().value() as ByteArray; context.resetForwards() }

        var action = 0L
        fun fund(a: AccountId, asset: AssetId, amount: Long) =
            send("CONTROL", FinanceWire.toProto(FinanceInput.Fund(ActionKey("calcify-finance", "fund", "a${action++}"), Authority.OPERATOR, a, asset, amount)).toByteArray())
        for (a in accounts) fund(a, usd, 1_000_000_000_000L)
        for (a in accounts) for (s in instruments) fund(a, AssetId(s), 1_000_000L)
        drain(); forwarded.clear()

        val rnd = Random(7)
        fun context(i: Int): ByteArray {
            val ins = instruments[i % instrumentCount]
            val buyer = accounts[rnd.nextInt(accountCount)]; var seller = accounts[rnd.nextInt(accountCount)]
            if (seller == buyer) seller = accounts[(rnd.nextInt(accountCount - 1) + 1 + accounts.indexOf(buyer)) % accountCount]
            val c = MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId("run")
            c.tradeBuilder.setRunId("run").factBuilder.setExecutionId("exec-$i").setInstrumentId(ins).setBuyOrderId("b-$i").setSellOrderId("s-$i").quantityBuilder.setUnits((1 + rnd.nextInt(5)).toString())
            c.tradeBuilder.factBuilder.priceBuilder.setNanos((100 + rnd.nextInt(20)).toString()).setCurrency("USD")
            c.buyAcceptedOrderBuilder.factBuilder.setOrderId("b-$i").setVenueSessionId("session").setInstrumentId(ins).setAccountId(buyer.value).setParticipantId("pb")
            c.sellAcceptedOrderBuilder.factBuilder.setOrderId("s-$i").setVenueSessionId("session").setInstrumentId(ins).setAccountId(seller.value).setParticipantId("ps")
            return c.build().toByteArray()
        }
        val warm = 20_000
        val raws = Array(warm + trades * segments) { context(it) }
        for (i in 0 until warm) { send("CAPTURE", raws[i]); if (i % 1000 == 999) drain() }
        drain()
        forwarded.clear()                                            // the projector measurement starts at the first measured decision
        val firstMeasured = processor.currentLedger().sequence + 1
        var priorChain = processor.currentLedger().chain

        val jfr = if (System.getenv("CAP_JFR") == "1" || System.getenv("CAP_JFR") == "all") jdk.jfr.Recording().also { r ->
            r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1)).withStackTrace()
            r.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "10000/s"); r.start() } else null
        val laneCpu = ArrayList<Double>(); val laneAlloc = ArrayList<Double>(); val decodeCpu = ArrayList<Double>(); val decodeAlloc = ArrayList<Double>()
        for (seg in 0 until segments) {
            var cpu = 0L; var alloc = 0L; val base = warm + seg * trades
            var i = 0
            while (i < trades) {
                val end = minOf(i + 1000, trades)
                val m = measure { for (k in i until end) send("CAPTURE", raws[base + k]) }
                cpu += m.cpuNs; alloc += m.allocBytes
                drain(); i = end
            }
            val decisions = 2.0 * trades
            laneCpu += cpu / decisions; laneAlloc += alloc / decisions
            // the decoder alone on the same bytes (parse, validate, map to Capture and first Settle)
            var sink = 0
            val d = measure { for (k in 0 until trades) sink += decoder.decode("CAPTURE", 0, 0, raws[base + k]).size }
            decodeCpu += d.cpuNs / decisions; decodeAlloc += d.allocBytes / decisions
            println("REENC lane segment=$seg cpu_ns_per_decision=${"%.0f".format(cpu / decisions)} alloc_bytes_per_decision=${"%.0f".format(alloc / decisions)} " +
                "decoder_alone_cpu_ns_per_decision=${"%.0f".format(d.cpuNs / decisions)} decoder_alone_alloc_per_decision=${"%.0f".format(d.allocBytes / decisions)} (sink $sink)")
        }

        if (jfr != null) { jfr.stop(); val f = java.nio.file.Files.createTempFile("reenc", ".jfr"); jfr.dump(f); jfr.close(); jfrReport(f, Thread.currentThread().name); java.nio.file.Files.delete(f) }
        // projector JVM work against the stub: decode the published records, then apply them in chunks of 2000 as the tailing runner does
        val db = StubDb(); val projector = FinanceProjector(CapacityChain(plan.capacity(0)), db.dataSource)
        println("REENC projector decisions=${forwarded.size} first=$firstMeasured")
        val projectorJfr = if (System.getenv("CAP_JFR") == "2") jdk.jfr.Recording().also { r ->
            r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1)).withStackTrace()
            r.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "10000/s"); r.start() } else null
        repeat(segments + 1) { pass ->
            var records: List<Pair<Decision, FinanceInput>> = emptyList()
            val dec = measure { records = FinanceProjectionRunner.decode(forwarded.map { null to it }) }
            db.sequence = firstMeasured - 1; db.chain = priorChain
            val ap = measure {
                var from = 0
                while (from < records.size) {
                    val chunk = records.subList(from, minOf(from + 2000, records.size))
                    projector.apply("b".repeat(64), 1L, chunk)
                    db.sequence = chunk.last().first.sequence; db.chain = chunk.last().first.checksum
                    from += chunk.size
                }
            }
            val n = records.size.toDouble()
            println("REENC projector pass=$pass decode_cpu_ns_per_decision=${"%.0f".format(dec.cpuNs / n)} decode_alloc=${"%.0f".format(dec.allocBytes / n)} apply_cpu_ns_per_decision=${"%.0f".format(ap.cpuNs / n)} apply_alloc=${"%.0f".format(ap.allocBytes / n)}")
        }
        if (projectorJfr != null) { projectorJfr.stop(); val f = java.nio.file.Files.createTempFile("reenc", ".jfr"); projectorJfr.dump(f); projectorJfr.close(); jfrReport(f, Thread.currentThread().name); java.nio.file.Files.delete(f) }
        println("REENC lane cpu_ns_per_decision ranges: ${laneCpu.map { "%.0f".format(it) }} alloc: ${laneAlloc.map { "%.0f".format(it) }}")
    }
}
