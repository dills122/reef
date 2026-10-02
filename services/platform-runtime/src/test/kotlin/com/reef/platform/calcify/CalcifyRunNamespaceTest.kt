package com.reef.platform.calcify

import com.reef.platform.calcify.CalcifySourceFixtures.rechecksum
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.streams.TopologyTestDriver

class CalcifyRunNamespaceTest {

    @Test fun reusedOrderIdsInDifferentRunsResolveIndependently() {
        val original = Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines().take(2)
        val other = original.map { rechecksum(it.replace("run-a", "run-b")) }
        val unicode = original.map { rechecksum(it.replace("run-a", "run:é").replace("rest-buy", "order:é").replace("partial-sell", "order:世界")) }
        val unicodeOther = unicode.map { rechecksum(it.replace("run:é", "run:世界")) }
        val cases = listOf(
            Triple(original + other, listOf(1L, 3L), listOf(0L, 2L)),
            Triple(listOf(original[0], other[0], original[1], other[1]), listOf(2L, 3L), listOf(0L, 1L)),
            Triple(listOf(unicode[0], unicodeOther[0], unicode[1], unicodeOther[1]), listOf(2L, 3L), listOf(0L, 1L)),
        )
        for ((bodies, tradeOffsets, acceptanceOffsets) in cases) {
            val reader = object : VenueSourceReader {
                override fun next(cursor: Long, target: Long) = bodies.getOrNull((cursor + 1).toInt())?.let { VenueSourceEntry(cursor + 1, it.toByteArray()) }
                override fun close() {}
            }
            val settings = ResolverSettings(generation = 1, sourceTopic = "source", sourceTopicId = "topic-uuid", verifiedTopic = "verified", outputTopic = "resolved")
            val topology = CalcifyResolverProcessor.topology(settings, { reader })
            TopologyTestDriver(topology, Properties().apply { put("application.id", "review-runs"); put("bootstrap.servers", "dummy:1234") }).use { driver ->
                val input = driver.createInputTopic("verified", ByteArraySerializer(), ByteArraySerializer())
                val output = driver.createOutputTopic("resolved", ByteArrayDeserializer(), ByteArrayDeserializer())
                for (offset in tradeOffsets) {
                    val passed = CommitmentVerificationPassed(CommitmentId(1, 0, offset, 0), 1)
                    input.pipeInput(CalcifyWire.commitment(passed.commitmentId), CalcifyWire.passed(passed))
                }
                val fault = driver.getKeyValueStore<String, ByteArray>("resolver").get("fault")
                assertNull(fault, fault?.toString(Charsets.UTF_8))
                val contexts = output.readKeyValuesToList().map { reef.contracts.calcify.v1.MatchContextResolvedV1.parseFrom(it.value) }
                assertEquals(if (bodies == cases.last().first) listOf("run:é", "run:世界") else listOf("run-a", "run-b"), contexts.map { it.runId })
                assertEquals(acceptanceOffsets, contexts.map { it.buyAcceptedOrder.source.sourceOffset })
            }
        }
    }

    @Test fun publiclySupportedHiddenLimitFactsDecode() {
        val body = Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines().first()
        val hidden = rechecksum(body.replace("\"orderType\":\"LIMIT\"", "\"orderType\":\"LIMIT_HIDDEN\""))
        val bodies = listOf(hidden, Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines()[1])
        val reader = object : VenueSourceReader {
            override fun next(cursor: Long, target: Long) = bodies.getOrNull((cursor + 1).toInt())?.let { VenueSourceEntry(cursor + 1, it.toByteArray()) }
            override fun close() {}
        }
        val settings = ResolverSettings(1, "source", "topic-uuid", "verified", "resolved")
        TopologyTestDriver(CalcifyResolverProcessor.topology(settings, { reader }), Properties().apply {
            put("application.id", "hidden-limit"); put("bootstrap.servers", "dummy:1234")
        }).use { driver ->
            val passed = CommitmentVerificationPassed(CommitmentId(1, 0, 1, 0), 1)
            driver.createInputTopic("verified", ByteArraySerializer(), ByteArraySerializer()).pipeInput(null, CalcifyWire.passed(passed))
            driver.advanceWallClockTime(java.time.Duration.ofMillis(50))
            val fault = driver.getKeyValueStore<String, ByteArray>("resolver").get("fault")
            assertNull(fault, fault?.toString(Charsets.UTF_8))
            val context = reef.contracts.calcify.v1.MatchContextResolvedV1.parseFrom(driver.createOutputTopic("resolved", ByteArrayDeserializer(), ByteArrayDeserializer()).readValue())
            assertEquals(reef.contracts.orderexecution.v1.OrderType.ORDER_TYPE_LIMIT, context.buyAcceptedOrder.fact.orderType)
            assertEquals("100", context.buyAcceptedOrder.fact.limitPrice)
        }
    }
    @Test fun realEngineHiddenLimitModifySourceResolvesExactContext() {
        val body = Path.of("../../docs/evidence/calcify-phase2/hidden-run-source-fixture.jsonl").readLines().single()
        val reader = sourceReader(listOf(body))
        driver(reader).use { driver ->
            pipe(driver, 0)
            driver.advanceWallClockTime(java.time.Duration.ofMillis(50))
            assertNull(driver.getKeyValueStore<String, ByteArray>("resolver").get("fault"))
            val context = reef.contracts.calcify.v1.MatchContextResolvedV1.parseFrom(output(driver).readValue())
            assertEquals("run-a", context.runId)
            assertEquals("run-a", context.trade.runId)
            assertEquals("cmd-modify", context.trade.source.commandId)
            assertEquals("buy", context.buyAcceptedOrder.fact.orderId)
            assertEquals("sell", context.sellAcceptedOrder.fact.orderId)
            assertEquals("200", context.sellAcceptedOrder.fact.limitPrice)
            assertEquals("100", context.trade.fact.price.nanos)
            assertEquals(reef.contracts.orderexecution.v1.OrderType.ORDER_TYPE_LIMIT, context.sellAcceptedOrder.fact.orderType)
            assertEquals(context, reef.contracts.calcify.v1.MatchContextResolvedV1.parseFrom(context.toByteArray()))
        }
    }

    @Test fun missingOrConflictingAuthoritativeTradeRunFailsClosed() {
        val historical = Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines()
        assertEquals("run-a", MatchContextResolver.parseBatch(historical[1], "source", "topic-uuid", 1, 0, 1).trades.single().runId)
        assertEquals("missing authoritative trade run", kotlin.test.assertFailsWith<IllegalArgumentException> {
            MatchContextResolver.parseBatch(historical[3], "source", "topic-uuid", 1, 0, 3)
        }.message)
        val conflicting = rechecksum(historical[1].replace("\"commandType\":\"SubmitOrder\"", "\"commandType\":\"SubmitOrder\",\"runId\":\"run-b\""))
        kotlin.test.assertFailsWith<IllegalArgumentException> { MatchContextResolver.parseBatch(conflicting, "source", "topic-uuid", 1, 0, 1) }
        val batch = MatchContextResolver.parseBatch(historical[1], "source", "topic-uuid", 1, 0, 1)
        val buy = MatchContextResolver.parseBatch(historical[0], "source", "topic-uuid", 1, 0, 0).acceptedOrders.single()
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            MatchContextResolver.resolve(CommitmentVerificationPassed(CommitmentId(1, 0, 1, 0), 1), batch.trades.single().toBuilder().setRunId("other").build(), buy, batch.acceptedOrders.single())
        }
    }

    @Test fun restoredPendingSnapshotMatchesLiveReplayWithoutBorrowingAcrossRuns() {
        val first = CalcifySourceFixtures.currentBodies().take(2)
        val second = first.map { rechecksum(it.replace("run-a", "run-b")) }
        val bodies = listOf(first[0], second[0], first[1], second[1])
        var available = 2
        val snapshot: Map<String, ByteArray>
        val live: List<ByteArray>
        driver(sourceReader(bodies) { available }).use { driver ->
            pipe(driver, 2)
            driver.advanceWallClockTime(java.time.Duration.ofMillis(50))
            assertEquals(0, output(driver).queueSize)
            snapshot = driver.getKeyValueStore<String, ByteArray>("resolver").all().use { rows -> rows.asSequence().associate { it.key to it.value.copyOf() } }
            available = bodies.size
            driver.advanceWallClockTime(java.time.Duration.ofMillis(50))
            pipe(driver, 3)
            driver.advanceWallClockTime(java.time.Duration.ofMillis(50))
            live = output(driver).readValuesToList()
        }
        val (processor, context, store) = restored(snapshot, sourceReader(bodies))
        try {
            context.scheduledPunctuators().first().punctuator.punctuate(50)
            val passed = CommitmentVerificationPassed(CommitmentId(1, 0, 3, 0), 1)
            context.setRecordMetadata("verified", 0, 1)
            processor.process(org.apache.kafka.streams.processor.api.Record(null, CalcifyWire.passed(passed), 0))
            context.scheduledPunctuators().first().punctuator.punctuate(100)
            val restored = context.forwarded().map { it.record().value() }
            assertEquals(live.size, restored.size)
            live.zip(restored).forEach { (expected, actual) -> kotlin.test.assertContentEquals(expected, actual) }
            assertNull(store.get("fault"))
            assertEquals("2", store.get("stateVersion").toString(Charsets.UTF_8))
            val resolved = restored.map { reef.contracts.calcify.v1.MatchContextResolvedV1.parseFrom(it) }
            assertEquals(listOf("run-a", "run-b"), resolved.map { it.runId })
            context.resetForwards()
            context.setRecordMetadata("verified", 0, 2)
            processor.process(org.apache.kafka.streams.processor.api.Record(null, CalcifyWire.passed(passed), 0))
            assertEquals(0, context.forwarded().size)
        } finally { processor.close() }
        driver(sourceReader(bodies)).use { driver ->
            pipe(driver, 2); pipe(driver, 3)
            repeat(10) { driver.advanceWallClockTime(java.time.Duration.ofMillis(50)) }
            val replay = output(driver).readValuesToList()
            assertEquals(live.size, replay.size)
            live.zip(replay).forEach { (expected, actual) -> kotlin.test.assertContentEquals(expected, actual) }
        }
    }

    @Test fun legacyAndIncompatibleStateRequireCoordinatedReplayAndRetainEvidence() {
        for (version in listOf(null, "1", "future")) {
            val legacy = mutableMapOf("identity" to "1:source:topic-uuid".toByteArray(), "cursor" to java.nio.ByteBuffer.allocate(8).putLong(1).array(), "O:1:8:rest-buy" to byteArrayOf(1))
            if (version != null) legacy["stateVersion"] = version.toByteArray()
            val (processor, context, store) = restored(legacy, sourceReader(emptyList()))
            try {
                kotlin.test.assertNotNull(store.get("fault"))
                kotlin.test.assertContentEquals(legacy.getValue("cursor"), store.get("cursor"))
                kotlin.test.assertContentEquals(byteArrayOf(1), store.get("O:1:8:rest-buy"))
                context.setRecordMetadata("verified", 0, 0)
                processor.process(org.apache.kafka.streams.processor.api.Record(null, CalcifyWire.passed(CommitmentVerificationPassed(CommitmentId(1, 0, 1, 0), 1)), 0))
                assertEquals(0, context.forwarded().size)
                kotlin.test.assertNotNull(store.get("Q:00000000000000000000"))
            } finally { processor.close() }
        }
    }

    @Test fun publicHiddenLimitCommandAndCanonicalAliasReplayKeepSameEconomics() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val body = Path.of("../../docs/evidence/calcify-phase2/hidden-run-source-fixture.jsonl").readLines().single()
        val fact = mapper.readTree(body).get("outcomes").get(0).get("result").get("acceptedOrder")
        val command = (fact.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()).apply {
            remove(listOf("engineOrderId", "acceptedAt"))
            put("commandId", "cmd-buy"); put("occurredAt", "2026-09-30T20:00:00Z")
            put("traceId", "trace-buy"); put("correlationId", "correlation-buy"); put("actorId", "actor-buy")
        }
        assertNull(com.reef.platform.api.PlatformCommandParsers.validateApiV1Command("/api/v1/orders/submit", command.toString()))
        val hidden = MatchContextResolver.parseBatch(body, "source", "topic-uuid", 1, 0, 0)
        val canonicalBody = rechecksum(body.replace("LIMIT_HIDDEN", "LIMIT"))
        val canonical = MatchContextResolver.parseBatch(canonicalBody, "source", "topic-uuid", 1, 0, 1)
        for ((original, replay) in hidden.acceptedOrders.zip(canonical.acceptedOrders)) {
            assertEquals(original, MatchContextResolver.mergeAcceptance(original, replay))
            assertEquals(original.fact, replay.fact)
        }
        val unsupported = rechecksum(body.replace("LIMIT_HIDDEN", "MARKET"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { MatchContextResolver.parseBatch(unsupported, "source", "topic-uuid", 1, 0, 0) }
    }

    @Test fun tradeCannotBorrowExistingAcceptanceFromAnotherRun() {
        val existing = CalcifySourceFixtures.currentBodies().take(2)
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val root = mapper.readTree(Path.of("../../docs/evidence/calcify-phase2/hidden-run-source-fixture.jsonl").readLines().single()) as com.fasterxml.jackson.databind.node.ObjectNode
        val modify = root.get("outcomes").get(2).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        modify.put("runId", "run-b")
        root.put("commandCount", 1)
        root.put("firstSequence", 3)
        root.put("lastSequence", 3)
        root.set<com.fasterxml.jackson.databind.JsonNode>("outcomes", mapper.createArrayNode().add(modify))
        val missing = rechecksum(root.toString().replace("\"buy\"", "\"rest-buy\"").replace("\"sell\"", "\"partial-sell\""))
        driver(sourceReader(existing + missing)).use { driver ->
            pipe(driver, 1)
            driver.advanceWallClockTime(java.time.Duration.ofMillis(50))
            assertEquals(1, output(driver).readValuesToList().size)
            pipe(driver, 2)
            driver.advanceWallClockTime(java.time.Duration.ofMillis(50))
            assertEquals("missing accepted order", driver.getKeyValueStore<String, ByteArray>("resolver").get("fault").toString(Charsets.UTF_8))
            kotlin.test.assertTrue(output(driver).isEmpty)
        }
    }

    private fun settings() = ResolverSettings(1, "source", "topic-uuid", "verified", "resolved")
    private fun sourceReader(bodies: List<String>, available: () -> Int = { bodies.size }) = object : VenueSourceReader {
        override fun next(cursor: Long, target: Long) = bodies.getOrNull((cursor + 1).toInt())?.takeIf { cursor + 1 < available() }?.let { VenueSourceEntry(cursor + 1, it.toByteArray()) }
        override fun close() {}
    }
    private fun driver(reader: VenueSourceReader) = TopologyTestDriver(CalcifyResolverProcessor.topology(settings().copy(maxAcceptedCacheRows = 1), { reader }), Properties().apply { put("application.id", "namespace-test"); put("bootstrap.servers", "dummy:1234") })
    private fun pipe(driver: TopologyTestDriver, offset: Long) = driver.createInputTopic("verified", ByteArraySerializer(), ByteArraySerializer()).pipeInput(null, CalcifyWire.passed(CommitmentVerificationPassed(CommitmentId(1, 0, offset, 0), 1)))
    private fun output(driver: TopologyTestDriver) = driver.createOutputTopic("resolved", ByteArrayDeserializer(), ByteArrayDeserializer())
    private fun restored(snapshot: Map<String, ByteArray>, reader: VenueSourceReader): Triple<CalcifyResolverProcessor, org.apache.kafka.streams.processor.api.MockProcessorContext<ByteArray, ByteArray>, org.apache.kafka.streams.state.KeyValueStore<String, ByteArray>> {
        val context = org.apache.kafka.streams.processor.api.MockProcessorContext<ByteArray, ByteArray>()
        val store = org.apache.kafka.streams.state.Stores.keyValueStoreBuilder(org.apache.kafka.streams.state.Stores.inMemoryKeyValueStore("resolver"), org.apache.kafka.common.serialization.Serdes.String(), org.apache.kafka.common.serialization.Serdes.ByteArray()).withLoggingDisabled().build()
        store.init(context.stateStoreContext, store)
        context.addStateStore(store)
        snapshot.forEach { (key, value) -> store.put(key, value.copyOf()) }
        val processor = CalcifyResolverProcessor(settings().copy(maxAcceptedCacheRows = 1), { reader }, { _, _ -> }, { _, _ -> })
        processor.init(context)
        return Triple(processor, context, store)
    }

}
