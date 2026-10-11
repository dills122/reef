package com.reef.platform.calcify

import com.reef.platform.api.ExternalApiPrincipal
import com.reef.platform.api.StaticTokenClientConfig
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.postgresql.ds.PGSimpleDataSource

/** Sole opt-in finite ingress/capture JVM. Registrar credentials are never loaded. */
internal class FiniteRuntime private constructor(private val opening:FiniteOpening,private val store:FiniteBindingStore,
    private val ingressKafka:FiniteKafka,private val captureKafka:FiniteKafka,private val ingress:FiniteIngress,
    private val scheduler:java.util.concurrent.ScheduledExecutorService,private val capture:FiniteCaptureLive,
    private val http:FiniteHttpServer):AutoCloseable {
    fun start(){capture.start();http.start()}
    override fun close(){
        runCatching{ingress.stop()};http.close()
        // Producer close completes real callbacks before managed capture/runtime exit.
        ingressKafka.close();capture.close();captureKafka.close();scheduler.shutdownNow()
    }
    companion object {
        fun create(lookup:(String)->String?=System::getenv,observer:FiniteIngressObserver=FiniteIngressObserver{_,_,_->},
            captureObserver:(reef.contracts.calcify.v1.FiniteLifecycleStateV1)->Unit={}):FiniteRuntime {
            fun required(key:String)=lookup(key)?.takeIf{it.isNotBlank()}?:error("missing $key")
            fun secret(name:String):String {
                require(lookup(name).isNullOrBlank()){"inline finite credential forbidden"}
                return privateBytes(Path.of(required(name+"_FILE")),8192).toString(Charsets.UTF_8).trimEnd('\n','\r').also{require(it.isNotBlank())}
            }
            val digest=required("CALCIFY_FINITE_BINDING_DIGEST");require(digest.matches(Regex("[0-9a-f]{64}")))
            val ds=PGSimpleDataSource().apply {
                setURL(required("CALCIFY_FINITE_POSTGRES_JDBC_URL"));user=required("CALCIFY_FINITE_POSTGRES_USER")
                password=secret("CALCIFY_FINITE_POSTGRES_PASSWORD");connectTimeout=5;socketTimeout=10
            }
            val store=PostgresFiniteBindingStore(ds);val first=store.snapshot(digest)
            val opening=FiniteBindingContract.parseOpening(FiniteBindingContract.encode(first["opening"]))
            require(opening.digest==digest && first["run"]["admissionState"].textValue()=="STOPPED"){"finite startup requires registered stopped binding"}
            val bootstrap=required("CALCIFY_FINITE_KAFKA_BOOTSTRAP_SERVERS")
            val protocol=lookup("CALCIFY_FINITE_KAFKA_SECURITY_PROTOCOL")?:"SASL_PLAINTEXT"
            val ingressUser=required("CALCIFY_FINITE_KAFKA_USERNAME");val captureUser=required("CALCIFY_FINITE_CAPTURE_KAFKA_USERNAME")
            require(ingressUser!=captureUser){"finite ingress/capture principals must differ"}
            require(lookup("CALCIFY_FINITE_CLIENT_TOKEN").isNullOrBlank()){"inline finite client credential forbidden"}
            val ingressKafka=FiniteKafka(opening,bootstrap,ingressUser,secret("CALCIFY_FINITE_KAFKA_PASSWORD"),protocol)
            var captureKafka:FiniteKafka?=null;var scheduler:java.util.concurrent.ScheduledExecutorService?=null;var live:FiniteCaptureLive?=null
            try {
                captureKafka=FiniteKafka(opening,bootstrap,captureUser,secret("CALCIFY_FINITE_CAPTURE_KAFKA_PASSWORD"),protocol)
                ingressKafka.verifyIdentities();captureKafka.verifyIdentities()
                ds.connection.use {connection->
                    connection.prepareStatement("SELECT source_topic,source_topic_id FROM runtime.calcify_source_generations WHERE source_generation=?").use {query->
                        query.setInt(1,opening.generation);query.executeQuery().use{rows->require(rows.next() && rows.getString(1)==opening.topic("SOURCE").name && rows.getString(2)==opening.topic("SOURCE").id){"finite source generation registry mismatch"}}
                    }
                    connection.prepareStatement("SELECT role,topic_name,topic_id FROM runtime.calcify_topic_identities WHERE source_generation=?").use {query->
                        query.setInt(1,opening.generation);query.executeQuery().use{rows->
                            val found=mutableSetOf<String>();while(rows.next()) {val role=rows.getString(1)
                                if(role in FiniteBindingContract.roles && role!="SOURCE") {val topic=opening.topic(role);require(rows.getString(2)==topic.name && rows.getString(3)==topic.id);found.add(role)}
                            };require(found==FiniteBindingContract.roles.filter{it!="SOURCE"}.toSet()){"finite topic identity registry incomplete"}
                        }
                    }
                }
                verifyPublished(opening,first,ingressKafka)
                val epoch=store.claimOwner(digest,first["run"]["ownerEpoch"].textValue().toLong(),required("CALCIFY_FINITE_OWNER_INSTANCE_ID"))
                require(epoch>0){"finite owner CAS refused"}
                val clients=clientTokens(privateBytes(Path.of(required("CALCIFY_FINITE_CLIENT_TOKEN_FILE")),8192),opening)
                val serviceToken=secret("CALCIFY_FINITE_SERVICE_TOKEN")
                val dir=Path.of(required("CALCIFY_FINITE_STATE_DIRECTORY"));require(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir)){"private managed directory required"}
                val timeout=(lookup("CALCIFY_FINITE_ACK_TIMEOUT_MS")?:"1000").toLong();require(timeout in 10..30000)
                scheduler=Executors.newSingleThreadScheduledExecutor()
                val ingress=FiniteIngress(opening,epoch,store,ingressKafka.commandPublisher(),scheduler,timeout,observer)
                val capture=FiniteCaptureLive(opening,store,captureKafka,dir,{runCatching{ingress.stop()}},captureObserver)
                live=capture
                fun witness(nonce:String):com.fasterxml.jackson.databind.node.ObjectNode {
                    val snapshot=store.snapshot(digest);FiniteCaptureProof.activationReady(snapshot)
                    val cuts=ingressKafka.cuts()
                    verifyPublished(opening,snapshot,ingressKafka)
                    val root=FiniteBindingContract.strictObject("{}".toByteArray(),2)
                    root.put("schema","calcify-finite-startup-v1");root.put("nonce",nonce);root.put("bindingDigest",digest)
                    root.set<com.fasterxml.jackson.databind.JsonNode>("opening",snapshot["opening"]);root.set<com.fasterxml.jackson.databind.JsonNode>("run",snapshot["run"])
                    root.set<com.fasterxml.jackson.databind.JsonNode>("journal",snapshot["journal"]);root.set<com.fasterxml.jackson.databind.JsonNode>("cuts",cuts)
                    require(store.snapshot(digest)==snapshot && ingressKafka.cuts()==cuts){"finite startup proof changed during read"}
                    FiniteBindingContract.parseStartup(FiniteBindingContract.encode(root),digest,nonce)
                    return root
                }
                val port=(lookup("CALCIFY_FINITE_HTTP_PORT")?:"8080").toInt();require(port in 1..65535)
                val http=FiniteHttpServer(opening,ingress,clients,serviceToken,InetSocketAddress(lookup("CALCIFY_FINITE_HTTP_BIND_ADDRESS")?:"127.0.0.1",port),::witness,capture::readiness)
                return FiniteRuntime(opening,store,ingressKafka,captureKafka,ingress,scheduler,capture,http)
            }catch(ex:Exception){live?.close();scheduler?.shutdownNow();captureKafka?.close();ingressKafka.close();throw ex}
        }
        fun run() {
            val runtime=create();Runtime.getRuntime().addShutdownHook(Thread{runtime.close()})
            try {runtime.start();CountDownLatch(1).await()}catch(ex:Exception){runtime.close();throw ex}
        }
        private fun verifyPublished(opening:FiniteOpening,snapshot:com.fasterxml.jackson.databind.node.ObjectNode,kafka:FiniteKafka) {
            val cuts=kafka.cuts();val range=cuts["ranges"].single{it["role"].textValue()=="COMMAND"}
            require(range["beginningOffset"].textValue()=="0"){"finite command genesis lost"}
            val records=kafka.read("COMMAND",0,range["readCommittedEndOffset"].textValue().toLong(),opening.budget.publications,
                opening.budget.publications.toLong()*opening.budget.commandBytes)
            val published=snapshot["journal"].filter{it["state"].textValue()=="PUBLISHED"}
            for(entry in published) {
                val record=records.singleOrNull{it.offset().toString()==entry["offset"].textValue()}?:error("finite published command absent")
                require(record.value()!=null && record.key()!=null && FiniteLifecycleContract.sha(record.value())==entry["payloadHash"].textValue() &&
                    FiniteLifecycleContract.sha(record.key())==entry["keyHash"].textValue()){"finite published bytes mismatch"}
                val headers=record.headers().headers("reef-stream-subject").toList();require(headers.size==1 && headers.single().value()!=null)
                require(FiniteLifecycleContract.sha(headers.single().value())==entry["subjectHash"].textValue())
                val body=FiniteBindingContract.strictObject(record.value(),opening.budget.commandBytes)
                require(body["commandId"].textValue()==entry["commandId"].textValue() && body["runId"].textValue()==opening.run &&
                    body["venueSessionId"].textValue()==opening.session && body["instrumentId"].textValue()==opening.instrument &&
                    opening.parties.any{it.participant==body["participantId"]?.textValue() && it.account==body["accountId"]?.textValue()})
                val route=when(entry["commandType"].textValue()) {"SubmitOrder"->"/api/v1/orders/submit";"ModifyOrder"->"/api/v1/orders/modify";"CancelOrder"->"/api/v1/orders/cancel";else->error("finite command type")}
                require(FiniteLifecycleContract.sha(route.toByteArray())==entry["routeHash"].textValue())
            }
            if(snapshot["run"]["admissionState"].textValue()=="STOPPED" && snapshot["run"]["unresolvedPublications"].textValue()=="0")
                require(records.size==published.size){"finite extra committed command record"}
        }
        internal fun privateBytes(path:Path,cap:Int):ByteArray {
            require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)){"finite private regular file required"}
            require(Files.getPosixFilePermissions(path).all{it==PosixFilePermission.OWNER_READ || it==PosixFilePermission.OWNER_WRITE}){"finite credential file permissions"}
            require(Files.size(path) in 1..cap.toLong());return Files.newInputStream(path).use{it.readNBytes(cap+1)}.also{require(it.size<=cap)}
        }
        internal fun clientTokens(bytes:ByteArray,opening:FiniteOpening):Map<String,StaticTokenClientConfig> {
            val root=FiniteBindingContract.strictObject(bytes,8192);FiniteBindingContract.fields(root,listOf("schema","clients"))
            require(root["schema"].textValue()=="calcify-finite-client-auth-v1" && root["clients"].isArray && root["clients"].size()==2)
            val seenTokens=mutableSetOf<String>();val seenParties=mutableSetOf<Pair<String,String>>()
            val result=linkedMapOf<String,StaticTokenClientConfig>()
            root["clients"].forEach {client->
                FiniteBindingContract.fields(client,listOf("clientId","token","actorId","participantId","accountId"))
                fun t(k:String)=FiniteBindingContract.text(client,k,if(k=="token")1024 else 128)
                val id=t("clientId");val token=t("token");val actor=t("actorId");val participant=t("participantId");val account=t("accountId")
                require(opening.parties.any{it.participant==participant && it.account==account} && seenParties.add(participant to account) && seenTokens.add(token) && !result.containsKey(id))
                result[id]=StaticTokenClientConfig(token,ExternalApiPrincipal(id,setOf(actor),setOf(participant),setOf(account)))
            };return result
        }
    }
}
