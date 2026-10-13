package com.reef.platform.calcify.finance

import com.reef.platform.calcify.finance.FinanceBrokerFixture as F
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag

/** The reusable whole-log reader (F6): one consumer across passes, same contents as a fresh read, and it sees records written between passes. */
@Tag("broker")
class FinanceEvidenceLogReaderTest {
    @Test fun oneConsumerReReadsTheWholeLogOnEveryPassAndSeesNewRecords() {
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { F.createTopics(it) }
            val reader = FinanceEvidenceReader(Properties().apply { put("bootstrap.servers", rp.bootstrap) }, F.APP_ID, F.bindingDigest, F.CAPTURE, F.CONTROL, F.DECISIONS, F.changelog)
            val producer = F.producer(rp.bootstrap)
            fun send(i: Int) = F.send(producer, F.DECISIONS, "record-$i".toByteArray())
            reader.logReader(F.DECISIONS).use { log ->
                assertEquals(emptyList(), log.readAll(), "an empty log reads as empty")
                (0 until 3).forEach(::send)
                val first = log.readAll().map { String(it.second!!) }
                assertEquals(listOf("record-0", "record-1", "record-2"), first)
                (3 until 5).forEach(::send)
                val second = log.readAll().map { String(it.second!!) }
                assertEquals((0 until 5).map { "record-$it" }, second, "the second pass re-reads the WHOLE log, not only the new records")
                assertEquals(second, reader.readAll(F.DECISIONS).map { String(it.second!!) }, "same contents as a fresh consumer")
            }
            producer.close()
        }
    }
}
