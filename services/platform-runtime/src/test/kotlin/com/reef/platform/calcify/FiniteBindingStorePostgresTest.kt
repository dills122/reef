package com.reef.platform.calcify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.*

/** Explicit opt-in suite: absent provisioned PG yields real skipped tests, never a pass claim. */
class FiniteBindingStorePostgresTest {
    private fun connection(registrar:Boolean=false):Connection {
        val prefix="CALCIFY_FINITE_POSTGRES_TEST_"
        val url=System.getenv(prefix+"JDBC_URL")
        assumeTrue(!url.isNullOrBlank(),"isolated finite Postgres test fixture not provisioned")
        val kind=if(registrar)"REGISTRAR" else "RUNTIME"
        val user=System.getenv(prefix+kind+"_USER") ?: error("test user missing")
        val file=Path.of(System.getenv(prefix+kind+"_PASSWORD_FILE") ?: error("private test password file missing"))
        val permissions=Files.getPosixFilePermissions(file)
        require(permissions.none{it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_")}){"private test secret permissions"}
        val password=Files.readString(file).trimEnd('\n','\r');require(password.isNotEmpty())
        return DriverManager.getConnection(url,user,password)
    }
    private fun sql(connection:Connection,query:String,vararg parameters:Any?):String = connection.prepareStatement(query).use { ps ->
        parameters.forEachIndexed{i,v->when(v){is ByteArray->ps.setBytes(i+1,v);is Long->ps.setLong(i+1,v);else->ps.setString(i+1,v as String?)}}
        ps.executeQuery().use { r->check(r.next());r.getString(1) }
    }
    private fun opening(publications:Int=16):FiniteOpening {
        val root=FiniteBindingFixtures.opening().json();root.put("brokerClusterId","sql-only-"+UUID.randomUUID())
        (root["budget"] as ObjectNode).put("publications",publications.toString())
        val b=FiniteBindingFixtures.opening().budget.copy(publications=publications)
        root.put("budgetHash",FiniteLifecycleContract.sha(b.canonical(root["profileHash"].textValue(),"calcify-limit-day-v1","p3-run","p3-session","AAPL","USD",FiniteBindingFixtures.opening().parties)))
        return FiniteBindingContract.parseOpening(FiniteBindingContract.encode(root))
    }
    private fun register(o:FiniteOpening) = connection(true).use { assertEquals("t",sql(it,
        "SELECT boundary.calcify_finite_register(?,?::jsonb,?,?)",o.digest,FiniteBindingContract.encode(o.json()).toString(Charsets.UTF_8),o.canonicalBytes(),"sql-test-owner")) }
    private fun snapshot(o:FiniteOpening):ObjectNode=connection().use { FiniteBindingContract.strictObject(sql(it,"SELECT boundary.calcify_finite_snapshot(?)",o.digest).toByteArray(),32768) }
    private fun context(o:FiniteOpening):String=connection(true).use { sql(it,"""
        WITH s AS (SELECT boundary.calcify_finite_snapshot(?) v)
        SELECT jsonb_build_object('activationEpoch',v->'run'->'activationEpoch','recordedAttempts',v->'run'->'recordedAttempts',
          'possiblePublications',v->'run'->'possiblePublications','commandPayloadBytes',v->'run'->'commandPayloadBytes',
          'attemptRecordBytes',v->'run'->'attemptRecordBytes','journalHash',encode(sha256(convert_to((v->'journal')::text,'UTF8')),'hex'),
          'brokerCuts',?::jsonb) FROM s
        """.trimIndent(),o.digest,FiniteBindingContract.encode(FiniteBindingContract.strictObject(FiniteBindingFixtures.witnessBytes(),32768)["cuts"]).toString(Charsets.UTF_8)) }
    private fun stages(o:FiniteOpening,cuts:String)=connection(true).use { sql(it,"SELECT boundary.calcify_finite_stages(?,1,?,?,?::jsonb)",o.digest,"1".repeat(64),"2".repeat(64),cuts) }
    private fun release(o:FiniteOpening,cuts:String)=connection(true).use { sql(it,"SELECT boundary.calcify_finite_release(?,1,?,?,?::jsonb)",o.digest,"1".repeat(64),"2".repeat(64),cuts) }
    private fun activate(o:FiniteOpening) {val cuts=context(o);assertEquals("t",stages(o,cuts));assertEquals("t",release(o,cuts))}
    private fun request(o:FiniteOpening,id:String="sql-command-"+UUID.randomUUID(),refusal:String=""):ObjectNode {
        val body="""{"commandId":"$id","runId":"p3-run","venueSessionId":"p3-session","instrumentId":"AAPL","participantId":"buyer","accountId":"buyer-account","actorId":"buyer","occurredAt":"2026-10-10T03:00:00Z","orderId":"p0-1","side":"BUY","orderType":"LIMIT","quantityUnits":"1","limitPrice":"10","currency":"USD","timeInForce":"DAY"}"""
        val route="/api/v1/orders/submit";val scope="sql-test|$route";val key="$scope|$id";val subject="reef.commands.p00.p3-session.AAPL.SubmitOrder"
        val root=FiniteBindingContract.strictObject("{}".toByteArray(),2)
        listOf("scope" to scope,"clientId" to "sql-test","idempotencyKey" to id,"participantId" to "buyer","commandId" to id,
            "commandType" to "SubmitOrder","route" to route,"subject" to subject,"key" to key,"payload" to body,
            "payloadHash" to FiniteLifecycleContract.sha(body.toByteArray()),"keyHash" to FiniteLifecycleContract.sha(key.toByteArray()),
            "subjectHash" to FiniteLifecycleContract.sha(subject.toByteArray()),"routeHash" to FiniteLifecycleContract.sha(route.toByteArray()),"refusal" to refusal).forEach {root.put(it.first,it.second)}
        root.put("partition",0);root.put("payloadBytes",body.toByteArray().size);return root
    }
    private fun begin(o:FiniteOpening,request:ObjectNode):ObjectNode=connection().use {
        FiniteBindingContract.strictObject(sql(it,"SELECT boundary.calcify_finite_begin_attempt(?,1,?::jsonb)",o.digest,FiniteBindingContract.encode(request).toString(Charsets.UTF_8)).toByteArray(),32768)
    }
    @Test fun permissionsOpeningAndReleaseAreRestricted() {
        val o=opening();register(o)
        connection().use { db ->
            for(q in listOf("SELECT boundary.calcify_finite_register('${o.digest}','{}',''::bytea,'x')",
                "SELECT boundary.calcify_finite_stages('${o.digest}',1,'x','y','{}')",
                "SELECT boundary.calcify_finite_release('${o.digest}',1,'x','y','{}')",
                "UPDATE boundary.calcify_finite_runs SET attempts=0",
                "UPDATE boundary.calcify_finite_bindings SET opening='{}'")) assertFailsWith<SQLException>{db.createStatement().use {it.execute(q)}}
        }
        assertEquals("STOPPED",snapshot(o)["run"]["admissionState"].textValue())
    }
    @Test fun ackRetryConflictRefusalAccountingAndUnknownRestoration() {
        val o=opening();register(o);activate(o);val request=request(o);val id=request["commandId"].textValue()
        assertEquals("RESERVED",begin(o,request)["disposition"].textValue())
        connection().use {assertEquals("t",sql(it,"SELECT boundary.calcify_finite_authorize_send(?,1,?)",o.digest,id))}
        assertEquals("UNRESOLVED",begin(o,request)["disposition"].textValue())
        connection().use {assertEquals("t",sql(it,"SELECT boundary.calcify_finite_unknown(?,?,1)",o.digest,id))}
        assertEquals("STOPPED",snapshot(o)["run"]["admissionState"].textValue())
        assertEquals("UNKNOWN",snapshot(o)["journal"][0]["state"].textValue())
        connection().use {assertEquals("f",sql(it,"SELECT boundary.calcify_finite_authorize_send(?,1,?)",o.digest,id));assertEquals("t",sql(it,"SELECT boundary.calcify_finite_ack(?,?,1,0,1)",o.digest,id))}
        assertEquals("PUBLISHED",begin(o,request)["disposition"].textValue())
        assertEquals("2",snapshot(o)["run"]["recordedAttempts"].textValue()) // stopped read-only replay does not append
        assertEquals("1",snapshot(o)["run"]["possiblePublications"].textValue())
        assertEquals("STOPPED",snapshot(o)["run"]["admissionState"].textValue())
    }
    @Test fun concurrentLastTokenLeavesReservedClosureDrainable() {
        val o=opening(1);register(o);activate(o);val pool=Executors.newFixedThreadPool(2);val barrier=CountDownLatch(1)
        try {
            val requests=listOf(request(o),request(o));val futures=requests.map {q->pool.submit<ObjectNode>{barrier.await();begin(o,q)}}
            barrier.countDown();val decisions=futures.map {it.get(5,TimeUnit.SECONDS)}
            assertEquals(listOf("RESERVED","STOPPED"),decisions.map{it["disposition"].textValue()}.sorted())
            val accepted=decisions.single{it["disposition"].textValue()=="RESERVED"}["commandId"].textValue()
            connection().use {assertEquals("t",sql(it,"SELECT boundary.calcify_finite_authorize_send(?,1,?)",o.digest,accepted));assertEquals("t",sql(it,"SELECT boundary.calcify_finite_ack(?,?,1,0,1)",o.digest,accepted))}
            assertEquals("1",snapshot(o)["run"]["possiblePublications"].textValue());assertEquals("STOPPED",snapshot(o)["run"]["admissionState"].textValue())
        }finally{pool.shutdownNow()}
    }
    @Test fun staleActivationAndStopReceiptCannotReopen() {
        val o=opening();register(o);val old=context(o);assertEquals("t",stages(o,old));assertEquals("t",release(o,old))
        connection().use {assertEquals("t",sql(it,"SELECT boundary.calcify_finite_stop(?,1)",o.digest))}
        assertEquals("f",release(o,old));assertEquals("f",stages(o,old))
        val fresh=context(o);assertEquals("t",stages(o,fresh));assertEquals("t",release(o,fresh))
        assertEquals("2",snapshot(o)["run"]["activationEpoch"].textValue())
    }
    @Test fun refusedAndConflictingAttemptsDoNotPublishAndEpochCannotReset() {
        val o=opening();register(o);activate(o);val q=request(o)
        assertEquals("REFUSED",begin(o,request(o,refusal="SOURCE_POLICY"))["disposition"].textValue())
        assertEquals("RESERVED",begin(o,q)["disposition"].textValue())
        val changed=q.deepCopy();changed.put("payload",q["payload"].textValue().replace("\"10\"","\"11\""));changed.put("payloadHash",FiniteLifecycleContract.sha(changed["payload"].textValue().toByteArray()))
        assertEquals("CONFLICT",begin(o,changed)["disposition"].textValue());assertEquals("3",snapshot(o)["run"]["recordedAttempts"].textValue())
        connection().use {assertEquals("t",sql(it,"SELECT boundary.calcify_finite_stop(?,1)",o.digest));assertEquals("2",sql(it,"SELECT boundary.calcify_finite_claim_owner(?,1,?)",o.digest,"replacement"));assertEquals("f",sql(it,"SELECT boundary.calcify_finite_authorize_send(?,1,?)",o.digest,q["commandId"].textValue()))}
        assertEquals("1",snapshot(o)["run"]["possiblePublications"].textValue());assertEquals("3",snapshot(o)["run"]["recordedAttempts"].textValue())
        assertEquals("RESERVED",snapshot(o)["journal"][0]["state"].textValue()) // unresolved, never inferred absent
    }
    @Test fun malformedSqlRequestsAndNullAckFailClosed() {
        val o=opening();register(o);activate(o)
        for(change in listOf<(ObjectNode)->Unit>({it.remove("payloadHash")},{it.put("unknown",1)},{it.putNull("commandId")},{it.put("partition","0")},{it.put("payloadHash","0".repeat(64))})) {
            val q=request(o);change(q);assertFailsWith<SQLException>{begin(o,q)}
        }
        assertEquals("0",snapshot(o)["run"]["recordedAttempts"].textValue())
        val q=request(o);begin(o,q)
        connection().use {assertEquals("t",sql(it,"SELECT boundary.calcify_finite_authorize_send(?,1,?)",o.digest,q["commandId"].textValue()));assertEquals("f",sql(it,"SELECT boundary.calcify_finite_ack(?,?,1,NULL,1)",o.digest,q["commandId"].textValue()))}
        assertEquals("PUBLISHING",snapshot(o)["journal"][0]["state"].textValue())
    }
    @Test fun durableReceiptRemainsExactAcrossLaterRetry() {
        val o=opening();register(o);activate(o);val q=request(o);val command=q["commandId"].textValue();begin(o,q)
        connection().use {assertEquals("t",sql(it,"SELECT boundary.calcify_finite_authorize_send(?,1,?)",o.digest,command))}
        connection().use {assertFailsWith<SQLException>{sql(it,"SELECT boundary.calcify_finite_receipt(?,?)",o.digest,command)}}
        connection().use {assertEquals("t",sql(it,"SELECT boundary.calcify_finite_ack(?,?,1,0,1)",o.digest,command))}
        val first=connection().use {sql(it,"SELECT boundary.calcify_finite_receipt(?,?)",o.digest,command)}
        assertEquals("PUBLISHED",begin(o,q)["disposition"].textValue())
        val repeated=connection().use {sql(it,"SELECT boundary.calcify_finite_receipt(?,?)",o.digest,command)}
        assertEquals(first,repeated)
        val row=FiniteBindingContract.strictObject(first.toByteArray(),1024)
        val reference=com.reef.platform.api.StreamCommandReference(commandId=command,clientId="sql-test",route="/api/v1/orders/submit",
            idempotencyKey=command,participantId="buyer",subject=q["subject"].textValue(),streamName=o.topic("COMMAND").name,partition=0,streamSequence=1)
        assertEquals(com.reef.platform.api.StreamCommandResponse.acceptedJson(reference,row["acceptedAt"].textValue()),
            com.reef.platform.api.StreamCommandResponse.acceptedJson(reference,row["acceptedAt"].textValue()))
        assertEquals("1",snapshot(o)["run"]["possiblePublications"].textValue())
    }

    @Test fun crossBindingCanonicalIdentityCollisionChargesOnlyConflictAttempt() {
        val first=opening();val second=opening();register(first);register(second);activate(first);activate(second)
        val r=request(first);assertEquals("RESERVED",begin(first,r)["disposition"].textValue())
        assertEquals("CONFLICT",begin(second,r)["disposition"].textValue())
        val snap=snapshot(second);assertEquals("1",snap["run"]["recordedAttempts"].textValue())
        assertEquals("0",snap["run"]["possiblePublications"].textValue());assertEquals(0,snap["journal"].size())
        val fresh=context(second)
        connection(true).use {assertEquals("f",sql(it,"SELECT boundary.calcify_finite_stages(?,NULL,?,?,?::jsonb)",second.digest,"1".repeat(64),"2".repeat(64),fresh))}
    }
    @Test fun actualHttpRetryKeepsDurableReceiptAndAuthScopePrecedesLedger() {
        val o=opening();register(o);activate(o)
        val url=System.getenv("CALCIFY_FINITE_POSTGRES_TEST_JDBC_URL")?:return
        val ds=PGSimpleDataSource().apply {setURL(url);user=System.getenv("CALCIFY_FINITE_POSTGRES_TEST_RUNTIME_USER")
            password=FiniteRuntime.privateBytes(Path.of(System.getenv("CALCIFY_FINITE_POSTGRES_TEST_RUNTIME_PASSWORD_FILE")),8192).toString(Charsets.UTF_8).trimEnd('\n','\r')}
        val store=PostgresFiniteBindingStore(ds);val scheduler=Executors.newSingleThreadScheduledExecutor();var sends=0
        val ingress=FiniteIngress(o,1,store,FiniteCommandPublisher{sends++;java.util.concurrent.CompletableFuture.completedFuture(FiniteBrokerAck(o.topic("COMMAND").name,0,0))},scheduler,1000)
        val clients=mapOf("sql-test" to com.reef.platform.api.StaticTokenClientConfig("buyer-test-token",com.reef.platform.api.ExternalApiPrincipal("sql-test",setOf("buyer"),setOf("buyer"),setOf("buyer-account"))))
        val server=FiniteHttpServer(o,ingress,clients,"service-test-token",java.net.InetSocketAddress("127.0.0.1",0),{error("must not read witness")},{error("must not read capture")})
        val body=request(o);val id=body["commandId"].textValue();val client=java.net.http.HttpClient.newHttpClient()
        fun call(payload:String,token:String="buyer-test-token",path:String="/api/v1/orders/submit"):java.net.http.HttpResponse<String> {
            val req=java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:"+server.port+path))
                .header("X-Client-Id","sql-test").header("Idempotency-Key",id).header("Authorization","Bearer "+token)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build()
            return client.send(req,java.net.http.HttpResponse.BodyHandlers.ofString())
        }
        try {
            server.start();assertEquals(401,call(body["payload"].textValue(),"bad-token").statusCode())
            assertEquals(403,call(body["payload"].textValue().replace("buyer-account","other-account")).statusCode())
            assertEquals(404,call(body["payload"].textValue(),path="/api/v1/orders/other").statusCode())
            assertEquals("0",snapshot(o)["run"]["recordedAttempts"].textValue())
            val first=call(body["payload"].textValue());assertEquals(202,first.statusCode())
            Thread.sleep(20);val retry=call(body["payload"].textValue());assertEquals(202,retry.statusCode());assertEquals(first.body(),retry.body())
            assertEquals(1,sends);assertEquals("2",snapshot(o)["run"]["recordedAttempts"].textValue())
            ingress.stop();val stopped=call(body["payload"].textValue());assertEquals(first.body(),stopped.body());assertEquals(202,stopped.statusCode())
            assertEquals("2",snapshot(o)["run"]["recordedAttempts"].textValue())
            assertEquals(503,call(body["payload"].textValue().replace("\"quantityUnits\":\"1\"","\"quantityUnits\":\"2\"")).statusCode());assertEquals(1,sends)
        }finally{server.close();scheduler.shutdownNow()}
    }

    @Test fun nulPayloadThroughRealHttpAndStoreIsUnchargedBadRequestWhileSqlCastWouldThrow() {
        val o=opening();register(o);activate(o)
        val url=System.getenv("CALCIFY_FINITE_POSTGRES_TEST_JDBC_URL")?:return
        val ds=PGSimpleDataSource().apply {setURL(url);user=System.getenv("CALCIFY_FINITE_POSTGRES_TEST_RUNTIME_USER")
            password=FiniteRuntime.privateBytes(Path.of(System.getenv("CALCIFY_FINITE_POSTGRES_TEST_RUNTIME_PASSWORD_FILE")),8192).toString(Charsets.UTF_8).trimEnd('\n','\r')}
        val store=PostgresFiniteBindingStore(ds);val scheduler=Executors.newSingleThreadScheduledExecutor();var sends=0
        val ingress=FiniteIngress(o,1,store,FiniteCommandPublisher{sends++;java.util.concurrent.CompletableFuture.completedFuture(FiniteBrokerAck(o.topic("COMMAND").name,0,0))},scheduler,1000)
        val clients=mapOf("sql-test" to com.reef.platform.api.StaticTokenClientConfig("buyer-test-token",com.reef.platform.api.ExternalApiPrincipal("sql-test",setOf("buyer"),setOf("buyer"),setOf("buyer-account"))))
        val server=FiniteHttpServer(o,ingress,clients,"service-test-token",java.net.InetSocketAddress("127.0.0.1",0),{error("must not read witness")},{error("must not read capture")})
        val good=request(o);val payload=good["payload"].textValue();check(payload.endsWith("}"))
        val client=java.net.http.HttpClient.newHttpClient()
        fun call(id:String,body:String):java.net.http.HttpResponse<String> = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:"+server.port+"/api/v1/orders/submit"))
            .header("X-Client-Id","sql-test").header("Idempotency-Key",id).header("Authorization","Bearer buyer-test-token")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString())
        try {
            // The unguarded SQL cast really does throw: this is what the Kotlin guard protects the run from.
            val raw=request(o);raw.put("payload",payload.dropLast(1)+",\"traceId\":\"a\\u0000\"}")
            assertFails{store.beginAttempt(o.digest,1,raw)}
            assertEquals("0",snapshot(o)["run"]["recordedAttempts"].textValue())
            server.start()
            val nul=call(good["commandId"].textValue(),payload.dropLast(1)+",\"traceId\":\"a\\u0000\"}")
            assertEquals(400,nul.statusCode())
            assertEquals("0",snapshot(o)["run"]["recordedAttempts"].textValue());assertEquals("ACTIVE",snapshot(o)["run"]["admissionState"].textValue());assertEquals(0,sends)
            assertEquals(202,call(good["commandId"].textValue(),payload).statusCode());assertEquals(1,sends)
        }finally{server.close();scheduler.shutdownNow()}
    }

    @Test fun unresolvedStoppedRestorePreservesKnownReceiptAndCannotStageOrAdmit() {
        val o=opening();register(o);activate(o);val known=request(o);val unknown=request(o)
        for(q in listOf(known,unknown)){begin(o,q);connection().use{assertEquals("t",sql(it,"SELECT boundary.calcify_finite_authorize_send(?,1,?)",o.digest,q["commandId"].textValue()))}}
        connection().use{assertEquals("t",sql(it,"SELECT boundary.calcify_finite_ack(?,?,1,0,1)",o.digest,known["commandId"].textValue()));assertEquals("t",sql(it,"SELECT boundary.calcify_finite_unknown(?,?,1)",o.digest,unknown["commandId"].textValue()))}
        val before=snapshot(o)
        connection().use{assertEquals("2",sql(it,"SELECT boundary.calcify_finite_claim_owner(?,1,?)",o.digest,"restored-stopped-instance"))}
        val restored=snapshot(o);assertEquals("STOPPED",restored["run"]["admissionState"].textValue());assertEquals("1",restored["run"]["unresolvedPublications"].textValue())
        assertFails{FiniteCaptureProof.activationReady(restored)}
        val store=PGSimpleDataSource().apply{setURL(System.getenv("CALCIFY_FINITE_POSTGRES_TEST_JDBC_URL"));user=System.getenv("CALCIFY_FINITE_POSTGRES_TEST_RUNTIME_USER")
            password=FiniteRuntime.privateBytes(Path.of(System.getenv("CALCIFY_FINITE_POSTGRES_TEST_RUNTIME_PASSWORD_FILE")),8192).toString(Charsets.UTF_8).trimEnd('\n','\r')}.let(::PostgresFiniteBindingStore)
        assertEquals("STOPPED",store.beginAttempt(o.digest,2,request(o)).disposition)
        assertEquals("PUBLISHED",store.beginAttempt(o.digest,2,known).disposition)
        assertFalse(store.authorizeSend(o.digest,2,unknown["commandId"].textValue()))
        val cuts=context(o)
        connection(true).use{assertEquals("f",sql(it,"SELECT boundary.calcify_finite_stages(?,2,?,?,?::jsonb)",o.digest,"1".repeat(64),"2".repeat(64),cuts));assertEquals("f",sql(it,"SELECT boundary.calcify_finite_release(?,2,?,?,?::jsonb)",o.digest,"1".repeat(64),"2".repeat(64),cuts))}
        val after=snapshot(o)
        for(key in listOf("recordedAttempts","possiblePublications","commandPayloadBytes","attemptRecordBytes","unresolvedPublications"))assertEquals(before["run"][key],after["run"][key])
        assertEquals(before["journal"],after["journal"])
    }
}
