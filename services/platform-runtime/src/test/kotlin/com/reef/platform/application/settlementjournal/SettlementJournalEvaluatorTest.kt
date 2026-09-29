package com.reef.platform.application.settlementjournal

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettlementJournalEvaluatorTest {
    private val stream = ReferenceStreamPartition("venue-commands", "generation-1", 0)
    private val at = "2026-09-26T00:00:01Z"
    private val genesis = SettlementEvaluatorHead(0, "0".repeat(64), 7, "owner-one")
    private val oracle = ReferenceSettlementInterpreter(
        coverageVerifier = { _, _ -> true }, controlVerifier = { _, _ -> true })

    @Test
    fun scarceSellerWinnerAndBreakMatchIndependentReference() {
        val fixture = fixture(2, sellerShares = "1")
        val expected = oracle.evaluate(fixture.steps)
        val evaluator = evaluator()
        val proposal = evaluator.prepare(prepared(fixture, expected))
        assertParity(expected.results, proposal.results)
        assertEquals(listOf(ReferenceResultKind.SETTLED, ReferenceResultKind.BREAK),
            proposal.results.map { it.kind })
        assertTrue(proposal.results[1].effects.isEmpty())
        assertEquals(genesis, evaluator.snapshot().head)
        assertTrue(evaluator.snapshot().balances.isEmpty())
        assertFailsWith<IllegalStateException> { evaluator.prepare(prepared(fixture, expected)) }
        assertFailsWith<IllegalStateException> {
            evaluator.confirm(proposal, receipt(proposal).copy(proposalDigest = "f".repeat(64)))
        }
        assertTrue(evaluator.snapshot().balances.isEmpty())
        evaluator.confirm(proposal, receipt(proposal))
        assertEquals(expected.balances, evaluator.snapshot().balances)
        assertEquals(expected.outstanding, evaluator.snapshot().outstanding)
        assertEquals(mapOf("trade-4" to 1), evaluator.snapshot().attempts)

        assertFailsWith<IllegalStateException> {
            evaluator.prepare(listOf(SettlementPreparedInput.Trade(
                0, expected.results[0].trade)))
        }
        assertEquals(expected.balances, evaluator.snapshot().balances)
    }

    @Test
    fun cashBreakRetriesOnlyAfterCommittedFunding() {
        val fixture = fixture(1, sellerShares = "1", buyerCash = "0")
        val before = oracle.evaluate(fixture.steps)
        val funding = ReferenceFunding(controlSequence = fixture.controls.size + 1L,
            controlId = "fund-buyer", account = account("buyer-1", "CASH", "USD"),
            amount = BigDecimal("50"), retryTradeIds = listOf("trade-2"))
        val after = oracle.evaluate(fixture.steps + ReferenceStep.Control(funding))
        val evaluator = evaluator()
        val original = evaluator.prepare(prepared(fixture, before))
        assertParity(before.results, original.results)
        assertEquals(ReferenceBreakReason.CASH_LEG_FAILED, original.results.single().breakReason)
        evaluator.confirm(original, receipt(original))

        val retry = evaluator.prepare(listOf(
            SettlementPreparedInput.Trade(0, before.results.single().trade),
            SettlementPreparedInput.Control(1, funding,
                after.controlDigests.getValue(funding.controlId))))
        assertParity(after.results.drop(1), retry.results)
        assertEquals(2, retry.results.single().attemptNumber)
        assertEquals(ReferenceResultKind.SETTLED, retry.results.single().kind)
        assertEquals(before.balances, evaluator.snapshot().balances)
        evaluator.discard(retry)
        assertEquals(before.balances, evaluator.snapshot().balances)
        val retried = evaluator.prepare(listOf(SettlementPreparedInput.Control(
            0, funding, after.controlDigests.getValue(funding.controlId))))
        assertParity(after.results.drop(1), retried.results)
        evaluator.confirm(retried, receipt(retried))
        assertEquals(after.balances, evaluator.snapshot().balances)
        assertTrue(evaluator.snapshot().outstanding.isEmpty())
    }

    @Test
    fun verifiedCheckpointRestoresControlHashAndOutstandingRetryState() {
        val fixture = fixture(1, sellerShares = "1", buyerCash = "0")
        val before = oracle.evaluate(fixture.steps)
        val funding = ReferenceFunding(controlSequence = fixture.controls.size + 1L,
            controlId = "fund-after-restart", account = account("buyer-1", "CASH", "USD"),
            amount = BigDecimal("50"), retryTradeIds = listOf("trade-2"))
        val expected = oracle.evaluate(fixture.steps + ReferenceStep.Control(funding))
        val original = evaluator()
        val initial = original.prepare(prepared(fixture, before))
        original.confirm(initial, receipt(initial))
        val checkpoint = original.recoveryState()
        assertFailsWith<IllegalArgumentException> {
            SettlementJournalEvaluator.restoreVerified(checkpoint.copy(
                orderedControls = checkpoint.orderedControls.dropLast(1) +
                    checkpoint.orderedControls.first()), 8,
                { _, _ -> true }, { _, _ -> true })
        }
        val recovered = SettlementJournalEvaluator.restoreVerified(checkpoint, 8,
            { _, _ -> true }, { proposal, receipt ->
                proposal.proposalDigest == receipt.storeProposalDigest &&
                    receipt.committedHead.batchDigest == receipt.storeProposalDigest
            })
        assertEquals(checkpoint.head.batchSequence, recovered.snapshot().head.batchSequence)
        assertEquals(checkpoint.head.batchDigest, recovered.snapshot().head.batchDigest)
        assertEquals(8L, recovered.snapshot().head.ownerEpoch)
        val input = SettlementPreparedInput.Control(0, funding,
            expected.controlDigests.getValue(funding.controlId))
        val uninterrupted = original.prepare(listOf(input))
        val afterRestart = recovered.prepare(listOf(input))
        assertEquals(uninterrupted.results, afterRestart.results)
        assertParity(expected.results.drop(1), afterRestart.results)
        recovered.confirm(afterRestart, receipt(afterRestart))
        assertEquals(expected.balances, recovered.snapshot().balances)
        assertTrue(recovered.snapshot().outstanding.isEmpty())
    }

    @Test
    fun controlOnlyCommitAndDiverseTradeBatchMatchReference() {
        val fixture = fixture(2, sellerShares = "1", distinctSellers = true)
        val expected = oracle.evaluate(fixture.steps)
        val evaluator = evaluator()
        val controls = evaluator.prepare(fixture.controls.mapIndexed { index, control ->
            SettlementPreparedInput.Control(index.toLong(), control,
                expected.controlDigests.getValue(control.controlId))
        })
        assertTrue(controls.results.isEmpty())
        assertTrue(evaluator.snapshot().balances.isEmpty())
        evaluator.confirm(controls, receipt(controls))
        val decisions = evaluator.prepare(listOf(coverage(fixture, expected).copy(stepIndex = 0)) +
            expected.results.map {
            SettlementPreparedInput.Trade(0, it.trade)
        })
        assertParity(expected.results, decisions.results)
        assertEquals(2, decisions.results.count { it.kind == ReferenceResultKind.SETTLED })
        evaluator.confirm(decisions, receipt(decisions))
        assertEquals(expected.balances, evaluator.snapshot().balances)
    }

    @Test
    fun hot640WinnerOrderAndNarrowPerResultStateMatchReference() {
        val fixture = fixture(640, sellerShares = "8")
        val expected = oracle.evaluate(fixture.steps)
        val evaluator = evaluator()
        val proposal = evaluator.prepare(prepared(fixture, expected))
        assertParity(expected.results, proposal.results)
        assertEquals(8, proposal.results.count { it.kind == ReferenceResultKind.SETTLED })
        assertEquals(632, proposal.results.count { it.kind == ReferenceResultKind.BREAK })
        assertTrue(proposal.results.all { it.touchedBalancesBefore.size <= 4 &&
            it.touchedBalancesAfter.size <= 4 })
        evaluator.confirm(proposal, receipt(proposal))
        assertEquals(expected.balances, evaluator.snapshot().balances)
        assertEquals(expected.outstanding, evaluator.snapshot().outstanding)
    }

    @Test
    fun changedControlAndOwnerReceiptFailClosed() {
        val fixture = fixture(1, sellerShares = "1")
        val expected = oracle.evaluate(fixture.steps)
        val evaluator = evaluator()
        val wrongControl = fixture.controls.first()
        assertFailsWith<IllegalArgumentException> {
            evaluator.prepare(listOf(SettlementPreparedInput.Control(0, wrongControl, "f".repeat(64))))
        }
        val proposal = evaluator.prepare(prepared(fixture, expected))
        assertFailsWith<IllegalStateException> {
            evaluator.confirm(proposal, receipt(proposal).copy(committedHead =
                genesis.copy(batchSequence = 1, ownerIncarnation = "other-owner")))
        }
        assertFailsWith<IllegalStateException> {
            evaluator.confirm(proposal, receipt(proposal).copy(committedHead =
                genesis.copy(batchSequence = 2)))
        }
        evaluator.confirm(proposal, receipt(proposal))
        assertFailsWith<IllegalStateException> { evaluator.confirm(proposal, receipt(proposal)) }
    }

    @Test
    fun reversedSourceTradesCannotChangeScarceWinner() {
        val fixture = fixture(2, sellerShares = "1")
        val expected = oracle.evaluate(fixture.steps)
        val ordered = prepared(fixture, expected)
        val evaluator = evaluator()
        assertFailsWith<IllegalStateException> {
            evaluator.prepare(ordered.dropLast(2) + ordered.takeLast(2).reversed())
        }
        assertTrue(evaluator.snapshot().balances.isEmpty())
        val proposal = evaluator.prepare(ordered)
        assertParity(expected.results, proposal.results)
        assertEquals(ReferenceResultKind.SETTLED, proposal.results[0].kind)
        assertEquals(ReferenceResultKind.BREAK, proposal.results[1].kind)
    }

    @Test
    fun coveredSourceCannotCommitWithOmittedOrChangedTrade() {
        val fixture = fixture(2, sellerShares = "1")
        val expected = oracle.evaluate(fixture.steps)
        val ordered = prepared(fixture, expected)
        val evaluator = evaluator()
        assertFailsWith<IllegalStateException> { evaluator.prepare(ordered.dropLast(1)) }
        assertTrue(evaluator.snapshot().sourceFrontiers.isEmpty())
        val changed = (ordered.last() as SettlementPreparedInput.Trade).copy(
            obligation = expected.results.last().trade.copy(cash = BigDecimal("49")))
        assertFailsWith<IllegalStateException> {
            evaluator.prepare(ordered.dropLast(1) + changed)
        }
        val delayed = (ordered.last() as SettlementPreparedInput.Trade).copy(
            stepIndex = ordered.last().stepIndex + 1)
        assertFailsWith<IllegalStateException> {
            evaluator.prepare(ordered.dropLast(1) + delayed)
        }
        assertTrue(evaluator.snapshot().sourceFrontiers.isEmpty())
        val complete = evaluator.prepare(ordered)
        assertParity(expected.results, complete.results)
    }

    @Test
    fun sourceManifestNeedsExternalProofEvenForZeroTradeWindow() {
        val verifierRejects = SettlementJournalEvaluator(genesis,
            manifestVerifier = { _, _ -> false }, commitVerifier = { _, _ -> true })
        val empty = SettlementPreparedInput.SourceCoverage(0,
            ReferenceSourceWindowKey(stream, 0, 1),
            ReferenceCoverageEvidence(proofId = "fixture-source-reader", digest = "a".repeat(64)),
            expectedTrades = emptyList())
        assertFailsWith<IllegalStateException> { verifierRejects.prepare(listOf(empty)) }
        assertTrue(verifierRejects.snapshot().sourceFrontiers.isEmpty())
    }

    @Test
    fun proposalBindsExactPreparedControlAndSourceInputs() {
        val fixture = fixture(1, sellerShares = "1")
        val expected = oracle.evaluate(fixture.steps)
        val inputs = prepared(fixture, expected)
        val evaluator = evaluator()
        val proposal = evaluator.prepare(inputs)
        assertEquals(proposal.preparedInputsDigest,
            SettlementJournalEvaluator.preparedInputsDigest(inputs))

        val changed = inputs.toMutableList()
        val opening = changed[1] as SettlementPreparedInput.Control
        changed[1] = opening.copy(value = (opening.value as ReferenceOpening).copy(
            amount = BigDecimal("999")))
        assertNotEquals(proposal.preparedInputsDigest,
            SettlementJournalEvaluator.preparedInputsDigest(changed))

        val changedManifest = inputs.toMutableList()
        val coverageIndex = fixture.controls.size
        val coverage = changedManifest[coverageIndex] as SettlementPreparedInput.SourceCoverage
        changedManifest[coverageIndex] = coverage.copy(expectedTrades =
            coverage.expectedTrades.map { it.copy(cash = BigDecimal("49")) })
        assertNotEquals(proposal.preparedInputsDigest,
            SettlementJournalEvaluator.preparedInputsDigest(changedManifest))
    }

    @Test
    fun nonGenesisHeadAndPolicyRebindingAfterCoverageFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            SettlementJournalEvaluator(genesis.copy(batchSequence = 1,
                batchDigest = "a".repeat(64)), { _, _ -> true }) { _, _ -> true }
        }
        val fixture = fixture(1, sellerShares = "1")
        val expected = oracle.evaluate(fixture.steps)
        val evaluator = evaluator()
        val newPolicy = (fixture.controls.first() as ReferencePolicyActivation).copy(
            controlSequence = fixture.controls.size + 1L, controlId = "policy-2",
            effectiveAfterSourceFrontiers = mapOf(stream to 2L))
        assertFailsWith<IllegalStateException> {
            evaluator.prepare(prepared(fixture, expected).dropLast(1) + listOf(
                SettlementPreparedInput.Control(fixture.controls.size + 1L, newPolicy,
                    evaluator.controlMemberDigest(newPolicy)),
                SettlementPreparedInput.Trade(fixture.controls.size + 1L,
                    expected.results.single().trade)))
        }
        assertEquals(expected.results.single().trade.policyControlId,
            fixture.controls.first().controlId)
        assertTrue(evaluator.snapshot().balances.isEmpty())
    }

    @Test
    fun tradeNeedsVerifiedCoverageAndReceiptNeedsStoreAuthentication() {
        val fixture = fixture(1, sellerShares = "1")
        val expected = oracle.evaluate(fixture.steps)
        val withoutCoverage = prepared(fixture, expected).filterNot {
            it is SettlementPreparedInput.SourceCoverage
        }.map { input -> if (input is SettlementPreparedInput.Trade)
            input.copy(stepIndex = fixture.controls.size - 1L) else input }
        val evaluator = evaluator()
        assertFailsWith<IllegalStateException> { evaluator.prepare(withoutCoverage) }
        assertTrue(evaluator.snapshot().balances.isEmpty())

        val proposal = evaluator.prepare(prepared(fixture, expected))
        val untrusted = SettlementJournalEvaluator(genesis, { _, _ -> true }) { _, _ -> false }
        val untrustedProposal = untrusted.prepare(prepared(fixture, expected))
        assertFailsWith<IllegalStateException> {
            untrusted.confirm(untrustedProposal, receipt(untrustedProposal))
        }
        assertTrue(untrusted.snapshot().balances.isEmpty())
        evaluator.confirm(proposal, receipt(proposal))
        assertEquals(expected.balances, evaluator.snapshot().balances)
    }

    @Test
    fun emptyVerifiedSourceRangeAdvancesOnlyAfterCommit() {
        val evaluator = evaluator()
        val empty = SettlementPreparedInput.SourceCoverage(0,
            ReferenceSourceWindowKey(stream, 0, 3),
            ReferenceCoverageEvidence(proofId = "empty-range-proof", digest = "a".repeat(64)),
            expectedTrades = emptyList())
        val proposal = evaluator.prepare(listOf(empty))
        assertTrue(proposal.results.isEmpty())
        assertTrue(evaluator.snapshot().sourceFrontiers.isEmpty())
        evaluator.confirm(proposal, receipt(proposal))
        assertEquals(3L, evaluator.snapshot().sourceFrontiers[stream])
        assertFailsWith<IllegalStateException> { evaluator.prepare(listOf(empty)) }
        assertFailsWith<IllegalStateException> {
            evaluator.prepare(listOf(empty.copy(key = ReferenceSourceWindowKey(stream, 2, 4))))
        }
        val next = evaluator.prepare(listOf(empty.copy(key = ReferenceSourceWindowKey(stream, 3, 5),
            evidence = empty.evidence.copy(digest = "b".repeat(64)))))
        assertTrue(next.results.isEmpty())
    }

    private fun assertParity(expected: List<ReferenceResult>, actual: List<SettlementDecisionResult>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (reference, decision) ->
            assertEquals(reference.tradeId, decision.tradeId)
            assertEquals(reference.attemptNumber, decision.attemptNumber)
            assertEquals(reference.trade, decision.trade)
            assertEquals(reference.position, decision.position)
            assertEquals(reference.sourceMemberDigest, decision.sourceMemberDigest)
            assertEquals(reference.policyControlId, decision.policyControlId)
            assertEquals(reference.boundOpeningControlIds, decision.boundOpeningControlIds)
            assertEquals(reference.boundFundingControlIds, decision.boundFundingControlIds)
            assertEquals(reference.boundControlDigest, decision.boundControlDigest)
            assertEquals(reference.kind, decision.kind)
            assertEquals(reference.breakReason, decision.breakReason)
            assertEquals(reference.workflow, decision.workflow)
            assertEquals(reference.workflowJson, decision.workflowJson)
            assertEquals(reference.effects, decision.effects)
            touched(reference.trade).forEach { key ->
                assertEquals(reference.balancesBefore[key] ?: BigDecimal.ZERO,
                    decision.touchedBalancesBefore.getValue(key))
                assertEquals(reference.balancesAfter[key] ?: BigDecimal.ZERO,
                    decision.touchedBalancesAfter.getValue(key))
            }
        }
    }

    private fun touched(o: ReferenceObligation): List<ReferenceAccountKey> = listOf(
        o.buyer,
        ReferenceAccountKey(o.runId, o.seller.participantId, o.seller.accountId, "CASH", o.currency),
        o.seller,
        ReferenceAccountKey(o.runId, o.buyer.participantId, o.buyer.accountId, "SECURITY", o.instrumentId)
    ).distinct()

    private fun prepared(fixture: Fixture, expected: ReferenceEvaluation):
        List<SettlementPreparedInput> = fixture.controls.mapIndexed { index, control ->
            SettlementPreparedInput.Control(index.toLong(), control,
                expected.controlDigests.getValue(control.controlId))
        } + coverage(fixture, expected) + expected.results.map {
            SettlementPreparedInput.Trade(fixture.controls.size.toLong(), it.trade)
        }

    private fun coverage(fixture: Fixture, expected: ReferenceEvaluation):
        SettlementPreparedInput.SourceCoverage {
        val window = (fixture.steps.last() as ReferenceStep.Source).window
        return SettlementPreparedInput.SourceCoverage(fixture.controls.size.toLong(),
            ReferenceSourceWindowKey(window.stream, window.fromExclusiveSequence,
                window.throughInclusiveSequence), window.evidence,
            expected.results.map { it.trade })
    }

    private fun evaluator() = SettlementJournalEvaluator(genesis,
        manifestVerifier = { coverage, digest ->
            coverage.evidence.proofId.isNotBlank() && digest.length == 64
        }, commitVerifier = { proposal, receipt ->
        proposal.proposalDigest == receipt.storeProposalDigest &&
            receipt.committedHead.batchDigest == receipt.storeProposalDigest
    })

    private fun receipt(proposal: SettlementDecisionProposal) = SettlementAppendReceipt(
        proposal.expectedHead, proposal.proposalDigest, proposal.proposalDigest,
        proposal.expectedHead.copy(batchSequence = proposal.expectedHead.batchSequence + 1,
            batchDigest = proposal.proposalDigest))

    private data class Fixture(val controls: List<ReferenceControl>, val steps: List<ReferenceStep>)

    private fun fixture(count: Int, sellerShares: String, buyerCash: String = "50",
                        distinctSellers: Boolean = false): Fixture {
        val controls = mutableListOf<ReferenceControl>(ReferencePolicyActivation(
            controlSequence = 1, controlId = "policy-1", runId = "run-1",
            venueSessionId = "session-1", effectiveAfterSourceFrontiers = emptyMap(),
            profileId = "profile-1", policyVersion = 1, mode = "instant-post-trade",
            settlementCycle = "T0", nettingMode = "GROSS", ledgerPostingMode = "GROSS_DVP",
            selectionSource = "FIXTURE"))
        for (buyer in 1..count) controls += ReferenceOpening(controlSequence = buyer + 1L,
            controlId = "opening-buyer-$buyer", account = account("buyer-$buyer", "CASH", "USD"),
            amount = BigDecimal(buyerCash))
        val sellers = if (distinctSellers) (1..count).map { "seller-$it" } else listOf("seller")
        sellers.forEachIndexed { index, seller -> controls += ReferenceOpening(
            controlSequence = count + index + 2L, controlId = "opening-$seller",
            account = account(seller, "SECURITY", "AAPL"), amount = BigDecimal(sellerShares)) }
        val outcomes = (1..count).flatMap { index ->
            val seller = if (distinctSellers) "seller-$index" else "seller"
            listOf(sellerSource(index * 2 - 1L, seller, "seller-order-$index"),
                buyerSource(index * 2L, "buyer-$index", "seller-order-$index"))
        }
        val source = window(outcomes)
        return Fixture(controls, controls.map(ReferenceStep::Control) + ReferenceStep.Source(source))
    }

    private fun account(owner: String, assetType: String, assetId: String) =
        ReferenceAccountKey("run-1", owner, "$owner-account", assetType, assetId)

    private fun sellerSource(sequence: Long, owner: String, order: String): CanonicalOutcomeSource {
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-$sequence","orderId":"$order","occurredAt":"$at"},"acceptedOrder":${identity(order, owner, "SELL")},"orderStates":[${state(order, "SELL", "OPEN", "1")}]}"""
        return source(sequence, order, payload)
    }

    private fun buyerSource(sequence: Long, owner: String, sellerOrder: String): CanonicalOutcomeSource {
        val order = "$owner-order"
        val fill = "fill-$sequence"
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-$sequence","orderId":"$order","occurredAt":"$at"},"acceptedOrder":${identity(order, owner, "BUY")},"executions":[{"eventId":"buy-$sequence","executionId":"$fill-buy","orderId":"$order","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"$at","liquidityRole":"TAKER"},{"eventId":"sell-$sequence","executionId":"$fill-sell","orderId":"$sellerOrder","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"$at","liquidityRole":"MAKER"}],"trades":[{"eventId":"trade-event-$sequence","tradeId":"trade-$sequence","executionId":"$fill","buyOrderId":"$order","sellOrderId":"$sellerOrder","instrumentId":"AAPL","quantityUnits":"1","price":"50","currency":"USD","occurredAt":"$at"}],"orderStates":[${state(order, "BUY")},${state(sellerOrder, "SELL")}]}"""
        return source(sequence, order, payload)
    }

    private fun identity(order: String, owner: String, side: String) =
        """{"orderId":"$order","engineOrderId":"engine-$order","clientOrderId":"client-$order","runId":"run-1","venueSessionId":"session-1","instrumentId":"AAPL","participantId":"$owner","accountId":"$owner-account","side":"$side","orderType":"LIMIT","quantityUnits":"1","limitPrice":"50","currency":"USD","timeInForce":"DAY","acceptedAt":"$at"}"""

    private fun state(order: String, side: String, status: String = "FILLED",
                      remaining: String = "0") =
        """{"orderId":"$order","instrumentId":"AAPL","side":"$side","status":"$status","originalQuantity":"1","remainingQuantity":"$remaining","limitPrice":"50","currency":"USD","lastUpdatedAt":"$at"}"""

    private fun source(sequence: Long, order: String, payload: String) = CanonicalOutcomeSource(
        eventStream = stream.eventStream, partitionId = 0, streamSequence = sequence,
        batchId = "batch-$sequence", commandId = "command-$sequence", commandType = "SubmitOrder",
        payloadHash = "hash-$sequence", instrumentId = "AAPL", orderId = order,
        resultStatus = "accepted", resultPayloadJson = payload)

    private fun window(outcomes: List<CanonicalOutcomeSource>): ReferenceSourceWindow {
        val memberDigests = outcomes.map { source -> digest(listOf(
            "reef.reference.canonical-source.v1", source.eventStream, source.partitionId.toString(),
            source.streamSequence.toString(), source.batchId, source.commandId, source.commandType,
            source.payloadHash, source.instrumentId, source.orderId, source.resultStatus,
            source.resultPayloadJson)) }
        val coverage = digest(listOf("reef.reference.source-coverage.v1", stream.eventStream,
            stream.sourceGeneration, stream.partitionId.toString(), "0", outcomes.size.toString(),
            "fixture-source-reader") + memberDigests)
        return ReferenceSourceWindow(stream = stream, fromExclusiveSequence = 0,
            throughInclusiveSequence = outcomes.size.toLong(), outcomes = outcomes,
            evidence = ReferenceCoverageEvidence(proofId = "fixture-source-reader", digest = coverage))
    }

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
