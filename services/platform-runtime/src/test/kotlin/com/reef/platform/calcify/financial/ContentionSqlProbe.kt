package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.DriverManager

/** Synthetic gross decisions and atomic SQL journal, solely a co-resident contention workload.
 * No production financial authority or mapping from the capture stream is claimed. */
object ContentionSqlProbe {
    private val json = ObjectMapper()
    private const val domainSize = 10000L
    private fun node(value: Any): JsonNode = json.valueToTree(value)
    private fun emit(value: Any) = println(json.writeValueAsString(value))
    private fun owner() = FinancialKernel(node(mapOf("buyerCash" to "100000000000", "sellerCash" to "0",
        "buyerShares" to "0", "sellerShares" to domainSize.toString())), node(mapOf("policy" to "gross-p1")), false)
    private fun action(id: Long, kind: String): JsonNode {
        val payload = if (kind == "CAPTURE") mapOf("executionId" to "trade-$id", "runId" to "domain-${id / domainSize}",
            "venueSessionId" to "contention", "instrumentId" to "ACME", "buyOrderId" to "b-$id", "sellOrderId" to "s-$id",
            "buyerAccount" to "buyer", "sellerAccount" to "seller", "cashAsset" to "USD_NANO", "securityAsset" to "ACME_SHARE",
            "quantity" to "1", "priceNanos" to "10000000", "dueTick" to "0")
        else mapOf("executionId" to "trade-$id", "runId" to "domain-${id / domainSize}", "venueSessionId" to "contention",
            "instrumentId" to "ACME", "attempt" to "1")
        return node(mapOf("namespace" to "contention", "domain" to "domain-${id / domainSize}",
            "actionId" to "$id-$kind", "kind" to kind, "payload" to payload))
    }
    private fun restore(db: Connection, domain: Long): FinancialKernel {
        val records = mutableListOf<JsonNode>()
        db.prepareStatement("SELECT body FROM history WHERE domain=? ORDER BY seq").use { q ->
            q.setLong(1, domain); q.executeQuery().use { rows -> while (rows.next()) records.add(json.readTree(rows.getString(1))) }
        }
        require(records.isNotEmpty())
        return FinancialKernel.replay(records, false)
    }
    private fun audit(db: Connection, next: Long) {
        var domains = 0L
        db.createStatement().use { q -> q.executeQuery("SELECT DISTINCT domain FROM history ORDER BY domain").use { rows ->
            while (rows.next()) {
                val domain = rows.getLong(1); val n = minOf(domainSize, next - domain * domainSize)
                require(n in 1..domainSize)
                val kernel = restore(db, domain); val balances = kernel.ownerView()["balances"]
                check(kernel.historySequence() == n * 2 + 1)
                val expected = mapOf("buyerCash" to 100000000000L - n * 10000000, "sellerCash" to n * 10000000,
                    "buyerShares" to n, "sellerShares" to domainSize - n)
                expected.forEach { (key, value) -> check(balances[key].asText() == value.toString()) }
                db.prepareStatement("SELECT asset,account,amount FROM balances WHERE domain=?").use { s ->
                    s.setLong(1,domain); s.executeQuery().use { b ->
                        var count=0
                        while(b.next()) {
                            val suffix=if(b.getString(1)=="USD_NANO") "Cash" else "Shares"
                            check(b.getBigDecimal(3).toBigIntegerExact().toString()==balances[b.getString(2)+suffix].asText()); count++
                        }
                        check(count==6)
                    }
                }; domains++
            }
        } }
        db.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM history").use { it.next(); check(it.getLong(1)==next*2+domains) }
            s.executeQuery("SELECT count(*) FROM legs").use { it.next(); check(it.getLong(1)==next*4+domains*6) }
            s.executeQuery("SELECT asset,sum(amount) FROM legs GROUP BY asset").use { while(it.next()) check(it.getBigDecimal(2).signum()==0) }
        }
        emit(mapOf("auditPass" to true,"committedTrades" to next,"domains" to domains,"fullHistoryReplay" to true,"sqlBalancesMatch" to true))
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size==5) { "run|audit jdbc-url schema duration-seconds rate" }
        val mode=args[0]; val schema=args[2]; val seconds=args[3].toLong(); val rate=args[4].toLong()
        require(mode in setOf("run","audit") && schema.matches(Regex("calcify_contention_[a-z0-9_]+")))
        require(seconds in 1..1800 && rate in 1..10000)
        DriverManager.getConnection(args[1],"reef","reef").use { db ->
            db.createStatement().use { s ->
                s.execute("CREATE SCHEMA IF NOT EXISTS $schema"); s.execute("SET search_path TO $schema")
                s.execute("CREATE TABLE IF NOT EXISTS history(domain bigint,seq bigint,body text NOT NULL,PRIMARY KEY(domain,seq))")
                s.execute("CREATE TABLE IF NOT EXISTS legs(domain bigint,seq bigint,ordinal int,asset text,account text,amount numeric NOT NULL,PRIMARY KEY(domain,seq,ordinal))")
                s.execute("CREATE TABLE IF NOT EXISTS balances(domain bigint,asset text,account text,amount numeric NOT NULL,PRIMARY KEY(domain,asset,account))")
                s.execute("CREATE TABLE IF NOT EXISTS checkpoint(id int PRIMARY KEY CHECK(id=1),next_trade bigint NOT NULL)")
                s.execute("INSERT INTO checkpoint VALUES(1,0) ON CONFLICT DO NOTHING")
            }
            var next = db.createStatement().use { s -> s.executeQuery("SELECT next_trade FROM checkpoint WHERE id=1").use { it.next();it.getLong(1) } }
            if(mode=="audit") { audit(db,next);return }
            val start=System.nanoTime(); val initial=next
            var domain=if(next==0L) -1 else (next-1)/domainSize
            var kernel: FinancialKernel?=if(domain<0) null else restore(db,domain)
            emit(mapOf("ready" to true,"nextTrade" to next,"restoreMs" to (System.nanoTime()-start)/1000000,"domain" to domain))
            db.autoCommit=false
            val history=db.prepareStatement("INSERT INTO history VALUES(?,?,?)")
            val legs=db.prepareStatement("INSERT INTO legs VALUES(?,?,?,?,?,?)")
            val balances=db.prepareStatement("INSERT INTO balances VALUES(?,?,?,?) ON CONFLICT(domain,asset,account) DO UPDATE SET amount=balances.amount+EXCLUDED.amount")
            val checkpoint=db.prepareStatement("UPDATE checkpoint SET next_trade=? WHERE id=1")
            fun record(record: JsonNode) {
                val seq=record["sequence"].asLong()
                history.setLong(1,domain);history.setLong(2,seq);history.setString(3,record.toString());history.addBatch()
                var ordinal=0
                record["journalGroups"].elements().forEachRemaining { group -> group.forEach { leg ->
                    legs.setLong(1,domain);legs.setLong(2,seq);legs.setInt(3,ordinal++);legs.setString(4,leg["asset"].asText());legs.setString(5,leg["account"].asText());legs.setBigDecimal(6,leg["amount"].asText().toBigDecimal());legs.addBatch()
                    balances.setLong(1,domain);balances.setString(2,leg["asset"].asText());balances.setString(3,leg["account"].asText());balances.setBigDecimal(4,leg["amount"].asText().toBigDecimal());balances.addBatch()
                } }
            }
            var reported=start
            while(System.nanoTime()-start < seconds*1000000000L) {
                repeat(25) {
                    if(next/domainSize!=domain) {domain=next/domainSize;kernel=owner();record(kernel!!.lastRecord()!!)}
                    check(kernel!!.execute(action(next,"CAPTURE"),false)["disposition"].asText()=="CAPTURED");record(kernel!!.lastRecord()!!)
                    check(kernel!!.execute(action(next,"SETTLE"),false)["disposition"].asText()=="SETTLED");record(kernel!!.lastRecord()!!)
                    next++
                }
                history.executeBatch();legs.executeBatch();balances.executeBatch();checkpoint.setLong(1,next);check(checkpoint.executeUpdate()==1);db.commit()
                val now=System.nanoTime()
                if(now-reported>=1000000000L) { emit(mapOf("progress" to true,"committedTrades" to next,"elapsedMs" to (now-start)/1000000,"domain" to domain));reported=now }
                val remaining=(next-initial)*1000000000L/rate-(System.nanoTime()-start)
                if(remaining>0) Thread.sleep(remaining/1000000, (remaining%1000000).toInt())
            }
            emit(mapOf("finished" to true,"committedTrades" to next))
        }
    }
}
