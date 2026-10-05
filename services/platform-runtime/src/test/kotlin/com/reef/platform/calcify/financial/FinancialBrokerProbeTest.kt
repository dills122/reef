package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TopologyTestDriver
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FinancialBrokerProbeTest {
    @Test fun `empty cold startup permits rate probe dynamic acknowledged membership`() {
        val json = ObjectMapper()
        val fixtures = json.readTree(Path.of("../../docs/evidence/calcify-financial-sprint1/fixtures.json").readText())
        val config = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("policy" to fixtures["policy"],
            "genesis" to mapOf("hot" to mapOf("balances" to fixtures["cases"][0]["genesisBalances"])),
            "acceptedManifest" to mapOf("inputTopicId" to "dynamic-source-uuid")))
        val topology = FinancialBrokerProbe.topology(config, "", "financial-s1-dynamic", { error("empty startup must not request historical membership") })
        TopologyTestDriver(topology, Properties().apply { put("application.id", "financial-s1-dynamic-test"); put("bootstrap.servers", "unused:9092") }).use { driver ->
            assertEquals(0L, driver.getKeyValueStore<String, String>("financial-state").approximateNumEntries())
        }
    }
    @Test fun `actual EOS worker config permits sixty second transaction grouping`() {
        val json = ObjectMapper()
        for (config in listOf(json.createObjectNode(), json.createObjectNode().put("commitIntervalMs", 60000).put("maxPollRecords", 1))) {
            val props = FinancialBrokerProbe.workerProperties("127.0.0.1:39192", "financial-s1-config-test", "/unused-test-state", config)
            val streams = StreamsConfig(props)
            assertEquals(60000L, streams.getLong(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG))
            assertEquals(StreamsConfig.EXACTLY_ONCE_V2, streams.getString(StreamsConfig.PROCESSING_GUARANTEE_CONFIG))
            val producer = streams.getProducerConfigs("config-test")
            assertTrue(producer["transaction.timeout.ms"].toString().toInt() > 60000)
            assertEquals(3, streams.getInt(StreamsConfig.REPLICATION_FACTOR_CONFIG))
        }
    }
    @Test fun `producer fault overflows only B settlement after bounded earlier sends`() {
        val config = ObjectMapper().createObjectNode()
        val props = FinancialBrokerProbe.workerProperties("unused", "financial-s1-test", "/unused", config, "production")
        val limit = StreamsConfig(props).getProducerConfigs("test")["max.request.size"].toString().toInt()
        assertEquals(131072, limit)
        val ordinary = "{\"domain\":\"A\",\"disposition\":\"SETTLED\"}"
        val target = "{\"domain\":\"B\",\"disposition\":\"SETTLED\"}"
        assertEquals(ordinary, FinancialBrokerProbe.outputBytes("production", ordinary)!!.toString(Charsets.UTF_8))
        assertTrue(FinancialBrokerProbe.outputBytes("production", target)!!.size > limit)
        assertEquals(target, FinancialBrokerProbe.outputBytes("", target)!!.toString(Charsets.UTF_8))
        assertFailsWith<IllegalStateException> { FinancialBrokerProbe.outputBytes("serialization", target) }
    }
}
