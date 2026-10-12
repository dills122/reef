package com.reef.platform.calcify.finance

import com.reef.platform.calcify.finance.FinanceBrokerFixture as F
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.streams.processor.StateRestoreListener
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Tag
import reef.contracts.calcify.finance.v1.FinanceDecisionV1

/**
 * S2-A4: the finance app on a real Redpanda broker (disposable container). Run with `./gradlew test -PbrokerTests --tests '*FinanceBrokerTest'`.
 * Proves what TopologyTestDriver cannot: EOS v2 commit atomicity across offsets + history + changelog + decisions, restore from the
 * changelog before processor init, and recovery after SIGKILL at arbitrary points.
 */
@Tag("broker")
class FinanceBrokerTest {
    private fun processed(admin: AdminClient, capture: Long, control: Long) =
        (F.committed(admin, F.CAPTURE) ?: 0L) == capture && (F.committed(admin, F.CONTROL) ?: 0L) == control

    private fun decisions(bootstrap: String) = F.readAll(bootstrap, F.DECISIONS).map { FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(it.second!!)) }

    private class Restores : StateRestoreListener {
        @Volatile var restored = 0L
        override fun onRestoreStart(topicPartition: TopicPartition, storeName: String, startingOffset: Long, endingOffset: Long) {}
        override fun onBatchRestored(topicPartition: TopicPartition, storeName: String, batchEndOffset: Long, numRestored: Long) { restored += numRestored }
        override fun onRestoreEnd(topicPartition: TopicPartition, storeName: String, totalRestored: Long) {}
    }

    private fun wipe(dir: File) { dir.deleteRecursively(); dir.mkdirs() }

    @Test fun cleanStopThenWipedLocalStateRestoresFromTheChangelogBeforeInitAndContinuesIdentically() {
        val count = 6
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { admin ->
                F.createTopics(admin)
                val stateDir = Files.createTempDirectory("finance-it-state").toFile()
                val producer = F.producer(rp.bootstrap)
                val oracleInputs = ArrayList<FinanceInput>()
                fun control(i: FinanceInput) { F.send(producer, F.CONTROL, FinanceWire.toProto(i).toByteArray()); oracleInputs += i }
                fun capture(i: Int) {
                    val envelope = F.envelope(i); F.send(producer, F.CAPTURE, envelope.toByteArray())
                    oracleInputs += (FinanceCaptureMapping.map(envelope, F.captureBinding).single() as FinanceCaptureMapping.Mapped.Ready).input
                }

                var streams = F.streams(rp.bootstrap, stateDir.path).also { it.start() }
                control(F.fund("seller-shares", F.seller, F.aapl, (count + 1).toLong())); control(F.fund("buyer-cash", F.buyer, F.usd, (count + 1) * F.PRICE))
                F.waitUntil("funds processed") { processed(admin, 0, 2) }
                repeat(count) { capture(it) }
                F.waitUntil("captures processed") { processed(admin, count.toLong(), 2) }
                repeat(count) { control(F.settle(F.executionId(it), 1)) }
                F.waitUntil("repairs processed") { processed(admin, count.toLong(), 2L + count) }
                streams.close(Duration.ofSeconds(30))

                val first = FinanceRestoreCut.verify(F.ledger, F.evidence(rp.bootstrap, admin))
                assertEquals((2 + 2 * count).toLong(), first.state.sequence)

                // Wipe local state: the next start must rebuild the store from the changelog, and init must see the restored store.
                wipe(stateDir)
                val restores = Restores()
                streams = F.streams(rp.bootstrap, stateDir.path).also { it.setGlobalStateRestoreListener(restores); it.start() }
                capture(count); control(F.settle(F.executionId(count), 1))
                F.waitUntil("post-restore records processed") { processed(admin, count + 1L, 3L + count) }
                streams.close(Duration.ofSeconds(30))
                assertTrue(restores.restored > 0, "the wiped store was rebuilt from the changelog, not started empty")

                val second = FinanceRestoreCut.verify(F.ledger, F.evidence(rp.bootstrap, admin))
                // Oracle: the same inputs in the same order through the pure ledger.
                var s = F.ledger.genesis(); val expected = ArrayList<Decision>()
                for (i in oracleInputs) F.ledger.decide(s, i).also { s = it.state; it.decision?.let(expected::add) }
                assertEquals(expected, decisions(rp.bootstrap).map { it.first })
                assertEquals(s, second.state)
                assertTrue(second.state.obligations.values.all { it.status == ObligationStatus.SETTLED })
                // History continued from the restored position rather than restarting at 1.
                assertEquals(second.meta.historyPosition, FinanceStore.entries(F.evidence(rp.bootstrap, admin).store).size.toLong())
                producer.close()
            }
        }
    }

    @Test fun abortedTransactionTailOnControlLeavesCommittedOffsetsRestorable() {
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { admin ->
                F.createTopics(admin)
                val stateDir = Files.createTempDirectory("finance-it-tail-state").toFile()
                val producer = F.producer(rp.bootstrap)
                val transactional = org.apache.kafka.clients.producer.KafkaProducer<ByteArray, ByteArray>(java.util.Properties().apply {
                    put("bootstrap.servers", rp.bootstrap); put("transactional.id", "it-control-writer"); put("acks", "all")
                    put("key.serializer", org.apache.kafka.common.serialization.ByteArraySerializer::class.java.name)
                    put("value.serializer", org.apache.kafka.common.serialization.ByteArraySerializer::class.java.name)
                }).also { it.initTransactions() }
                fun committedWrite(input: FinanceInput) {
                    transactional.beginTransaction()
                    transactional.send(org.apache.kafka.clients.producer.ProducerRecord(F.CONTROL, 0, ByteArray(0), FinanceWire.toProto(input).toByteArray())).get()
                    transactional.commitTransaction()
                }
                fun abortedWrite(input: FinanceInput) {
                    transactional.beginTransaction()
                    transactional.send(org.apache.kafka.clients.producer.ProducerRecord(F.CONTROL, 0, ByteArray(0), FinanceWire.toProto(input).toByteArray())).get()
                    transactional.abortTransaction()
                }
                var streams = F.streams(rp.bootstrap, stateDir.path).also { it.start() }
                committedWrite(F.fund("seller-shares", F.seller, F.aapl, 4))        // offset 0 (+ marker at 1)
                abortedWrite(F.fund("aborted-1", F.buyer, F.usd, 999))              // offsets 2 (+ abort marker at 3)
                committedWrite(F.fund("buyer-cash", F.buyer, F.usd, 4 * F.PRICE))   // offset 4 (+ marker at 5)
                abortedWrite(F.fund("aborted-2", F.buyer, F.usd, 998))              // TAIL: record at 6, abort marker at 7
                F.send(producer, F.CAPTURE, F.envelope(0).toByteArray())
                // Transactional writes add markers, so wait for the group to reach the read-committed end of both inputs (past the aborted tail).
                F.waitUntil("controls and capture processed", seconds = 90) {
                    (F.committed(admin, F.CAPTURE) ?: 0L) >= F.readCommittedEnd(rp.bootstrap, F.CAPTURE) && (F.committed(admin, F.CONTROL) ?: 0L) >= F.readCommittedEnd(rp.bootstrap, F.CONTROL)
                }
                Thread.sleep(1000)                                                   // a settled grace period before the app is closed
                streams.close(Duration.ofSeconds(30))
                val committedControl = F.committed(admin, F.CONTROL); val end = F.readCommittedEnd(rp.bootstrap, F.CONTROL)
                println("FINANCE-TAIL committedControl=$committedControl readCommittedEnd=$end uncommittedEnd=${F.uncommittedEnd(rp.bootstrap, F.CONTROL)}")
                val evidence = F.evidence(rp.bootstrap, admin)
                val entries = FinanceStore.entries(evidence.store)
                // The group committed past the aborted tail (offsets beyond the last positioned record) yet nothing visible was skipped.
                assertTrue((committedControl ?: 0L) > entries.filter { it.sourceRole == "CONTROL" }.maxOf { it.sourceOffset } + 1, "Streams committed past the aborted tail")
                assertEquals(null, F.firstVisible(rp.bootstrap, F.CONTROL, entries.filter { it.sourceRole == "CONTROL" }.maxOf { it.sourceOffset } + 1))
                val cut = FinanceRestoreCut.verify(F.ledger, evidence)
                assertEquals(2L, cut.state.sequence - 1, "two funds and one capture are the only recorded inputs")
                producer.close(); transactional.close()
            }
        }
    }

    @Test fun sigkillLoopLeavesAVerifiedHistoryWithEverySourceRecordPositionedExactlyOnce() {
        val count = 60
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { admin ->
                F.createTopics(admin)
                val stateDir = Files.createTempDirectory("finance-it-kill-state").toFile()
                val logs = Files.createTempDirectory("finance-it-kill-logs").toFile()
                val producer = F.producer(rp.bootstrap)
                val total = 2 + count + 2 * count     // funds + captures + (attempt 1 and attempt 2) per execution
                val producerThread = Thread {
                    F.send(producer, F.CONTROL, FinanceWire.toProto(F.fund("seller-shares", F.seller, F.aapl, count.toLong())).toByteArray())
                    F.send(producer, F.CONTROL, FinanceWire.toProto(F.fund("buyer-cash", F.buyer, F.usd, count * F.PRICE)).toByteArray())
                    for (i in 0 until count) {
                        F.send(producer, F.CAPTURE, F.envelope(i).toByteArray())
                        F.send(producer, F.CONTROL, FinanceWire.toProto(F.settle(F.executionId(i), 1)).toByteArray())
                        Thread.sleep(120)
                    }
                    for (i in 0 until count) F.send(producer, F.CONTROL, FinanceWire.toProto(F.settle(F.executionId(i), 2)).toByteArray())
                }
                val java = File(System.getProperty("java.home"), "bin/java").path
                var attempt = 0
                fun child(): Process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.reef.platform.calcify.finance.FinanceStreamsApp", rp.bootstrap, stateDir.path)
                    .redirectErrorStream(true).redirectOutput(File(logs, "child-${attempt++}.log")).start()
                val random = Random(20261011)
                producerThread.start()
                // Three SIGKILLs, each after a random amount of progress plus a sub-commit-interval jitter, so the kill lands at arbitrary points
                // of the poll/process/commit cycle. The 2nd kill is followed by a wiped local store (changelog restore after a crash).
                var restartsWithWipe = 0; var killsInsideATransaction = 0
                fun progress() = (F.committed(admin, F.CAPTURE) ?: 0L) + (F.committed(admin, F.CONTROL) ?: 0L)
                for (round in 0 until 3) {
                    val process = child()
                    val target = progress() + 8 + random.nextInt(18)
                    F.waitUntil("progress before kill $round", seconds = 120) { progress() >= target }
                    Thread.sleep(random.nextInt(120).toLong())
                    process.destroyForcibly().waitFor()                // SIGKILL: no shutdown hooks, no close(), no final commit
                    val open = F.uncommittedEnd(rp.bootstrap, F.DECISIONS) > F.readCommittedEnd(rp.bootstrap, F.DECISIONS)
                    if (open) killsInsideATransaction++
                    println("FINANCE-KILL round=$round committedCapture=${F.committed(admin, F.CAPTURE)} committedControl=${F.committed(admin, F.CONTROL)} openTransactionOnDecisions=$open")
                    if (round == 1) { wipe(stateDir); restartsWithWipe++ }
                }
                producerThread.join()
                val finalChild = child()
                F.waitUntil("everything processed", seconds = 180) { processed(admin, count.toLong(), (total - count).toLong()) }
                finalChild.destroy(); finalChild.waitFor()
                producer.close()

                val evidence = F.evidence(rp.bootstrap, admin)
                val cut = FinanceRestoreCut.verify(F.ledger, evidence)        // chain, store, history, cross-check, offset rule
                val entries = FinanceStore.entries(evidence.store)
                // Every source record is positioned exactly once: no skip, no duplicate, across all kills.
                assertEquals((0 until count).map { it.toLong() }, entries.filter { it.sourceRole == "CAPTURE" }.map { it.sourceOffset })
                assertEquals((0 until (total - count)).map { it.toLong() }, entries.filter { it.sourceRole == "CONTROL" }.map { it.sourceOffset })
                assertEquals(total, entries.size)
                // The recorded history is the order: replaying the inputs carried by the decisions reproduces them byte for byte.
                val log = decisions(rp.bootstrap)
                var s = F.ledger.genesis(); val replay = ArrayList<Decision>()
                for ((_, input) in log) F.ledger.decide(s, input).also { s = it.state; it.decision?.let(replay::add) }
                assertEquals(log.map { it.first }, replay)
                assertEquals(s, cut.state)
                // Whatever interleaving the broker produced, the economics converge: every execution settled exactly once.
                assertEquals(count, cut.state.obligations.size)
                assertTrue(cut.state.obligations.values.all { it.status == ObligationStatus.SETTLED && it.cashResidual == 0L && it.shareResidual == 0L })
                assertEquals(count.toLong(), cut.state.positions[F.buyer to F.aapl])
                assertEquals(count * F.PRICE, cut.state.positions[F.seller to F.usd])
                assertEquals(1, restartsWithWipe)
                println("FINANCE-KILL-SUMMARY kills=3 killsInsideATransaction=$killsInsideATransaction")
            }
        }
    }
}
