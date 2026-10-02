package com.reef.platform.calcify

import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.*
import reef.contracts.calcify.v1.AcceptedOrderSourceV1

class CalcifyActualFullFactObserverTest {
    @Test fun independentDecoderCoversActualRestingPartialFillModifyAndMultiTradeFacts() {
        val orders=mutableMapOf<String,AcceptedOrderSourceV1>();var count=0
        val rows=Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines()
        rows.forEachIndexed {offset,body ->
            val decoded=CalcifyActualFullFactObserver.decode(body.toByteArray(),"source","uuid",1,0,offset.toLong())
            val control=MatchContextResolver.parseBatch(body,"source","uuid",1,0,offset.toLong())
            assertEquals(control.acceptedOrders,decoded.orders);assertEquals(control.trades,decoded.trades)
            decoded.orders.forEach {orders.putIfAbsent(it.fact.orderId,it)}
            decoded.trades.forEachIndexed {ordinal,trade ->
                val id=CommitmentId(1,0,offset.toLong(),ordinal)
                val buy=orders.getValue(trade.fact.buyOrderId);val sell=orders.getValue(trade.fact.sellOrderId)
                val independent=CalcifyActualFullFactObserver.context(id,1,trade,buy,sell)
                assertEquals(MatchContextResolver.resolve(CommitmentVerificationPassed(id,1),trade,buy,sell),independent)
                // Full protobuf equality includes non-identity fields omitted by lightweight counter.
                assertNotEquals(independent,independent.toBuilder().setBuyAcceptedOrder(buy.toBuilder().setFact(buy.fact.toBuilder().setAccountId("mutated-account"))).build())
                count++
            }
        }
        assertEquals(130,count)
        assertEquals("200",orders.getValue("modify-sell").fact.limitPrice)
        assertFalse(orders.containsKey("reject"))
    }

    @Test fun independentDecoderRejectsCorruptOrMisroutedSource() {
        val body=Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines().first()
        assertFailsWith<IllegalArgumentException> {CalcifyActualFullFactObserver.decode(body.replace("rest-buy","changed").toByteArray(),"source","uuid",1,0,0)}
        assertFailsWith<IllegalArgumentException> {CalcifyActualFullFactObserver.decode(body.toByteArray(),"source","uuid",1,1,0)}
    }
}
