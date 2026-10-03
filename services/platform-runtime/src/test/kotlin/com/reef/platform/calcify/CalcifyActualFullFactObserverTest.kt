package com.reef.platform.calcify

import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.*
import reef.contracts.calcify.v1.AcceptedOrderSourceV1

class CalcifyActualFullFactObserverTest {
    @Test fun independentDecoderCoversActualRestingPartialFillModifyAndMultiTradeFacts() {
        val orders=mutableMapOf<Pair<String,String>,AcceptedOrderSourceV1>();var count=0
        val rows=CalcifySourceFixtures.currentBodies()
        rows.forEachIndexed {offset,body ->
            val decoded=CalcifyActualFullFactObserver.decode(body.toByteArray(),"source","uuid",1,0,offset.toLong())
            val control=MatchContextResolver.parseBatch(body,"source","uuid",1,0,offset.toLong())
            assertEquals(control.acceptedOrders,decoded.orders);assertEquals(control.trades,decoded.trades)
            decoded.orders.forEach {orders.putIfAbsent(it.fact.runId to it.fact.orderId,it)}
            decoded.trades.forEachIndexed {ordinal,trade ->
                val id=CommitmentId(1,0,offset.toLong(),ordinal)
                val buy=orders.getValue(trade.runId to trade.fact.buyOrderId);val sell=orders.getValue(trade.runId to trade.fact.sellOrderId)
                val independent=CalcifyActualFullFactObserver.context(id,1,trade,buy,sell)
                assertEquals(MatchContextResolver.resolve(CommitmentVerificationPassed(id,1),trade,buy,sell),independent)
                // Full protobuf equality includes non-identity fields omitted by lightweight counter.
                assertNotEquals(independent,independent.toBuilder().setBuyAcceptedOrder(buy.toBuilder().setFact(buy.fact.toBuilder().setAccountId("mutated-account"))).build())
                count++
            }
        }
        assertEquals(130,count)
        assertEquals("200",orders.getValue("run-a" to "modify-sell").fact.limitPrice)
        assertFalse(orders.containsKey("run-a" to "reject"))
    }

    @Test fun independentDecoderRejectsCorruptOrMisroutedSource() {
        val body=CalcifySourceFixtures.currentBodies().first()
        assertFailsWith<IllegalArgumentException> {CalcifyActualFullFactObserver.decode(body.replace("rest-buy","changed").toByteArray(),"source","uuid",1,0,0)}
        assertFailsWith<IllegalArgumentException> {CalcifyActualFullFactObserver.decode(body.toByteArray(),"source","uuid",1,1,0)}
    }
    private val mapper=com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
    private fun changed(body:String,edit:(com.fasterxml.jackson.databind.node.ObjectNode)->Unit):String {
        val root=mapper.readTree(body) as com.fasterxml.jackson.databind.node.ObjectNode
        edit(root)
        return CalcifySourceFixtures.rechecksum(root.toString())
    }
    private fun decoded(body:String,offset:Long)=CalcifyActualFullFactObserver.decode(body.toByteArray(),"source","uuid",1,0,offset)

    @Test fun legacySubmitFallbackWorksButMissingModifyRunFailsClosed() {
        val historical=Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines()
        val submit=historical.first {body->mapper.readTree(body).get("outcomes").any {it.get("commandType").asText()=="SubmitOrder" && (it.get("result").get("trades")?.size() ?: 0)>0}}
        assertEquals("run-a",decoded(submit,1).trades.single().runId)
        val modify=CalcifySourceFixtures.currentBodies().first {body->mapper.readTree(body).get("outcomes").any {it.get("commandType").asText()=="ModifyOrder" && (it.get("result").get("trades")?.size() ?: 0)>0}}
        val missing=changed(modify) {root->root.get("outcomes").forEach {row->if(row.get("commandType").asText()=="ModifyOrder") (row as com.fasterxml.jackson.databind.node.ObjectNode).remove("runId")}}
        assertFailsWith<IllegalArgumentException> {decoded(missing,2)}
    }

    @Test fun reusedOrderIdsStayRunScopedAndAmbiguousStringKeysDoNotCollide() {
        val bodies=CalcifySourceFixtures.currentBodies().take(2)
        val runs=listOf("run-a","run-b").associateWith {run->bodies.mapIndexed {offset,body->decoded(CalcifySourceFixtures.rechecksum(body.replace("\"run-a\"","\"$run\"")),offset.toLong())}}
        val orders=mutableMapOf<Pair<String,String>,AcceptedOrderSourceV1>()
        runs.values.flatten().flatMap {it.orders}.forEach {orders[it.fact.runId to it.fact.orderId]=it}
        assertEquals(4,orders.size)
        for((run,batches) in runs) {
            val trade=batches[1].trades.single();val id=CommitmentId(1,0,1,0)
            val buy=orders.getValue(run to trade.fact.buyOrderId);val sell=orders.getValue(run to trade.fact.sellOrderId)
            assertEquals(run,CalcifyActualFullFactObserver.context(id,1,trade,buy,sell).runId)
            val other=if(run=="run-a") "run-b" else "run-a"
            assertFailsWith<IllegalArgumentException> {CalcifyActualFullFactObserver.context(id,1,trade,orders.getValue(other to trade.fact.buyOrderId),orders.getValue(other to trade.fact.sellOrderId))}
        }
        assertFalse(CalcifyActualFullFactObserver.acceptanceKey(0,"a:b","c").contentEquals(CalcifyActualFullFactObserver.acceptanceKey(0,"a","b:c")))
        assertFalse(CalcifyActualFullFactObserver.acceptanceKey(0,"run-a","same").contentEquals(CalcifyActualFullFactObserver.acceptanceKey(0,"run-b","same")))
    }

    @Test fun hiddenLimitMapsToLimitAndAuthoritativeRunConflictFails() {
        val body=CalcifySourceFixtures.currentBodies().first()
        val hidden=changed(body) {root->(root.get("outcomes").first().get("result").get("acceptedOrder") as com.fasterxml.jackson.databind.node.ObjectNode).put("orderType","LIMIT_HIDDEN")}
        assertEquals(reef.contracts.orderexecution.v1.OrderType.ORDER_TYPE_LIMIT,decoded(hidden,0).orders.single().fact.orderType)
        val conflict=changed(body) {root->(root.get("outcomes").first() as com.fasterxml.jackson.databind.node.ObjectNode).put("runId","other-run")}
        assertFailsWith<IllegalArgumentException> {decoded(conflict,0)}
        val row=decoded(body,0).orders.single();val replay=row.toBuilder().setSource(row.source.toBuilder().setSourceOffset(5)).build()
        assertEquals(row,CalcifyActualFullFactObserver.retainAcceptance(row,replay))
        assertFailsWith<IllegalArgumentException> {CalcifyActualFullFactObserver.retainAcceptance(row,replay.toBuilder().setFact(row.fact.toBuilder().setQuantityUnits("different")).build())}
    }

}
