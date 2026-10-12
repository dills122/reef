package com.reef.platform.calcify.finance

import com.reef.platform.calcify.finance.FinanceBrokerFixture as F
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.ProducerFencedException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.Tag

/** S2-A3 on a real broker: idempotence, conflicts, budgets, zombie fencing before the recovery read, and recovery from the log alone. */
@Tag("broker")
class FiniteControlWriterBrokerTest {
    private val txId = "it-control-writer"
    private fun props(bootstrap: String) = Properties().apply { put("bootstrap.servers", bootstrap) }
    private fun key(id: String) = ActionKey("operator", "fund", id)
    private fun logged(bootstrap: String) = F.readAll(bootstrap, F.CONTROL).map { FinanceWire.fromProto(reef.contracts.calcify.finance.v1.FinanceInputV1.parseFrom(it.second!!)) }

    @Test fun idempotenceConflictAndBudgetsOnTheRealLog() {
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { F.createTopics(it) }
            FiniteControlWriter(props(rp.bootstrap), F.CONTROL, txId).use { w ->
                w.recover()
                val first = w.fund(key("f1"), F.buyer, F.usd, 100) as ControlSubmission.Submitted
                assertEquals(ControlSubmission.AlreadySubmitted(first.offset), w.fund(key("f1"), F.buyer, F.usd, 100))
                assertEquals(ControlSubmission.ActionConflict(first.offset), w.fund(key("f1"), F.buyer, F.usd, 101))
                repeat(7) { assertTrue(w.fund(key("g$it"), F.buyer, F.usd, 1) is ControlSubmission.Submitted) }
                assertTrue(w.fund(key("one-too-many"), F.buyer, F.usd, 1) is ControlSubmission.BudgetExceeded)
                assertTrue(w.settle(ActionKey("operator", "repair", "s1"), ExecutionKey("p3-run", "p3-session", "AAPL", "e1"), 1) is ControlSubmission.Submitted)
                val log = logged(rp.bootstrap)
                assertEquals(9, log.size, "8 funds and 1 settle; duplicates, conflicts and refusals wrote nothing")
                assertTrue(log.all { (it as? FinanceInput.Fund)?.authority != Authority.TRADING && (it as? FinanceInput.Settle)?.authority != Authority.TRADING }, "the writer stamped OPERATOR")
                assertFailsWith<IllegalArgumentException> { w.fund(key("neg"), F.buyer, F.usd, 0) }
                assertFailsWith<IllegalArgumentException> { w.settle(ActionKey("operator", "repair", "bad"), ExecutionKey("r", "s", "i", "e"), 0) }
            }
        }
    }

    @Test fun aNewWriterFencesAZombieAndItsOpenTransactionNeverBecomesVisible() {
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { F.createTopics(it) }
            val zombie = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
                put("bootstrap.servers", rp.bootstrap); put("transactional.id", txId); put("acks", "all")
                put("key.serializer", ByteArraySerializer::class.java.name); put("value.serializer", ByteArraySerializer::class.java.name)
            })
            zombie.initTransactions(); zombie.beginTransaction()
            zombie.send(ProducerRecord(F.CONTROL, 0, null, FinanceWire.toProto(FinanceInput.Fund(key("zombie"), Authority.OPERATOR, F.buyer, F.usd, 999)).toByteArray())).get()
            // The zombie's transaction is open: a read-committed read now would stop behind it. Recovery must fence first.
            FiniteControlWriter(props(rp.bootstrap), F.CONTROL, txId).use { w ->
                w.recover()
                assertTrue(w.fund(key("real"), F.buyer, F.usd, 5) is ControlSubmission.Submitted)
                assertFailsWith<Exception>("the zombie's commit must fail") { zombie.commitTransaction() }
                val ids = logged(rp.bootstrap).map { it.action.actionId }
                assertEquals(listOf("real"), ids, "only the live writer's record is visible")
                assertEquals(ControlSubmission.Submitted::class, w.fund(key("after"), F.buyer, F.usd, 5)::class)
            }
            runCatching { zombie.close(java.time.Duration.ZERO) }
        }
    }

    @Test fun aRestartedWriterRebuildsEverythingFromTheLogAlone() {
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { F.createTopics(it) }
            val offsets = FiniteControlWriter(props(rp.bootstrap), F.CONTROL, txId).use { w ->
                w.recover(); (0 until 3).map { (w.fund(key("f$it"), F.buyer, F.usd, 10L + it) as ControlSubmission.Submitted).offset }
            }
            FiniteControlWriter(props(rp.bootstrap), F.CONTROL, txId).use { w ->
                w.recover()
                for (i in 0 until 3) assertEquals(ControlSubmission.AlreadySubmitted(offsets[i]), w.fund(key("f$i"), F.buyer, F.usd, 10L + i))
                assertEquals(ControlSubmission.ActionConflict(offsets[1]), w.fund(key("f1"), F.buyer, F.usd, 999))
                assertTrue(w.fund(key("f3"), F.buyer, F.usd, 13) is ControlSubmission.Submitted)
                // Five more funds exhaust the remaining budget: 3 recovered + 1 + 4 = 8.
                repeat(4) { assertTrue(w.fund(key("h$it"), F.buyer, F.usd, 1) is ControlSubmission.Submitted) }
                assertTrue(w.fund(key("last"), F.buyer, F.usd, 1) is ControlSubmission.BudgetExceeded, "the fund count survived the restart")
            }
        }
    }

    @Test fun aMultiPartitionControlTopicIsRefusedAtRecovery() {
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { it.createTopics(listOf(org.apache.kafka.clients.admin.NewTopic(F.CONTROL, 2, 1.toShort()))).all().get(30, java.util.concurrent.TimeUnit.SECONDS) }
            FiniteControlWriter(props(rp.bootstrap), F.CONTROL, txId).use { w ->
                assertFailsWith<IllegalStateException> { w.recover() }
            }
        }
    }

    @Test fun callsBeforeRecoverAreRefused() {
        FiniteControlWriter(props("127.0.0.1:1"), F.CONTROL, txId).use { w ->
            assertFailsWith<IllegalStateException> { w.fund(key("x"), F.buyer, F.usd, 1) }
        }
    }
}
