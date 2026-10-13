package com.reef.platform.calcify.finance

import com.reef.platform.calcify.finance.FinanceBrokerFixture as F
import java.nio.file.Files
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.acl.AccessControlEntry
import org.apache.kafka.common.acl.AclBinding
import org.apache.kafka.common.acl.AclOperation
import org.apache.kafka.common.acl.AclPermissionType
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.resource.ResourcePattern
import org.apache.kafka.common.resource.ResourceType
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.Tag
import reef.contracts.calcify.finance.v1.FinanceDecisionV1

/**
 * S2 closeout: spec section 9 isolation matrix on a SASL/SCRAM Redpanda with authorization on. Principals mirror the spec's names;
 * the finance Streams app runs under its restricted ACL set, which makes the test the source of the minimal grants an operator must
 * provision. Run with `./gradlew test -PbrokerTests --tests '*FinanceAclTest'`.
 */
@Tag("broker")
class FinanceAclTest {
    private val users = listOf("ingress", "capture", "control", "finance", "observer", "matcher")
    private val commandTopic = "IT_COMMAND"
    private val controlTx = "it-control-writer"
    private val app = F.APP_ID

    private fun allow(user: String, type: ResourceType, name: String, pattern: PatternType, vararg ops: AclOperation) =
        ops.map { AclBinding(ResourcePattern(type, name, pattern), AccessControlEntry("User:$user", "*", it, AclPermissionType.ALLOW)) }

    private fun grants(): List<AclBinding> = buildList {
        fun topic(user: String, name: String, vararg ops: AclOperation) = addAll(allow(user, ResourceType.TOPIC, name, PatternType.LITERAL, *ops))
        topic("ingress", commandTopic, AclOperation.WRITE, AclOperation.DESCRIBE)
        addAll(allow("ingress", ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL, AclOperation.IDEMPOTENT_WRITE))
        topic("capture", F.CAPTURE, AclOperation.WRITE, AclOperation.DESCRIBE)
        addAll(allow("capture", ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL, AclOperation.IDEMPOTENT_WRITE))
        topic("control", F.CONTROL, AclOperation.WRITE, AclOperation.READ, AclOperation.DESCRIBE)
        addAll(allow("control", ResourceType.TRANSACTIONAL_ID, controlTx, PatternType.LITERAL, AclOperation.WRITE, AclOperation.DESCRIBE))
        addAll(FinanceGrants.financeAndObserver("finance", "observer", app, F.CAPTURE, F.CONTROL, F.DECISIONS, F.changelog))
        // "matcher" (existing principal) deliberately receives nothing on the new topics.
        topic("matcher", commandTopic, AclOperation.READ, AclOperation.DESCRIBE)
    }

    private fun producerAs(rp: SecuredRedpanda, user: String, transactionalId: String? = null, idempotent: Boolean = false) = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
        putAll(rp.auth(user)); put("bootstrap.servers", rp.bootstrap); put("acks", "all"); put("max.block.ms", 15000); put("request.timeout.ms", 10000); put("delivery.timeout.ms", 12000)
        put("key.serializer", ByteArraySerializer::class.java.name); put("value.serializer", ByteArraySerializer::class.java.name)
        if (transactionalId != null) put("transactional.id", transactionalId) else put("enable.idempotence", idempotent)
    })

    /** True when [user] can write to [topic]; false on an authorization refusal; any other failure propagates. */
    private fun canWrite(rp: SecuredRedpanda, user: String, topic: String): Boolean = producerAs(rp, user, idempotent = user in listOf("ingress", "capture")).use { p ->
        try { p.send(ProducerRecord(topic, 0, ByteArray(0), byteArrayOf(1))).get(15, TimeUnit.SECONDS); true }
        catch (e: java.util.concurrent.ExecutionException) { if (e.cause is AuthorizationException) false else throw e }
    }

    private fun canRead(rp: SecuredRedpanda, user: String, topic: String): Boolean = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        putAll(rp.auth(user)); put("bootstrap.servers", rp.bootstrap); put("enable.auto.commit", false); put("auto.offset.reset", "earliest"); put("isolation.level", "read_committed")
        put("key.deserializer", ByteArrayDeserializer::class.java.name); put("value.deserializer", ByteArrayDeserializer::class.java.name); put("default.api.timeout.ms", 10000)
    }).use { c ->
        // Authorization can surface at assign, seek, metadata or poll depending on client version: guard the whole interaction.
        try { c.assign(listOf(TopicPartition(topic, 0))); c.poll(Duration.ofSeconds(3)); true } catch (e: AuthorizationException) { false }
    }

    private fun txInit(rp: SecuredRedpanda, user: String, id: String): Boolean = producerAs(rp, user, transactionalId = id).use { p ->
        try { p.initTransactions(); true } catch (e: AuthorizationException) { false }
    }

    @Test fun isolationMatrixAndFinanceAppUnderItsMinimalGrants() {
        SecuredRedpanda(users).start().use { rp ->
            rp.admin("admin").use { admin ->
                admin.createTopics((listOf(F.CAPTURE, F.CONTROL, F.DECISIONS, commandTopic)).map { org.apache.kafka.clients.admin.NewTopic(it, 1, 1.toShort()).configs(mapOf("retention.ms" to "-1", "cleanup.policy" to "delete")) }).all().get(30, TimeUnit.SECONDS)
                admin.createAcls(grants()).all().get(30, TimeUnit.SECONDS)
            }
            // 1. Authentication: no credentials, wrong password.
            assertFailsWith<Exception>("anonymous") { AdminClient.create(Properties().apply { put("bootstrap.servers", rp.bootstrap); put("request.timeout.ms", 4000); put("default.api.timeout.ms", 6000) }).use { it.describeCluster().clusterId().get(6, TimeUnit.SECONDS) } }
            assertFailsWith<Exception>("wrong password") { rp.admin("finance").let { _ -> AdminClient.create(rp.auth("finance", "nope").apply { put("bootstrap.servers", rp.bootstrap); put("request.timeout.ms", 4000); put("default.api.timeout.ms", 6000) }).use { it.describeCluster().clusterId().get(6, TimeUnit.SECONDS) } } }

            // The finance app runs end to end under exactly these grants; inputs are written by their owners.
            val stateDir = Files.createTempDirectory("finance-acl-state").toFile()
            val streams = F.streams(rp.bootstrap, stateDir.path, rp.auth("finance")).also { it.start() }
            val control = producerAs(rp, "control", transactionalId = controlTx).also { it.initTransactions() }
            val capture = producerAs(rp, "capture", idempotent = true)
            fun controlWrite(i: FinanceInput) { control.beginTransaction(); control.send(ProducerRecord(F.CONTROL, 0, ByteArray(0), FinanceWire.toProto(i).toByteArray())).get(); control.commitTransaction() }
            controlWrite(F.fund("seller-shares", F.seller, F.aapl, 1)); controlWrite(F.fund("buyer-cash", F.buyer, F.usd, F.PRICE))
            capture.send(ProducerRecord(F.CAPTURE, 0, ByteArray(0), F.envelope(0).toByteArray())).get(15, TimeUnit.SECONDS)
            // CAPTURE and CONTROL are different topics, so the app may see a repair before its capture and refuse it (unknown execution,
            // not recorded). Wait until the capture is processed so the order, and the four recorded decisions, are deterministic.
            rp.admin("observer").use { o -> F.waitUntil("capture processed before the repair is written", seconds = 120) { (F.committed(o, F.CAPTURE) ?: 0L) >= 1L } }
            controlWrite(F.settle(F.executionId(0), 1))
            rp.admin("observer").use { obs ->
                // Transactional writes add commit markers to CONTROL, so offsets are not record counts: wait for the group to reach the
                // read-committed end of both inputs, which is only true once every record (the repair included) has been processed.
                val observer = rp.auth("observer")
                val captureEnd = F.readCommittedEnd(rp.bootstrap, F.CAPTURE, observer); val controlEnd = F.readCommittedEnd(rp.bootstrap, F.CONTROL, observer)
                F.waitUntil("finance app under ACLs processed everything", seconds = 120) {
                    (F.committed(obs, F.CAPTURE) ?: 0L) >= captureEnd && (F.committed(obs, F.CONTROL) ?: 0L) >= controlEnd
                }
                streams.close(Duration.ofSeconds(30))
                val evidence = F.evidence(rp.bootstrap, obs, rp.auth("observer"))
                val cut = FinanceRestoreCut.verify(F.ledger, evidence)
                assertEquals(4L, cut.state.sequence)
                assertTrue(cut.state.obligations.values.single().status == ObligationStatus.SETTLED)
                assertEquals(4, F.readAll(rp.bootstrap, F.DECISIONS, rp.auth("observer")).map { FinanceDecisionV1.parseFrom(it.second!!) }.size)
            }
            control.close(); capture.close()

            // Everything below writes junk into the topics, so it runs after the end-to-end evidence has been read.
            // 2. Write matrix: each principal writes only what it owns. (control and finance write transactionally in production; plain
            //    writes here prove the topic ACL itself.)
            val topics = listOf(commandTopic, F.CAPTURE, F.CONTROL, F.DECISIONS)
            val expectedWrite = mapOf("ingress" to setOf(commandTopic), "capture" to setOf(F.CAPTURE), "control" to setOf(F.CONTROL), "finance" to setOf(F.DECISIONS),
                "observer" to emptySet(), "matcher" to emptySet())
            for ((user, allowed) in expectedWrite) for (t in topics) assertEquals(t in allowed, canWrite(rp, user, t), "write $user -> $t")

            // 3. Read matrix: ingress/capture/matcher cannot read the finance topics; control reads CONTROL (recovery read, no group); observer reads all.
            for (t in listOf(F.CONTROL, F.DECISIONS)) for (u in listOf("ingress", "capture", "matcher")) assertEquals(false, canRead(rp, u, t), "read $u <- $t")
            assertEquals(true, canRead(rp, "control", F.CONTROL)); assertEquals(false, canRead(rp, "control", F.DECISIONS)); assertEquals(false, canRead(rp, "control", F.CAPTURE))
            for (t in listOf(F.CAPTURE, F.CONTROL, F.DECISIONS)) assertEquals(true, canRead(rp, "observer", t), "observer read $t")
            assertEquals(false, canRead(rp, "finance", "IT_UNGRANTED"), "finance cannot read topics outside its grants")

            // 4. Transactional-id separation: finance cannot fence the control writer, control cannot use finance ids, each can use its own.
            assertEquals(true, txInit(rp, "control", controlTx))
            assertEquals(false, txInit(rp, "finance", controlTx), "finance must not be able to fence the control writer")
            assertEquals(false, txInit(rp, "control", "$app-x"), "control must not use a finance transactional id")
            assertEquals(true, txInit(rp, "finance", "$app-x"))
            assertEquals(false, txInit(rp, "capture", controlTx)); assertEquals(false, txInit(rp, "ingress", "$app-1"))

        }
    }
}

