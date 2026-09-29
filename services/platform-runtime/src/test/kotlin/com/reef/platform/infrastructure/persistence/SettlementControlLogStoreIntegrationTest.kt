package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceControl
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreter
import com.reef.platform.application.settlementjournal.ReferenceStep
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettlementControlLogStoreIntegrationTest {
    @Test
    fun controlPayloadAboveOneMebibyteCannotAdvanceHead() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "control-size-${UUID.randomUUID()}"
            val store = SettlementControlLogStore(dataSource, schema)
            val head = store.initialize(stream, 1, "nonce-1", "incarnation-1")
            val opening = ReferenceOpening(controlSequence = 1, controlId = "large-opening",
                account = ReferenceAccountKey("run-1", "participant-1", "x".repeat(1_048_560),
                    "CASH", "USD"), amount = BigDecimal.ONE)
            assertTrue(SettlementJournalControlCodec.encode(opening).size > 1_048_576)
            assertFailsWith<IllegalArgumentException> {
                store.appendBatch(proposal(stream, head, listOf(opening)))
            }
            assertEquals(head, store.head(stream))
            assertEquals(0L, rowCount(dataSource, schema, stream, "settlement_control_log_batches"))
        }
    }

    @Test
    fun exactRetrySurvivesSmallerProofBoundAndAppendStopsAtStreamCap() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "control-cap-${UUID.randomUUID()}"
            val store = SettlementControlLogStore(dataSource, schema, maxStreamControls = 2)
            val head = store.initialize(stream, 1, "nonce-1", "incarnation-1")
            val controls = listOf<ReferenceControl>(policy(1), ReferenceOpening(
                controlSequence = 2, controlId = "opening-1",
                account = ReferenceAccountKey("run-1", "participant-1", "account-1", "CASH", "USD"),
                amount = BigDecimal.ONE))
            val accepted = proposal(stream, head, controls)
            store.appendBatch(accepted)
            assertFailsWith<IllegalStateException> {
                store.readVerifiedPrefix(stream, "incarnation-1", maxControls = 1)
            }
            assertTrue(store.appendBatch(accepted).duplicate)
            assertEquals(2, store.readVerifiedPrefix(stream, "incarnation-1", maxControls = 2)
                .batches.single().members.size)
            assertFailsWith<IllegalArgumentException> {
                store.appendBatch(proposal(stream, store.head(stream), listOf(policy(3))))
            }
            assertEquals(3L, store.head(stream).nextControlSequence)
        }
    }

    @Test
    fun policyFrontierMustUseCanonicalPartitionRange() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "control-${UUID.randomUUID()}"
            val store = SettlementControlLogStore(dataSource, schema)
            val head = store.initialize(stream, 1, "nonce-1", "incarnation-1")
            val partition = ReferenceStreamPartition(stream, "generation-1", 1)
            assertFailsWith<IllegalArgumentException> {
                store.appendBatch(proposal(stream, head, listOf(policy(1).copy(
                    effectiveAfterSourceFrontiers = mapOf(partition to 0L)))))
            }
            assertEquals(1L, store.head(stream).nextControlSequence)
            val accepted = policy(1).copy(effectiveAfterSourceFrontiers =
                mapOf(partition to CanonicalStreamPosition.origin(1)))
            assertFalse(store.appendBatch(proposal(stream, head, listOf(accepted))).duplicate)
            assertEquals(accepted, store.readVerifiedPrefix(stream, "incarnation-1")
                .batches.single().members.single().decode())
        }
    }

    @Test
    fun boundedReplayRejectsExtraRowsEvenWhenHeadUnderBound() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "control-${UUID.randomUUID()}"
            val store = SettlementControlLogStore(dataSource, schema)
            val head = store.initialize(stream, 1, "nonce-1", "incarnation-1")
            store.appendBatch(proposal(stream, head, listOf(policy(1))))
            val committed = store.head(stream)
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO $schema.settlement_control_log_batches
                       (event_stream, first_control_sequence, last_control_sequence, member_count,
                        previous_digest, batch_digest, owner_epoch, owner_nonce, incarnation_id)
                       VALUES (?, 2, 2, 1, ?, ?, 1, 'nonce-1', 'incarnation-1')"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, committed.lastControlDigest)
                    statement.setString(3, "a".repeat(64))
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                store.readVerifiedPrefix(stream, "incarnation-1", maxControls = 1)
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO $schema.settlement_control_log_members
                       (event_stream, control_sequence, batch_first_control_sequence, control_id,
                        control_kind, control_version, payload, member_digest, prefix_digest)
                       VALUES (?, 2, 2, 'policy-2', 'POLICY', 1, ?, ?, ?)"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setBytes(2, SettlementJournalControlCodec.encode(policy(2)))
                    statement.setString(3, "b".repeat(64))
                    statement.setString(4, "c".repeat(64))
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                store.readVerifiedPrefix(stream, "incarnation-1", maxControls = 1)
            }
        }
    }

    @Test
    fun replayRejectsChangedKindSequenceHeaderAndMissingMember() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "control-${UUID.randomUUID()}"
            val store = SettlementControlLogStore(dataSource, schema)
            val head = store.initialize(stream, 1, "nonce-1", "incarnation-1")
            val opening = ReferenceOpening(controlSequence = 2, controlId = "opening-1",
                account = ReferenceAccountKey("run-1", "participant-1", "account-1", "CASH", "USD"),
                amount = BigDecimal.ONE)
            store.appendBatch(proposal(stream, head, listOf(policy(1), opening)))
            fun change(table: String, column: String, value: String, suffix: String) {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "UPDATE $schema.$table SET $column = ? WHERE event_stream = ? $suffix"
                    ).use { statement ->
                        statement.setString(1, value)
                        statement.setString(2, stream)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
            }
            change("settlement_control_log_members", "control_kind", "FUNDING",
                "AND control_sequence = 2")
            assertFailsWith<IllegalStateException> {
                store.readVerifiedPrefix(stream, "incarnation-1")
            }
            change("settlement_control_log_members", "control_kind", "OPENING",
                "AND control_sequence = 2")
            change("settlement_control_log_batches", "owner_nonce", "tampered-nonce",
                "AND first_control_sequence = 1")
            assertFailsWith<IllegalStateException> {
                store.readVerifiedPrefix(stream, "incarnation-1")
            }
            change("settlement_control_log_batches", "owner_nonce", "nonce-1",
                "AND first_control_sequence = 1")
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $schema.settlement_control_log_members SET control_sequence = 3
                       WHERE event_stream = ? AND control_sequence = 2"""
                ).use { statement ->
                    statement.setString(1, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                store.readVerifiedPrefix(stream, "incarnation-1")
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """DELETE FROM $schema.settlement_control_log_members
                       WHERE event_stream = ? AND control_sequence = 3"""
                ).use { statement ->
                    statement.setString(1, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                store.readVerifiedPrefix(stream, "incarnation-1")
            }
        }
    }

    @Test
    fun orderedTypedBatchReplayDuplicateAndOwnerFence() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "control-${UUID.randomUUID()}"
            val store = SettlementControlLogStore(dataSource, schema)
            val start = store.initialize(stream, 1, "nonce-1", "incarnation-1")
            val account = ReferenceAccountKey("run-1", "participant-1", "account-1", "CASH", "USD")
            val controls = listOf<ReferenceControl>(policy(1),
                ReferenceOpening(controlSequence = 2, controlId = "opening-1",
                    account = account, amount = BigDecimal("10.00")),
                ReferenceFunding(controlSequence = 3, controlId = "funding-1",
                    account = account, amount = BigDecimal("5.00"), retryTradeIds = emptyList()))
            val first = proposal(stream, start, controls)
            val receipt = store.appendBatch(first)
            assertFalse(receipt.duplicate)
            assertEquals(1L, receipt.firstSequence)
            assertEquals(3L, receipt.lastSequence)
            assertEquals(4L, store.head(stream).nextControlSequence)
            assertTrue(store.appendBatch(first).duplicate)
            assertFailsWith<IllegalStateException> {
                store.appendBatch(first.copy(controls = controls.map { control ->
                    if (control is ReferenceOpening) control.copy(amount = BigDecimal("11.00"))
                    else control
                }))
            }
            val verified = store.readVerifiedPrefix(stream, "incarnation-1")
            assertEquals(controls, verified.batches.single().members.map { it.decode() })
            val evaluated = ReferenceSettlementInterpreter({ _, _ -> true },
                verified.referenceVerifier()).evaluate(controls.map(ReferenceStep::Control))
            assertEquals(BigDecimal("15.00"), evaluated.balances[account])
            assertFalse(verified.referenceVerifier().verify(controls[0], "a".repeat(64)))
            assertFailsWith<IllegalStateException> {
                store.readVerifiedPrefix(stream, "restored-incarnation")
            }
            assertFailsWith<IllegalStateException> {
                store.initialize(stream, 1, "nonce-1", "restored-incarnation")
            }
            assertFailsWith<IllegalStateException> {
                store.appendBatch(first.copy(expectedFirstSequence = 5,
                    controls = listOf(policy(5))))
            }

            val fenced = store.fenceOwner(stream, store.head(stream), 2, "nonce-2")
            assertEquals(2L, fenced.ownerEpoch)
            assertFailsWith<IllegalStateException> { store.appendBatch(first) }
            assertFailsWith<IllegalStateException> {
                store.fenceOwner(stream, start, 3, "nonce-3")
            }
            val next = ReferenceOpening(controlSequence = 4, controlId = "opening-2",
                account = ReferenceAccountKey("run-1", "participant-2", "account-2", "CASH", "USD"),
                amount = BigDecimal.ZERO)
            val second = proposal(stream, fenced, listOf(next))
            assertFalse(store.appendBatch(second).duplicate)
            assertEquals(2, store.readVerifiedPrefix(stream, "incarnation-1").batches.size)
            assertFailsWith<IllegalStateException> { store.appendBatch(first) }
        }
    }

    @Test
    fun failedBatchRollsBackAndTamperedRowsFailReplay() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "control-${UUID.randomUUID()}"
            val store = SettlementControlLogStore(dataSource, schema)
            val head = store.initialize(stream, 1, "nonce-1", "incarnation-1")
            val controls = listOf<ReferenceControl>(policy(1), ReferenceOpening(
                controlSequence = 2, controlId = "opening-1",
                account = ReferenceAccountKey("run-1", "participant-1", "account-1", "CASH", "USD"),
                amount = BigDecimal.ONE))
            val first = proposal(stream, head, controls)
            val crashed = SettlementControlLogStore(dataSource, schema, beforeCommit = {
                error("injected crash before control commit")
            })
            assertFailsWith<IllegalStateException> { crashed.appendBatch(first) }
            assertEquals(1L, store.head(stream).nextControlSequence)
            assertEquals(0L, rowCount(dataSource, schema, stream, "settlement_control_log_batches"))
            assertEquals(0L, rowCount(dataSource, schema, stream, "settlement_control_log_members"))
            store.appendBatch(first)
            assertEquals(1L, rowCount(dataSource, schema, stream, "settlement_control_log_batches"))
            assertEquals(2L, rowCount(dataSource, schema, stream, "settlement_control_log_members"))

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $schema.settlement_control_log_members SET payload = ?
                       WHERE event_stream = ? AND control_sequence = 2"""
                ).use { statement ->
                    statement.setBytes(1, byteArrayOf(1, 2, 3))
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalArgumentException> {
                store.readVerifiedPrefix(stream, "incarnation-1")
            }
            assertFailsWith<IllegalStateException> { store.appendBatch(first) }
        }
    }

    private fun policy(sequence: Long) = ReferencePolicyActivation(controlSequence = sequence,
        controlId = "policy-$sequence", runId = "run-1", venueSessionId = "session-1",
        effectiveAfterSourceFrontiers = emptyMap(), profileId = "instant-post-trade-v1",
        policyVersion = 1, mode = "instant-post-trade", settlementCycle = "T+0",
        nettingMode = "gross", ledgerPostingMode = "gross-dvp", selectionSource = "fixture")

    private fun proposal(stream: String, head: SettlementControlLogHead, controls: List<ReferenceControl>) =
        SettlementControlLogProposal(stream, head.nextControlSequence, head.lastControlDigest,
            head.ownerEpoch, head.ownerNonce, head.incarnationId, controls)

    private fun rowCount(dataSource: DataSource, schema: String, stream: String, table: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $schema.$table WHERE event_stream = ?")
                .use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
                }
        }

    private fun withMigratedSchema(dataSource: DataSource, run: (String) -> Unit) {
        val schema = "control_log_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        try {
            val migration = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("scripts/dev/db/migrations/postmatch/0007_settlement_control_log.sql") }
                .first(Files::exists)
            val sql = Files.readString(migration).lineSequence()
                .filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                .replace("postmatch.", "$schema.")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    sql.split(';').map(String::trim).filter(String::isNotBlank)
                        .forEach(statement::execute)
                }
            }
            run(schema)
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun dataSourceOrSkip(): DataSource {
        val url = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "disposable settlement PostgreSQL test database required")
        return RuntimeDataSources.dataSource(url, user, password, "settlement-control-log")
    }
}
