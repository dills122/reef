package com.reef.platform.calcify.financial

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinancialAckJournalTest {
    private val scope = FinancialAckJournal.Scope("run-1", "financial-s1-test-input", "uuid-1")
    private fun payload(ordinal: Long) = """{"domain":"hot","mode":"EXECUTE","inputOrdinal":$ordinal,"input":{"kind":"CAPTURE","payload":{"executionId":"timed-$ordinal"}}}""".toByteArray()
    private fun submit(j: FinancialAckJournal, ordinal: Long, offset: Long = ordinal * 3 + 7) = j.callback(ordinal, offset, payload(ordinal), "timed", "CAPTURE", ordinal, 10, 20)
    private fun await(j: FinancialAckJournal, count: Long) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (j.publishedCount < count && j.failure == null && System.nanoTime() < end) Thread.sleep(1)
        j.checkHealthy(); assertEquals(count, j.publishedCount)
    }
    private fun awaitFailure(j: FinancialAckJournal) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (j.failure == null && System.nanoTime() < end) Thread.sleep(1)
        assertNotNull(j.failure); assertFailsWith<IllegalStateException> { j.checkHealthy() }
    }
    @Test fun callbackVisibilityPrecedesForcedPublicationAndRecoveryRetainsPhysicalHoles() {
        val dir = Files.createTempDirectory("ack-red-"); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        FinancialAckJournal(dir, scope, boundary = { if (it == "before-data-force") { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }).use { j ->
            submit(j, 0); assertTrue(entered.await(5, TimeUnit.SECONDS)); assertNull(j.member(0)); assertEquals(0, j.publishedCount)
            release.countDown(); await(j, 1); assertEquals(7, j.member(0)!!["offset"].asLong()); submit(j, 1); await(j, 2)
        }
        FinancialAckJournal(dir, scope).use { recovered ->
            assertEquals(2, recovered.publishedCount); assertEquals(10, recovered.member(1)!!["offset"].asLong())
            assertEquals(10, recovered.member(0)!!["offeredNano"].asLong()); assertEquals(20, recovered.member(0)!!["callbackNano"].asLong())
            assertTrue(recovered.member(0)!!["controllerAdmissionNano"].isNull)
        }
    }

    @Test fun actualProcessCrashesDistinguishUnpublishedForcedAndPublishedBeforeNotification() {
        for (stage in listOf("before-data-force", "after-data-force-before-publication", "after-witness-force-before-publication", "after-publication-force-before-notification")) {
            val dir = Files.createTempDirectory("ack-crash-")
            val cp = listOf(FinancialAckJournalCrashProcess::class.java, FinancialAckJournal::class.java,
                com.fasterxml.jackson.databind.ObjectMapper::class.java, com.fasterxml.jackson.core.JsonFactory::class.java,
                com.fasterxml.jackson.annotation.JsonProperty::class.java, kotlin.Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", cp,
                FinancialAckJournalCrashProcess::class.java.name, dir.toString(), stage).redirectErrorStream(true).start()
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), stage); val output = process.inputStream.bufferedReader().readText()
            assertEquals(77, process.exitValue(), "$stage $output"); assertTrue(output.contains("crash-boundary=$stage"), output)
            if (stage == "after-witness-force-before-publication") {
                val refusal = assertFailsWith<IllegalArgumentException> { FinancialAckJournal(dir, scope) }
                assertTrue(refusal.message!!.contains("PUBLICATION_WITNESS_MISMATCH")); continue
            }
            FinancialAckJournal(dir, scope).use { recovered ->
                val expected = if (stage == "after-publication-force-before-notification") 1L else 0L
                assertEquals(expected, recovered.publishedCount, stage)
                if (expected == 1L) { val member = assertNotNull(recovered.member(0)); assertTrue(member["controllerAdmissionNano"].isNull); assertEquals(20, member["callbackNano"].asLong()); assertTrue(member["dataForceNano"].isIntegralNumber) }
                else { assertNull(recovered.member(0)); assertTrue((recovered.recoveryReceipt["unpublishedDataBytes"] as Long) > 0); assertTrue(Files.exists(dir.resolve(recovered.recoveryReceipt["retainedDataFile"] as String))) }
            }
        }
    }

    @Test fun everyPublishedFileMutationTornMarkerMissingFileAndGenerationMismatchRefuseActivation() {
        for (mutation in listOf("members.bin", "ordinal-index.bin", "publications.bin", "publication-witness.bin", "torn", "whole-marker-loss", "whole-witness-loss", "missing", "generation")) {
            val dir = Files.createTempDirectory("ack-corruption-")
            FinancialAckJournal(dir, scope).use { submit(it, 0); await(it, 1) }
            when (mutation) {
                "torn" -> Files.write(dir.resolve("publications.bin"), byteArrayOf(0, 0), APPEND)
                "missing" -> Files.delete(dir.resolve("members.bin"))
                "whole-marker-loss" -> FileChannel.open(dir.resolve("publications.bin"), WRITE).use { it.truncate(0) }
                "whole-witness-loss" -> FileChannel.open(dir.resolve("publication-witness.bin"), WRITE).use { it.truncate(0) }
                "generation" -> Unit
                else -> FileChannel.open(dir.resolve(mutation), READ, WRITE).use { channel ->
                    val at = if (mutation == "ordinal-index.bin") 0L else 8L
                    val original = ByteBuffer.allocate(1); channel.read(original, at)
                    channel.write(ByteBuffer.wrap(byteArrayOf((original.array()[0].toInt() xor 1).toByte())), at)
                }
            }
            assertFailsWith<Exception>(mutation) { FinancialAckJournal(dir, if (mutation == "generation") scope.copy(topicUuid = "uuid-2") else scope).close() }
        }
    }

    @Test fun oldPrefixLookupAfterRestartDoesNotDependOnLiveSuffixAndNewClockScopeAppends() {
        val dir = Files.createTempDirectory("ack-old-prefix-")
        var oldClock = ""
        FinancialAckJournal(dir, scope, queueCapacity = 32, batchSize = 16, batchMillis = 1).use { j ->
            repeat(80) { submit(j, it.toLong()); if (it % 16 == 15) await(j, it + 1L) }
            oldClock = j.member(0)!!["clockScope"].asText()
        }
        FinancialAckJournal(dir, scope).use { j ->
            assertEquals(80, j.recoveredCount); assertEquals(7, j.member(0)!!["offset"].asLong()); submit(j, 80); await(j, 81)
            assertFalse(j.member(80)!!["clockScope"].asText() == oldClock); assertEquals(oldClock, j.member(0)!!["clockScope"].asText())
        }
    }

    @Test fun queueBoundFailureIsStickyAndPreventsLaterPublication() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val j = FinancialAckJournal(Files.createTempDirectory("ack-queue-"), scope, queueCapacity = 1, batchSize = 1,
            boundary = { if (it == "before-data-force") { entered.countDown(); release.await(5, TimeUnit.SECONDS) } })
        try {
            submit(j, 0); assertTrue(entered.await(5, TimeUnit.SECONDS)); submit(j, 1)
            assertFailsWith<IllegalStateException> { submit(j, 2) }; val first = j.failure
            release.countDown(); awaitFailure(j); assertEquals(first, j.failure); assertEquals(0, j.publishedCount)
            assertFailsWith<IllegalStateException> { j.member(0) }
        } finally { release.countDown(); assertFailsWith<IllegalStateException> { j.close() } }
    }

    @Test fun duplicateOrdinalPhysicalMutationAndByteEnvelopeBoundsFailSticky() {
        for (bad in listOf("duplicate", "physical", "payload", "cap")) {
            val j = FinancialAckJournal(Files.createTempDirectory("ack-invalid-"), scope, maxBytes = if (bad == "cap") 4096 else 256L * 1024 * 1024)
            try {
                if (bad != "cap") { submit(j, 0); await(j, 1) }
                when (bad) {
                    "duplicate" -> submit(j, 0, 20)
                    "physical" -> submit(j, 1, 7)
                    "payload" -> assertFailsWith<IllegalArgumentException> { j.callback(1, 10, ByteArray(1025), "timed", "CAPTURE", 1, 10, 20) }
                    "cap" -> submit(j, 0)
                }
                awaitFailure(j)
            } finally { assertFailsWith<IllegalStateException> { j.close() } }
        }
    }

    @Test fun notificationsAreAfterPublicationForceAndRecoveredNotificationsAreNeverReplayed() {
        val notices = AtomicLong(); val forced = CountDownLatch(1); val release = CountDownLatch(1)
        val dir = Files.createTempDirectory("ack-notification-")
        FinancialAckJournal(dir, scope, boundary = { if (it == "after-publication-force-before-notification") { forced.countDown(); release.await(5, TimeUnit.SECONDS) } },
            onPublished = { member, nano -> assertTrue(nano >= member["callbackNano"].asLong()); notices.incrementAndGet() }).use { j ->
            submit(j, 0); assertTrue(forced.await(5, TimeUnit.SECONDS)); assertEquals(0, notices.get()); assertNull(j.member(0))
            release.countDown(); await(j, 1)
        }
        assertEquals(1, notices.get())
        FinancialAckJournal(dir, scope, onPublished = { _, _ -> notices.incrementAndGet() }).use { assertEquals(1, it.recoveredCount) }
        assertEquals(1, notices.get())
    }

    @Test fun actualRateProviderAndTopologyUseRecoveredDiskMembershipBeforeFinancialWork() {
        val json = ObjectMapper()
        val fixtures = json.readTree(Files.readString(Path.of("../../docs/evidence/calcify-financial-sprint1/fixtures.json")))
        val input = fixtures["cases"][0]["steps"][0]["input"].deepCopy<ObjectNode>().apply { put("domain", "hot") }
        val source = json.writeValueAsBytes(mapOf("domain" to "hot", "mode" to "EXECUTE", "inputOrdinal" to 0, "input" to input))
        val dir = Files.createTempDirectory("ack-provider-")
        FinancialAckJournal(dir, scope).use { j -> j.callback(0, 0, source, "timed", "CAPTURE", 0, 10, 20); await(j, 1) }
        FinancialAckJournal(dir, scope).use { recovered ->
            val config = json.valueToTree<JsonNode>(mapOf("policy" to fixtures["policy"], "genesis" to mapOf("hot" to mapOf("balances" to fixtures["cases"][0]["genesisBalances"])), "acceptedManifest" to mapOf("inputTopicId" to scope.topicUuid)))
            val topology = FinancialBrokerProbe.topology(config, "", "financial-s1-test", { FinancialRateProbe.acknowledgedMember(recovered, it) }, scope.topicUuid)
            TopologyTestDriver(topology, Properties().apply { put("application.id", "financial-s1-journal-provider"); put("bootstrap.servers", "unused:9092") }).use { driver ->
                val sourceTopic = driver.createInputTopic(scope.topic, StringSerializer(), StringSerializer())
                val results = driver.createOutputTopic("financial-s1-test-results", StringDeserializer(), StringDeserializer())
                sourceTopic.pipeInput("hot", source.toString(Charsets.UTF_8))
                val result = json.readTree(results.readValue())
                assertEquals("CAPTURED", result["disposition"].asText()); assertEquals(0, result["inputOrdinal"].asLong())
                val member = FinancialRateProbe.acknowledgedMember(recovered, 0)
                assertEquals(input, member["input"]); assertTrue(member["controllerAdmissionNano"].isNull)
            }
        }
    }
}

/** Child uses Runtime.halt: no journal close, force or shutdown hooks after fault. */
object FinancialAckJournalCrashProcess {
    @JvmStatic fun main(args: Array<String>) {
        val stage = args[1]
        val journal = FinancialAckJournal(Path.of(args[0]), FinancialAckJournal.Scope("run-1", "financial-s1-test-input", "uuid-1"), boundary = {
            if (it == stage) { println("crash-boundary=$stage"); System.out.flush(); Runtime.getRuntime().halt(77) }
        })
        val payload = """{"domain":"hot","mode":"EXECUTE","inputOrdinal":0,"input":{"kind":"CAPTURE","payload":{"executionId":"timed-0"}}}""".toByteArray()
        journal.callback(0, 7, payload, "timed", "CAPTURE", 0, 10, 20)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (journal.failure == null && System.nanoTime() < end) Thread.sleep(1)
        journal.checkHealthy(); error("crash boundary not reached")
    }
}
