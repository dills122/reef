package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.streams.StreamsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinancialBrokerProbeTest {
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
}
