package com.reef.platform.application.settlementjournal

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReferenceSettlementInterpreterTest {
    private val stream = ReferenceStreamPartition("venue-commands", "generation-1", 0)
    private val proofId = "fixture-source-reader"
    private val at = "2026-09-26T00:00:01Z"
    // Fixture-only attestations. Production source/control proof protocols remain integration work.
    private val interpreter = ReferenceSettlementInterpreter(
        coverageVerifier = { window, digest ->
            window.evidence.proofId == proofId && window.evidence.digest == digest
        },
        controlVerifier = { control, digest ->
            control.controlId.isNotBlank() && digest.length == 64
        }
    )

    /** Fixed expectation from bounded-transition scarce-account and gross-DvP integration cases. */
    @Test
    fun scarceWinnerAndFourLegParity() {
        val sources = listOf(
            seller(1, orderId = "seller-order-1"),
            buyer(2, "buyer-1", sellerOrderId = "seller-order-1"),
            seller(3, orderId = "seller-order-2"),
            buyer(4, "buyer-2", sellerOrderId = "seller-order-2")
        )
        val inputs = controls(
            opening(2, "buyer-1", "CASH", "USD", "50"),
            opening(3, "buyer-2", "CASH", "USD", "50"),
            opening(4, "seller", "SECURITY", "AAPL", "1")
        ) + ReferenceStep.Source(window(0, 4, sources))
        val result = interpreter.evaluate(inputs)
        assertEquals(listOf("trade-2", "trade-4"), result.results.map { it.tradeId })
        assertEquals(listOf(ReferenceResultKind.SETTLED, ReferenceResultKind.BREAK),
            result.results.map { it.kind })
        assertEquals(ReferenceBreakReason.SECURITY_LEG_FAILED, result.results[1].breakReason)
        assertEquals(listOf(ReferenceLegKind.BUYER_CASH_DEBIT,
            ReferenceLegKind.SELLER_CASH_CREDIT, ReferenceLegKind.SELLER_SECURITY_DEBIT,
            ReferenceLegKind.BUYER_SECURITY_CREDIT), result.results[0].effects.map { it.kind })
        assertEquals(listOf("50", "50", "1", "1"),
            result.results[0].effects.map { it.amount.toPlainString() })
        assertTrue(result.results[1].effects.isEmpty())
        assertEquals(result.results[1].balancesBefore, result.results[1].balancesAfter)
        assertEquals(BigDecimal.ZERO, result.balances.getValue(account("seller", "SECURITY", "AAPL")))
        assertEquals(BigDecimal("50"), result.balances.getValue(account("buyer-2", "CASH", "USD")))
        assertEquals(setOf("trade-4"), result.outstanding.keys)
        assertEquals(11, result.results[0].workflow.size)
        assertEquals("trade-2:SETTLED:1", result.results[0].workflow.last().id)
        assertEquals(listOf("opening-2", "opening-4"),
            result.results[0].boundOpeningControlIds)
    }

    @Test
    fun cashBreakAndExplicitFundingRetry() {
        val source = window(0, 2, listOf(seller(1), buyer(2, "buyer-1")))
        val base = controls(opening(2, "buyer-1", "CASH", "USD", "0"),
            opening(3, "seller", "SECURITY", "AAPL", "1")) + ReferenceStep.Source(source)
        val before = interpreter.evaluate(base)
        assertEquals(ReferenceBreakReason.CASH_LEG_FAILED, before.results.single().breakReason)
        assertTrue(before.results.single().effects.isEmpty())
        assertEquals(before.results.single().balancesBefore, before.results.single().balancesAfter)
        val after = interpreter.evaluate(base + ReferenceStep.Control(
            ReferenceFunding(controlSequence = 4, controlId = "fund-cash",
                account = account("buyer-1", "CASH", "USD"), amount = BigDecimal("50"),
                retryTradeIds = listOf("trade-2"))))
        assertEquals(listOf(ReferenceResultKind.BREAK, ReferenceResultKind.SETTLED),
            after.results.map { it.kind })
        assertEquals(listOf(1, 2), after.results.map { it.attemptNumber })
        assertEquals("trade-2:SETTLED:2", after.results.last().workflow.last().id)
        assertEquals(listOf("fund-cash"), after.results.last().boundFundingControlIds)
        assertTrue(after.outstanding.isEmpty())
    }

    @Test
    fun diverseAndOpsRealisticCohorts() {
        val sources = listOf(seller(1, "seller-1"), buyer(2, "buyer-1", "seller-1"),
            seller(3, "seller-2"), buyer(4, "buyer-2", "seller-2"))
        val inputs = controls(opening(2, "buyer-1", "CASH", "USD", "50"),
            opening(3, "buyer-2", "CASH", "USD", "50"),
            opening(4, "seller-1", "SECURITY", "AAPL", "1"),
            opening(5, "seller-2", "SECURITY", "AAPL", "1")) +
            ReferenceStep.Source(window(0, 4, sources))
        assertEquals(listOf(ReferenceResultKind.SETTLED, ReferenceResultKind.SETTLED),
            interpreter.evaluate(inputs).results.map { it.kind })
        // Ops-realistic remains logical pending; no PENDING journal result.
        val pending = interpreter.evaluate(listOf(ReferenceStep.Control(policy("ops-realistic"))) +
            inputs.drop(1))
        assertTrue(pending.results.isEmpty())
        assertEquals(setOf("trade-2", "trade-4"), pending.outstanding.keys)
    }

    @Test
    fun duplicateAndChangedInputsFailClosed() {
        val source = window(0, 2, listOf(seller(1), buyer(2, "buyer-1")))
        val base = controls(opening(2, "buyer-1", "CASH", "USD", "50"),
            opening(3, "seller", "SECURITY", "AAPL", "1")) +
            ReferenceStep.Source(source)
        assertEquals(interpreter.evaluate(base), interpreter.evaluate(base + ReferenceStep.Source(source)))
        val changed = source.outcomes.last().copy(payloadHash = "different")
        assertFailsWith<IllegalStateException> {
            interpreter.evaluate(base + ReferenceStep.Source(
                window(0, 2, listOf(source.outcomes.first(), changed))))
        }
        val byteChanged = source.outcomes.last().copy(
            resultPayloadJson = source.outcomes.last().resultPayloadJson
                .replace("\"effectVersion\":1", "\"effectVersion\":1 "))
        assertFailsWith<IllegalStateException> {
            interpreter.evaluate(base + ReferenceStep.Source(
                window(0, 2, listOf(source.outcomes.first(), byteChanged))))
        }
        assertFailsWith<IllegalStateException> {
            interpreter.evaluate(base + ReferenceStep.Control(
                opening(3, "seller", "SECURITY", "AAPL", "2")))
        }
    }

    @Test
    fun emptyCoverageNeedsSourceProofAndKeepsFrontier() {
        val first = window(0, 1, listOf(seller(1)))
        val empty = window(1, 3, emptyList())
        val next = window(3, 4, listOf(buyer(4, "buyer-1")))
        val prefix = controls(opening(2, "buyer-1", "CASH", "USD", "50"),
            opening(3, "seller", "SECURITY", "AAPL", "1")) +
            ReferenceStep.Source(first)
        val result = interpreter.evaluate(prefix + ReferenceStep.Source(empty) +
            ReferenceStep.Source(next))
        assertEquals(3, result.coverageDigests.size)
        assertEquals(ReferenceResultKind.SETTLED, result.results.single().kind)
        val forged = empty.copy(evidence = empty.evidence.copy(proofId = "untrusted"))
        assertFailsWith<IllegalStateException> {
            interpreter.evaluate(prefix + ReferenceStep.Source(forged))
        }
        assertFailsWith<IllegalArgumentException> {
            interpreter.evaluate(controls() + ReferenceStep.Source(
                window(0, 2, listOf(seller(1)))))
        }
    }

    @Test
    fun unpositionedLegacyShortcutIsExplicitlyRejected() {
        // D-059 permits an unpositioned run to pass its balance check. The journal
        // reference requires opening authority for debit resources before deciding.
        val source = window(0, 2, listOf(seller(1), buyer(2, "buyer-1")))
        assertFailsWith<IllegalStateException> {
            interpreter.evaluate(controls() + ReferenceStep.Source(source))
        }
    }

    @Test
    fun encodedPartitionOriginAndControlSequenceGapsFailClosed() {
        val partition = ReferenceStreamPartition(stream.eventStream, stream.sourceGeneration, 3)
        val origin = CanonicalStreamPosition.origin(3)
        val source = seller(1).copy(partitionId = 3, streamSequence = origin + 1)
        val evaluation = interpreter.evaluate(listOf(
            ReferenceStep.Control(policy().copy(effectiveAfterSourceFrontiers =
                mapOf(partition to origin))),
            ReferenceStep.Source(window(origin, origin + 1, listOf(source), partition))))
        assertEquals(1, evaluation.coverageDigests.size)
        assertTrue(evaluation.sourceMemberDigests.keys.any {
            it.stream == partition && it.streamSequence == origin + 1
        })
        assertFailsWith<IllegalStateException> {
            interpreter.evaluate(listOf(ReferenceStep.Control(policy()),
                ReferenceStep.Control(opening(3, "buyer-1", "CASH", "USD", "50"))))
        }
        val invalidPartition = partition.copy(partitionId = 32768)
        assertFailsWith<IllegalArgumentException> {
            interpreter.evaluate(listOf(ReferenceStep.Source(window(0, 1,
                listOf(source.copy(partitionId = 32768, streamSequence = 1)), invalidPartition))))
        }
        val partitionEnd = origin + ((1L shl 48) - 1)
        assertFailsWith<IllegalArgumentException> {
            interpreter.evaluate(listOf(ReferenceStep.Source(window(partitionEnd,
                partitionEnd + 1, listOf(source.copy(streamSequence = partitionEnd + 1)),
                partition))))
        }
    }

    @Test
    fun unrelatedFundingCannotCauseRetryAndControlNeedsExternalProof() {
        val source = window(0, 2, listOf(seller(1), buyer(2, "buyer-1")))
        val inputs = controls(opening(2, "buyer-1", "CASH", "USD", "0"),
            opening(3, "seller", "SECURITY", "AAPL", "1"),
            opening(4, "buyer-2", "CASH", "USD", "0")) +
            ReferenceStep.Source(source)
        assertFailsWith<IllegalStateException> {
            interpreter.evaluate(inputs + ReferenceStep.Control(ReferenceFunding(
                controlSequence = 5, controlId = "unrelated-funding",
                account = account("buyer-2", "CASH", "USD"), amount = BigDecimal("50"),
                retryTradeIds = listOf("trade-2"))))
        }
        val unverified = ReferenceSettlementInterpreter(
            coverageVerifier = { _, _ -> true }, controlVerifier = { _, _ -> false })
        assertFailsWith<IllegalStateException> {
            unverified.evaluate(listOf(ReferenceStep.Control(policy())))
        }
    }

    @Test
    fun hot640FixedScarceWinners() {
        val openings = (1..640).map { buyer ->
            opening((buyer + 1).toLong(), "buyer-$buyer", "CASH", "USD", "50")
        } + opening(642, "seller", "SECURITY", "AAPL", "8")
        val sources = (1..640).flatMap { index ->
            val sellerOrder = "seller-order-$index"
            listOf(
                seller((index * 2 - 1).toLong(), orderId = sellerOrder),
                buyer((index * 2).toLong(), "buyer-$index", sellerOrderId = sellerOrder)
            )
        }
        val result = interpreter.evaluate(controls(*openings.toTypedArray()) +
            ReferenceStep.Source(window(0, 1280, sources)))
        assertEquals(640, result.results.size)
        assertEquals(8, result.results.count { it.kind == ReferenceResultKind.SETTLED })
        assertEquals(632, result.results.count { it.kind == ReferenceResultKind.BREAK })
        assertEquals((1L..8L).map { it * 2 }, result.results.filter {
            it.kind == ReferenceResultKind.SETTLED
        }.map { it.position.streamSequence })
        val expectedKinds = listOf(ReferenceLegKind.BUYER_CASH_DEBIT,
            ReferenceLegKind.SELLER_CASH_CREDIT, ReferenceLegKind.SELLER_SECURITY_DEBIT,
            ReferenceLegKind.BUYER_SECURITY_CREDIT)
        assertTrue(result.results.take(8).all { settled ->
            settled.effects.map { it.kind } == expectedKinds &&
                settled.effects.map { it.amount.toPlainString() } ==
                    listOf("50", "50", "1", "1")
        })
        assertEquals(BigDecimal("400"),
            result.balances.getValue(account("seller", "CASH", "USD")))
        assertEquals(BigDecimal.ZERO, result.balances.getValue(account("seller", "SECURITY", "AAPL")))
        (1..8).forEach { index ->
            assertEquals(BigDecimal.ZERO,
                result.balances.getValue(account("buyer-$index", "CASH", "USD")))
            assertEquals(BigDecimal.ONE,
                result.balances.getValue(account("buyer-$index", "SECURITY", "AAPL")))
        }
        assertEquals(BigDecimal("50"), result.balances.getValue(account("buyer-9", "CASH", "USD")))
        assertEquals(BigDecimal.ZERO,
            result.balances[account("buyer-9", "SECURITY", "AAPL")] ?: BigDecimal.ZERO)
        assertTrue(result.results.drop(8).all { it.effects.isEmpty() &&
            it.balancesBefore == it.balancesAfter })
        assertEquals(632, result.outstanding.size)
    }

    private fun controls(vararg openings: ReferenceOpening): List<ReferenceStep> =
        listOf(ReferenceStep.Control(policy())) + openings.map(ReferenceStep::Control)

    private fun policy(mode: String = "instant-post-trade") = ReferencePolicyActivation(
        controlSequence = 1, controlId = "policy-1", runId = "run-1",
        venueSessionId = "session-1", effectiveAfterSourceFrontiers = emptyMap(),
        profileId = "profile-1", policyVersion = 1, mode = mode,
        settlementCycle = "T0", nettingMode = "GROSS", ledgerPostingMode = "GROSS_DVP",
        selectionSource = "FIXTURE")

    private fun account(owner: String, assetType: String, assetId: String) =
        ReferenceAccountKey("run-1", owner, "$owner-account", assetType, assetId)

    private fun opening(sequence: Long, owner: String, assetType: String, assetId: String,
                        amount: String) = ReferenceOpening(
        controlSequence = sequence, controlId = "opening-$sequence",
        account = account(owner, assetType, assetId), amount = BigDecimal(amount))

    private fun seller(sequence: Long, owner: String = "seller",
                       orderId: String = "$owner-order"): CanonicalOutcomeSource {
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-$sequence","orderId":"$orderId","occurredAt":"$at"},"acceptedOrder":${identity(orderId, owner, "SELL")},"orderStates":[${state(orderId, "SELL", "OPEN", "1")}]}"""
        return source(sequence, orderId, payload)
    }

    private fun buyer(sequence: Long, owner: String, seller: String = "seller",
                      sellerOrderId: String = "$seller-order"):
        CanonicalOutcomeSource {
        val buyOrder = "$owner-order"
        val sellOrder = sellerOrderId
        val fill = "fill-$sequence"
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-$sequence","orderId":"$buyOrder","occurredAt":"$at"},"acceptedOrder":${identity(buyOrder, owner, "BUY")},"executions":[{"eventId":"buy-$sequence","executionId":"$fill-buy","orderId":"$buyOrder","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"$at","liquidityRole":"TAKER"},{"eventId":"sell-$sequence","executionId":"$fill-sell","orderId":"$sellOrder","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"$at","liquidityRole":"MAKER"}],"trades":[{"eventId":"trade-event-$sequence","tradeId":"trade-$sequence","executionId":"$fill","buyOrderId":"$buyOrder","sellOrderId":"$sellOrder","instrumentId":"AAPL","quantityUnits":"1","price":"50","currency":"USD","occurredAt":"$at"}],"orderStates":[${state(buyOrder, "BUY")},${state(sellOrder, "SELL")}]}"""
        return source(sequence, buyOrder, payload)
    }

    private fun identity(order: String, owner: String, side: String) =
        """{"orderId":"$order","engineOrderId":"engine-$order","clientOrderId":"client-$order","runId":"run-1","venueSessionId":"session-1","instrumentId":"AAPL","participantId":"$owner","accountId":"$owner-account","side":"$side","orderType":"LIMIT","quantityUnits":"1","limitPrice":"50","currency":"USD","timeInForce":"DAY","acceptedAt":"$at"}"""

    private fun state(order: String, side: String, status: String = "FILLED",
                      remaining: String = "0") =
        """{"orderId":"$order","instrumentId":"AAPL","side":"$side","status":"$status","originalQuantity":"1","remainingQuantity":"$remaining","limitPrice":"50","currency":"USD","lastUpdatedAt":"$at"}"""

    private fun source(sequence: Long, order: String, payload: String) = CanonicalOutcomeSource(
        eventStream = stream.eventStream, partitionId = stream.partitionId,
        streamSequence = sequence, batchId = "batch-$sequence", commandId = "command-$sequence",
        commandType = "SubmitOrder", payloadHash = "hash-$sequence", instrumentId = "AAPL",
        orderId = order, resultStatus = "accepted", resultPayloadJson = payload)

    private fun window(from: Long, through: Long, outcomes: List<CanonicalOutcomeSource>,
                       targetStream: ReferenceStreamPartition = stream):
        ReferenceSourceWindow {
        val memberDigests = outcomes.map { source -> digest(listOf(
            "reef.reference.canonical-source.v1", source.eventStream, source.partitionId.toString(),
            source.streamSequence.toString(), source.batchId, source.commandId, source.commandType,
            source.payloadHash, source.instrumentId, source.orderId, source.resultStatus,
            source.resultPayloadJson)) }
        val coverage = digest(listOf("reef.reference.source-coverage.v1", targetStream.eventStream,
            targetStream.sourceGeneration, targetStream.partitionId.toString(), from.toString(),
            through.toString(), proofId) + memberDigests)
        return ReferenceSourceWindow(stream = targetStream, fromExclusiveSequence = from,
            throughInclusiveSequence = through, outcomes = outcomes,
            evidence = ReferenceCoverageEvidence(proofId = proofId, digest = coverage))
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
