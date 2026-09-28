package com.reef.platform.infrastructure.persistence

import com.fasterxml.jackson.databind.json.JsonMapper
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import com.reef.platform.application.settlement.PostTradeProfileSelection
import com.reef.platform.application.settlement.PostTradeProfileSelectionSource
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier
import com.reef.platform.application.settlementjournal.ReferenceCoverageEvidence
import com.reef.platform.application.settlementjournal.ReferenceCoverageProofVerifier
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.ReferenceResultKind
import com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreter
import com.reef.platform.application.settlementjournal.ReferenceSourceWindow
import com.reef.platform.application.settlementjournal.ReferenceStep
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.ReferenceEffect
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compares one canonical fixture through real normalized SQL and independent pure evaluation. */
class SettlementJournalReferenceParityIntegrationTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun canonicalTradeParityCoversDvpCashBreakAndScarceBuyerOrder() {
        val target = targetOrSkip()
        listOf(
            Case("settled", "200", 1, listOf("SETTLED")),
            Case("cash-break", "199", 1, listOf("BREAK")),
            Case("scarce-buyer", "200", 2, listOf("SETTLED", "BREAK"))
        ).forEach { case -> compareCase(target, case) }
    }

    private data class Case(val name: String, val buyerCash: String, val tradeCount: Int,
        val expectedOutcomes: List<String>)

    private fun compareCase(target: DataSource, case: Case) {
        val token = UUID.randomUUID().toString()
        val stream = "reference-parity-${case.name}-$token"
        val generation = "generation-$token"
        val run = "run-$token"
        val session = "session-$token"
        val buyer = "buyer-$token"
        val buyerAccount = "buyer-account-$token"
        val sources = (0 until case.tradeCount).flatMap { ordinal ->
            val seller = "seller-$ordinal-$token"
            listOf(
                source(stream, ordinal * 2L + 1, "sell-$ordinal-$token",
                    makerSubmit("sell-$ordinal-$token", seller, "seller-account-$ordinal-$token", run, session)),
                source(stream, ordinal * 2L + 2, "buy-$ordinal-$token",
                    takerSubmit("buy-$ordinal-$token", "sell-$ordinal-$token", buyer, buyerAccount,
                        run, session, "trade-$ordinal-$token"))
            )
        }
        val tradeIds = (0 until case.tradeCount).map { "trade-$it-$token" }
        val buyerCashKey = ReferenceAccountKey(run, buyer, buyerAccount, "CASH", "USD")
        val sellerSecurityKeys = (0 until case.tradeCount).map { ordinal ->
            ReferenceAccountKey(run, "seller-$ordinal-$token", "seller-account-$ordinal-$token",
                "SECURITY", "AAPL")
        }
        val policy = SettlementPolicySnapshot(
            PostTradeProfileSelection("instant-post-trade-v1", 1, "instant-post-trade",
                PostTradeProfileSelectionSource.HardDefault),
            "T+0", "gross-or-microbatch", "near-instant-finality"
        )
        try {
            val intake = SettlementCanonicalIntakeStore(target)
            val sourceVerifier = CanonicalSourceCoverageVerifier()
            val verified = sourceVerifier.verify(SettlementCanonicalIntakeStore.CONSUMER,
                stream, 0, generation, 0, sources.size.toLong(), sources)
            assertEquals(PostMatchApplyResult.APPLIED, intake.apply(verified))
            assertEquals(PostMatchApplyResult.DUPLICATE, intake.apply(verified))

            target.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO settlement.resource_positions(resource_position_id, scenario_run_id,
                         correlation_id, causation_id, participant_id, account_id, asset_type, asset_id,
                         quantity, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())"""
                ).use { statement ->
                    (listOf(buyerCashKey to case.buyerCash) + sellerSecurityKeys.map { it to "2" })
                        .forEachIndexed { index, (key, amount) ->
                            statement.setString(1, "position-$index-$token")
                            statement.setString(2, run)
                            statement.setString(3, "correlation-$token")
                            statement.setString(4, "causation-$token")
                            statement.setString(5, key.participantId)
                            statement.setString(6, key.accountId)
                            statement.setString(7, key.assetType)
                            statement.setString(8, key.assetId)
                            statement.setBigDecimal(9, BigDecimal(amount))
                            statement.addBatch()
                        }
                    assertEquals(case.tradeCount + 1, statement.executeBatch().size)
                }
            }

            val obligations = SettlementBoundedObligationStore(target)
            val obligationWindow = requireNotNull(obligations.readNextWindow(stream, 0, generation,
                maxSourcePositions = sources.size))
            assertEquals(tradeIds, obligationWindow.trades.map { it.tradeId })
            assertEquals(PostMatchApplyResult.APPLIED,
                obligations.apply(obligationWindow, mapOf(SettlementPolicyKey(run, session) to policy)))
            val transition = SettlementBoundedTransitionStore(target)
            val transitionWindow = requireNotNull(transition.readNextWindow(stream, 0, generation,
                maxSourcePositions = sources.size))
            assertEquals(PostMatchApplyResult.APPLIED, transition.admit(transitionWindow))
            assertEquals(PostMatchApplyResult.APPLIED, transition.apply(transitionWindow))
            assertEquals(PostMatchApplyResult.DUPLICATE, transition.apply(transitionWindow))

            // Fixture-only verifiers accept locally constructed control and coverage evidence. They
            // do not establish authentic broker absence or external control-stream provenance.
            val reference = ReferenceSettlementInterpreter(
                ReferenceCoverageProofVerifier { _, _ -> true },
                ReferenceControlProofVerifier { _, _ -> true }
            )
            val partition = ReferenceStreamPartition(stream, generation, 0)
            val controls = listOf(
                ReferenceStep.Control(ReferencePolicyActivation(controlSequence = 1,
                    controlId = "policy-$token", runId = run, venueSessionId = session,
                    effectiveAfterSourceFrontiers = emptyMap(), profileId = policy.selection.profileId,
                    policyVersion = policy.selection.policyVersion, mode = policy.selection.mode,
                    settlementCycle = policy.settlementCycle, nettingMode = policy.nettingMode,
                    ledgerPostingMode = policy.ledgerPostingMode,
                    selectionSource = policy.selection.source.name)),
                ReferenceStep.Control(ReferenceOpening(controlSequence = 2,
                    controlId = "opening-buyer-$token", account = buyerCashKey,
                    amount = BigDecimal(case.buyerCash)))
            ) + sellerSecurityKeys.mapIndexed { index, key ->
                ReferenceStep.Control(ReferenceOpening(controlSequence = (index + 3).toLong(),
                    controlId = "opening-seller-$index-$token", account = key,
                    amount = BigDecimal("2")))
            }
            val coverageId = "fixture-coverage-$token"
            val coverageDigest = digest(listOf("reef.reference.source-coverage.v1", stream,
                generation, "0", "0", sources.size.toString(), coverageId) +
                sources.map(::sourceMemberDigest))
            val window = ReferenceSourceWindow(stream = partition, fromExclusiveSequence = 0,
                throughInclusiveSequence = sources.size.toLong(), outcomes = sources,
                evidence = ReferenceCoverageEvidence(proofId = coverageId, digest = coverageDigest))
            val evaluated = reference.evaluate(controls + ReferenceStep.Source(window))
            assertEquals(tradeIds, evaluated.results.map { it.tradeId })
            assertEquals(transitionWindow.obligations.map { it.streamSequence to it.effectOrdinal },
                evaluated.results.map { it.position.streamSequence to it.position.effectOrdinal })
            assertEquals(case.expectedOutcomes, evaluated.results.map { it.kind.name })
            assertEquals(case.expectedOutcomes,
                readOutcomes(target, stream, generation, tradeIds, evaluated.results.map { it.workflowJson }))
            assertEquals(evaluated.results.flatMap { it.effects }.map(::legTuple),
                readLegs(target, stream, generation, tradeIds))
            val expectedAccounts = evaluated.results.flatMap { result ->
                listOf(result.trade.buyer, result.trade.seller,
                    ReferenceAccountKey(run, result.trade.seller.participantId,
                        result.trade.seller.accountId, "CASH", result.trade.currency),
                    ReferenceAccountKey(run, result.trade.buyer.participantId,
                        result.trade.buyer.accountId, "SECURITY", result.trade.instrumentId))
            }.toSet()
            assertEquals(expectedAccounts.associateWith {
                (evaluated.balances[it] ?: BigDecimal.ZERO).stripTrailingZeros()
            }, readBalances(target, stream, generation))
            assertEquals(tradeIds.filterIndexed { index, _ -> case.expectedOutcomes[index] == "BREAK" }.toSet(),
                evaluated.outstanding.keys)
        } finally {
            clean(target, stream, run)
        }
    }

    private fun readOutcomes(target: DataSource, stream: String, generation: String,
        tradeIds: List<String>, expectedWorkflow: List<String>): List<String> = target.connection.use { connection ->
        connection.prepareStatement(
            """SELECT attempt.trade_id, attempt.outcome, attempt.break_reason, attempt.workflow_facts,
                      obligation.status FROM settlement.canonical_transition_attempts attempt
               JOIN settlement.canonical_settlement_obligations obligation
                 ON obligation.event_stream = attempt.event_stream
                AND obligation.source_generation = attempt.source_generation
                AND obligation.trade_id = attempt.trade_id
               WHERE attempt.event_stream = ? AND attempt.source_generation = ?"""
        ).use { statement ->
            statement.setString(1, stream); statement.setString(2, generation)
            statement.executeQuery().use { rows ->
                val found = mutableMapOf<String, Pair<String, String?>>()
                while (rows.next()) {
                    val id = rows.getString(1)
                    assertEquals(mapper.readTree(expectedWorkflow[tradeIds.indexOf(id)]),
                        mapper.readTree(rows.getString(4)))
                    assertEquals(rows.getString(2), rows.getString(5))
                    found[id] = rows.getString(2) to rows.getString(3)
                }
                assertEquals(tradeIds.toSet(), found.keys)
                tradeIds.map { id ->
                    val (outcome, reason) = requireNotNull(found[id])
                    assertEquals(if (outcome == "BREAK") "CASH_LEG_FAILED" else null, reason)
                    outcome
                }
            }
        }
    }

    private fun legTuple(effect: ReferenceEffect): List<String> = listOf(effect.kind.name,
        effect.account.participantId, effect.account.accountId, effect.account.assetType,
        effect.account.assetId, effect.direction.name, effect.amount.stripTrailingZeros().toPlainString())

    private fun readLegs(target: DataSource, stream: String, generation: String,
        tradeIds: List<String>): List<List<String>> = target.connection.use { connection ->
        connection.prepareStatement(
            """SELECT trade_id, entry_kind, participant_id, account_id, asset_type, asset_id,
                      direction, quantity FROM settlement.canonical_transition_ledger_entries
               WHERE event_stream = ? AND source_generation = ?"""
        ).use { statement ->
            statement.setString(1, stream); statement.setString(2, generation)
            statement.executeQuery().use { rows ->
                val found = mutableMapOf<String, MutableList<List<String>>>()
                while (rows.next()) found.getOrPut(rows.getString(1)) { mutableListOf() }.add(
                    (2..7).map(rows::getString) + rows.getBigDecimal(8).stripTrailingZeros().toPlainString())
                tradeIds.flatMap { found[it].orEmpty().sortedBy { leg ->
                    listOf("BUYER_CASH_DEBIT", "SELLER_CASH_CREDIT", "SELLER_SECURITY_DEBIT",
                        "BUYER_SECURITY_CREDIT").indexOf(leg[0])
                } }
            }
        }
    }

    private fun readBalances(target: DataSource, stream: String, generation: String):
        Map<ReferenceAccountKey, BigDecimal> = target.connection.use { connection ->
        connection.prepareStatement(
            """SELECT run_id, participant_id, account_id, asset_type, asset_id,
                      opening_quantity + ledger_delta FROM settlement.canonical_account_state
               WHERE event_stream = ? AND source_generation = ?"""
        ).use { statement ->
            statement.setString(1, stream); statement.setString(2, generation)
            statement.executeQuery().use { rows ->
                val found = mutableMapOf<ReferenceAccountKey, BigDecimal>()
                while (rows.next()) found[ReferenceAccountKey(rows.getString(1), rows.getString(2),
                    rows.getString(3), rows.getString(4), rows.getString(5))] =
                    rows.getBigDecimal(6).stripTrailingZeros()
                found
            }
        }
    }

    private fun targetOrSkip(): DataSource {
        val url = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "migrated dedicated settlement PostgreSQL test database is required")
        return RuntimeDataSources.dataSource(url, user, password, "journal-reference-parity")
    }

    private fun clean(target: DataSource, stream: String, run: String) {
        target.connection.use { connection ->
            listOf("canonical_transition_admission_completions", "canonical_account_checkpoints",
                "canonical_transition_ledger_entries", "canonical_transition_attempts",
                "canonical_account_state", "canonical_transition_coverage", "canonical_transition_frontiers",
                "canonical_transition_dependencies", "canonical_transition_admission_accounts",
                "canonical_transition_admissions", "canonical_transition_admission_frontiers",
                "canonical_transition_admission_counter", "canonical_settlement_obligations",
                "canonical_obligation_coverage", "canonical_policy_bindings", "canonical_obligation_frontiers",
                "canonical_trade_intake", "canonical_order_directory", "canonical_intake_receipts",
                "canonical_intake_coverage", "canonical_intake_frontiers").forEach { table ->
                connection.prepareStatement("DELETE FROM settlement.$table WHERE event_stream = ?")
                    .use { it.setString(1, stream); it.executeUpdate() }
            }
            connection.prepareStatement("DELETE FROM settlement.resource_positions WHERE scenario_run_id = ?")
                .use { it.setString(1, run); it.executeUpdate() }
            connection.prepareStatement("DELETE FROM settlement.canonical_resource_openings WHERE run_id = ?")
                .use { it.setString(1, run); it.executeUpdate() }
        }
    }

    private fun source(stream: String, sequence: Long, orderId: String, payload: String) =
        CanonicalOutcomeSource(stream, 0, sequence, "batch-$sequence", "command-$sequence",
            "SubmitOrder", "hash-$sequence", "AAPL", orderId, "accepted", payload)

    private fun makerSubmit(order: String, participant: String, account: String, run: String,
        session: String) = """
        {"effectVersion":1,"accepted":{"eventId":"accept-$order","orderId":"$order","occurredAt":"2026-09-27T00:00:00Z"},
         "acceptedOrder":{"orderId":"$order","engineOrderId":"engine-$order","runId":"$run",
         "venueSessionId":"$session","instrumentId":"AAPL","participantId":"$participant",
         "accountId":"$account","side":"SELL","orderType":"LIMIT","quantityUnits":"2",
         "limitPrice":"100","currency":"USD","timeInForce":"DAY","acceptedAt":"2026-09-27T00:00:00Z"},
         "orderStates":[{"orderId":"$order","instrumentId":"AAPL","side":"SELL","status":"ACCEPTED",
         "originalQuantity":"2","remainingQuantity":"2","limitPrice":"100","currency":"USD",
         "lastUpdatedAt":"2026-09-27T00:00:00Z"}]}
    """.trimIndent()

    private fun takerSubmit(order: String, maker: String, participant: String, account: String,
        run: String, session: String, trade: String): String {
        return """
        {"effectVersion":1,"accepted":{"eventId":"accept-$order","orderId":"$order","occurredAt":"2026-09-27T00:00:01Z"},
         "acceptedOrder":{"orderId":"$order","engineOrderId":"engine-$order","runId":"$run",
         "venueSessionId":"$session","instrumentId":"AAPL","participantId":"$participant",
         "accountId":"$account","side":"BUY","orderType":"LIMIT","quantityUnits":"2",
         "limitPrice":"100","currency":"USD","timeInForce":"DAY","acceptedAt":"2026-09-27T00:00:01Z"},
         "executions":[
           {"eventId":"exec-buy-$trade","executionId":"$trade-buy","orderId":"$order","instrumentId":"AAPL","quantityUnits":"2","executionPrice":"100","currency":"USD","occurredAt":"2026-09-27T00:00:01Z","liquidityRole":"TAKER"},
           {"eventId":"exec-sell-$trade","executionId":"$trade-sell","orderId":"$maker","instrumentId":"AAPL","quantityUnits":"2","executionPrice":"100","currency":"USD","occurredAt":"2026-09-27T00:00:01Z","liquidityRole":"MAKER"}],
         "trades":[{"eventId":"event-$trade","tradeId":"$trade","executionId":"$trade",
         "buyOrderId":"$order","sellOrderId":"$maker","instrumentId":"AAPL","quantityUnits":"2",
         "price":"100","currency":"USD","occurredAt":"2026-09-27T00:00:01Z"}],
         "orderStates":[
           {"orderId":"$order","instrumentId":"AAPL","side":"BUY","status":"FILLED","originalQuantity":"2",
           "remainingQuantity":"0","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-27T00:00:01Z"},
           {"orderId":"$maker","instrumentId":"AAPL","side":"SELL","status":"FILLED","originalQuantity":"2",
           "remainingQuantity":"0","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-27T00:00:01Z"}]}
        """.trimIndent()
    }

    private fun sourceMemberDigest(source: CanonicalOutcomeSource): String = digest(listOf(
        "reef.reference.canonical-source.v1", source.eventStream, source.partitionId.toString(),
        source.streamSequence.toString(), source.batchId, source.commandId, source.commandType,
        source.payloadHash, source.instrumentId, source.orderId, source.resultStatus,
        source.resultPayloadJson
    ))

    private fun digest(fields: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
