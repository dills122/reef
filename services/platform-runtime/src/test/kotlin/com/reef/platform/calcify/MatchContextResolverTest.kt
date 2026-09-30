package com.reef.platform.calcify

import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.*
import reef.contracts.calcify.v1.MatchContextResolvedV1

class MatchContextResolverTest {
    private fun fixture() = Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines()
        .mapIndexed { offset, body -> MatchContextResolver.parseBatch(body, "source", "topic-uuid", 1, 0, offset.toLong()) }

    @Test fun fullFactsAndWireReplay() {
        val batches = fixture()
        val orders = mutableMapOf<String, reef.contracts.calcify.v1.AcceptedOrderSourceV1>()
        val contexts = mutableListOf<MatchContextResolvedV1>()
        batches.forEachIndexed { offset, batch ->
            for (row in batch.acceptedOrders) orders[row.fact.orderId] = MatchContextResolver.mergeAcceptance(orders[row.fact.orderId], row)
            batch.trades.forEachIndexed { ordinal, trade ->
                val passed = CommitmentVerificationPassed(CommitmentId(1, 0, offset.toLong(), ordinal), 1)
                val resolved = MatchContextResolver.resolve(passed, trade, orders[trade.fact.buyOrderId], orders[trade.fact.sellOrderId])
                assertEquals(resolved, MatchContextResolvedV1.parseFrom(resolved.toByteArray()))
                assertEquals(orders[trade.fact.buyOrderId], resolved.buyAcceptedOrder)
                assertEquals(orders[trade.fact.sellOrderId], resolved.sellAcceptedOrder)
                contexts += resolved
            }
        }
        assertEquals(130, contexts.size)
        assertFalse(orders.containsKey("reject"))
        assertEquals("200", orders.getValue("modify-sell").fact.limitPrice)
        assertEquals("100", contexts[1].trade.fact.price.nanos)
        assertEquals("cmd-modify", contexts[1].trade.source.commandId)
        assertEquals("cmd-modify-sell", contexts[1].sellAcceptedOrder.source.commandId)
    }

    @Test fun missingWrongScopeAndLaterAcceptanceFailClosed() {
        val batches=fixture(); val buy=batches[0].acceptedOrders.single();val sell=batches[1].acceptedOrders.single(); val trade=batches[1].trades.single()
        val passed=CommitmentVerificationPassed(CommitmentId(1,0,1,0),1)
        assertFailsWith<IllegalArgumentException> { MatchContextResolver.resolve(passed,trade,null,sell) }
        assertFailsWith<IllegalArgumentException> { MatchContextResolver.resolve(passed,trade,buy,sell.toBuilder().setFact(sell.fact.toBuilder().setRunId("other")).build()) }
        assertFailsWith<IllegalArgumentException> { MatchContextResolver.resolve(passed,trade,buy.toBuilder().setSource(buy.source.toBuilder().setSourceOffset(1).setOutcomeOrdinal(1)).build(),sell) }
        assertFailsWith<IllegalArgumentException> { MatchContextResolver.resolve(passed,trade,buy,sell.toBuilder().setSource(sell.source.toBuilder().setSourcePartition(1)).build()) }
    }

    @Test fun immutableReplayKeepsEarliestProvenanceAndRejectsConflicts() {
        val row=fixture()[0].acceptedOrders.single()
        val replay=row.toBuilder().setSource(row.source.toBuilder().setSourceOffset(9).setBatchId("replay")).build()
        assertEquals(row,MatchContextResolver.mergeAcceptance(row,replay))
        assertEquals(row,MatchContextResolver.mergeAcceptance(replay,row))
        assertFailsWith<IllegalArgumentException> { MatchContextResolver.mergeAcceptance(row,replay.toBuilder().setFact(row.fact.toBuilder().setLimitPrice("101")).build()) }
        assertFailsWith<IllegalArgumentException> { MatchContextResolver.mergeAcceptance(row,replay.toBuilder().setSource(replay.source.toBuilder().setCommandId("new-command")).build()) }
    }

    @Test fun corruptMalformedAndMisroutedSourceFailClosed() {
        val original=Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines().first()
        assertFailsWith<IllegalArgumentException> { MatchContextResolver.parseBatch(original,"source","uuid",1,1,0) }
        assertFailsWith<IllegalArgumentException> { MatchContextResolver.parseBatch(original.replace("rest-buy","changed"),"source","uuid",1,0,0) }
        val altered=original.replace("\"quantityUnits\":\"1000\"","\"quantityUnits\":\"zero\"")
        val corrected=altered.replace(com.reef.platform.api.JsonCodec.parseObject(original).string("payloadChecksum"),com.reef.platform.api.JsonCodec.parseObject(altered).semanticSha256(setOf("createdAt","workFinishedAt","timingChecksum","payloadChecksum","payloadChecksumAlgorithm")))
        assertFailsWith<IllegalArgumentException> { MatchContextResolver.parseBatch(corrected,"source","uuid",1,0,0) }
    }
}
