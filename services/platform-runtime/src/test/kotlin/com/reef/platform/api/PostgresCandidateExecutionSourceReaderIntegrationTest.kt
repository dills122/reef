package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresCandidateExecutionSourceReaderIntegrationTest {
    @Test
    fun indexedLocatorMustAgreeWithCommittedMatchingOutcome() {
        val url = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "disposable PostgreSQL test database required")
        val dataSource = RuntimeDataSources.dataSource(requireNotNull(url), requireNotNull(user),
            requireNotNull(password), "candidate-execution-source")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val sourceSchema = "execution_source_$suffix"
        val marketSchema = "execution_market_$suffix"
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $sourceSchema")
                statement.execute("CREATE SCHEMA $marketSchema")
                statement.execute("""CREATE TABLE $sourceSchema.canonical_command_outcomes (
                    event_stream TEXT NOT NULL, partition_id INTEGER NOT NULL,
                    stream_sequence BIGINT NOT NULL, batch_id TEXT NOT NULL,
                    command_id TEXT NOT NULL, command_type TEXT NOT NULL,
                    payload_hash TEXT NOT NULL, instrument_id TEXT NOT NULL,
                    order_id TEXT NOT NULL, result_status TEXT NOT NULL,
                    result_payload JSONB NOT NULL,
                    PRIMARY KEY (partition_id, stream_sequence))""")
                statement.execute("""CREATE TABLE $marketSchema.matching_market_candidate_tape (
                    event_stream TEXT NOT NULL, partition_id INTEGER NOT NULL,
                    projector_generation TEXT NOT NULL, trade_id TEXT NOT NULL,
                    run_id TEXT NOT NULL, venue_session_id TEXT NOT NULL,
                    instrument_id TEXT NOT NULL, currency TEXT NOT NULL,
                    event_id TEXT NOT NULL, execution_id TEXT NOT NULL,
                    quantity_units NUMERIC NOT NULL, price NUMERIC NOT NULL,
                    source_generation TEXT NOT NULL, source_stream_sequence BIGINT NOT NULL,
                    source_effect_ordinal INTEGER NOT NULL,
                    PRIMARY KEY (event_stream, partition_id, projector_generation, trade_id))""")
            }
        }
        try {
            val payload = """{"effectVersion":1,"accepted":{"eventId":"accepted","orderId":"buyer-order","occurredAt":"2026-09-28T00:00:00Z"},"acceptedOrder":{"orderId":"buyer-order","engineOrderId":"engine-buyer","clientOrderId":"client-buyer","runId":"run","venueSessionId":"session","instrumentId":"AAPL","participantId":"buyer","accountId":"buyer-account","side":"BUY","orderType":"LIMIT","quantityUnits":"1","limitPrice":"50","currency":"USD","timeInForce":"DAY","acceptedAt":"2026-09-28T00:00:00Z"},"executions":[{"eventId":"buy-event","executionId":"execution-buy","orderId":"buyer-order","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"2026-09-28T00:00:01Z","liquidityRole":"TAKER"},{"eventId":"sell-event","executionId":"execution-sell","orderId":"seller-order","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"2026-09-28T00:00:01Z","liquidityRole":"MAKER"}],"trades":[{"eventId":"trade-event","tradeId":"trade-1","executionId":"execution","buyOrderId":"buyer-order","sellOrderId":"seller-order","instrumentId":"AAPL","quantityUnits":"1","price":"50","currency":"USD","occurredAt":"2026-09-28T00:00:01Z"}],"orderStates":[{"orderId":"buyer-order","instrumentId":"AAPL","side":"BUY","status":"FILLED","originalQuantity":"1","remainingQuantity":"0","limitPrice":"50","currency":"USD","lastUpdatedAt":"2026-09-28T00:00:01Z"},{"orderId":"seller-order","instrumentId":"AAPL","side":"SELL","status":"FILLED","originalQuantity":"1","remainingQuantity":"0","limitPrice":"50","currency":"USD","lastUpdatedAt":"2026-09-28T00:00:01Z"}]}"""
            dataSource.connection.use { connection ->
                connection.prepareStatement("""INSERT INTO $sourceSchema.canonical_command_outcomes
                    VALUES ('stream', 0, 3, 'batch', 'command', 'SubmitOrder', 'hash', 'AAPL',
                    'buyer-order', 'accepted', ?::jsonb)""").use { statement ->
                    statement.setString(1, payload)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.createStatement().use { statement ->
                    statement.executeUpdate("""INSERT INTO $marketSchema.matching_market_candidate_tape
                        VALUES ('stream', 0, 'market-gen', 'trade-1', 'run', 'session', 'AAPL',
                        'USD', 'trade-event', 'execution', 1, 50, 'source-gen', 3, 3)""")
                }
            }
            val reader = PostgresCandidateExecutionSourceReader(dataSource, dataSource,
                sourceSchema = sourceSchema, marketSchema = marketSchema)
            val execution = reader.readExecution("stream", "market-gen", "source-gen", 2,
                "run", "trade-1")
            assertEquals(3L, execution.sourceSequence)
            assertEquals(3, execution.effectOrdinal)
            assertFailsWith<IllegalStateException> {
                reader.readExecution("stream", "market-gen", "source-gen", 2,
                    "wrong-run", "trade-1")
            }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("""UPDATE $marketSchema.matching_market_candidate_tape
                        SET event_id = 'tampered' WHERE trade_id = 'trade-1'""")
                }
            }
            assertFailsWith<IllegalStateException> {
                reader.readExecution("stream", "market-gen", "source-gen", 2,
                    "run", "trade-1")
            }
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $marketSchema CASCADE")
                    statement.execute("DROP SCHEMA $sourceSchema CASCADE")
                }
            }
        }
    }
}
