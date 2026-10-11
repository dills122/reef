package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import reef.contracts.calcify.v1.FiniteLifecycleStateV1
import reef.contracts.orderexecution.v1.OrderSide

/** Test-only qualification. Transport runs LAST; synthetic bytes never represent typed authority. */
object FiniteManagedStateTransportProbe {
    private fun json() = FiniteBindingContract.strictObject("{}".toByteArray(),2)
    internal fun payload() = ByteArray(6307840) { (it % 251).toByte() }
    internal fun healthyInitial(binding:FiniteLifecycleBinding,existing:FiniteLifecycleStateV1?):FiniteLifecycleStateV1 {
        val genesis=FiniteLifecycleContract.genesis(binding)
        require(existing==genesis){"healthy requires exact restored ordinary genesis"}
        return requireNotNull(existing)
    }
    internal fun preParserCheck(binding:FiniteLifecycleBinding,restore:Boolean,expectedPrior:String,bytes:ByteArray){
        require(FiniteLifecycleContract.sha(bytes)==expectedPrior){"prior managed checkpoint mismatch"}
        if(restore)require(bytes.size==binding.budget.stateBytes.toInt())
        else {val typed=FiniteLifecycleStateV1.parseFrom(bytes);FiniteLifecycleReducer.validateState(typed,binding)
            require(typed.completedRecordsCount==8 && typed.retainedSuffixCount==7 && typed.fault.isNotEmpty())}
    }
    private fun write(path:Path,bytes:ByteArray) {
        Files.newByteChannel(path,setOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            val buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer)
        }
    }
    private fun gate(path:Path) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)){check(System.nanoTime()<end){"qualification gate expired"};Thread.sleep(10)}
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    }
    internal fun rawTopology(binding:FiniteLifecycleBinding,restore:Boolean,expectedPrior:String,
                             observe:(ByteArray)->Unit,initialized:()->Unit = {}):Topology = Topology().apply {
        addSource("finite-source",ByteArrayDeserializer(),ByteArrayDeserializer(),binding.sourceTopic)
        addProcessor("capture",{object:Processor<ByteArray,ByteArray,ByteArray,ByteArray>{
            private lateinit var context:ProcessorContext<ByteArray,ByteArray>
            private lateinit var store:KeyValueStore<String,ByteArray>
            override fun init(context:ProcessorContext<ByteArray,ByteArray>){
                this.context=context;store=context.getStateStore(FiniteLifecycleCaptureProcessor.STORE)
                require(context.taskId().partition()==binding.partition)
                val bytes=requireNotNull(store.get("state")){"transport requires retained prior managed history"}
                preParserCheck(binding,restore,expectedPrior,bytes)
                if(restore)observe(bytes)
                initialized()
            }
            override fun process(record:Record<ByteArray,ByteArray>){
                check(!restore){"transport restore must not process additional source"}
                val metadata=context.recordMetadata().orElseThrow()
                require(metadata.topic()==binding.sourceTopic && metadata.partition()==binding.partition && metadata.offset()==15L)
                require(record.value().contentEquals("g9-transport-only".toByteArray()))
                val bytes=payload();require(bytes.size==binding.budget.stateBytes.toInt());store.put("state",bytes);observe(bytes)
            }
            override fun close()=Unit
        }},"finite-source")
        addStateStore(FiniteLifecycleCaptureProcessor.managedStoreBuilder(),"capture")
        addSink("finite-capture",binding.captureTopic,ByteArraySerializer(),ByteArraySerializer(),
            org.apache.kafka.streams.processor.StreamPartitioner<ByteArray,ByteArray>{_,_,_,_->java.util.Optional.of(setOf(binding.partition))},"capture")
    }
    internal fun healthyInputs(binding:FiniteLifecycleBinding,templates:List<ByteArray>):List<ByteArray> = (0..7).map { i ->
        val seller=binding.parties.single{it.side==OrderSide.ORDER_SIDE_SELL};val buyer=binding.parties.single{it.side==OrderSide.ORDER_SIDE_BUY}
        val party=if(i%2==0)seller else buyer;val order="p0-${i+1}"
        val body=FiniteLifecycleFixtures.change(templates[0]) { root ->
            root.put("commandStream",binding.commandTopic);root.put("eventStream",binding.sourceTopic);root.put("batchId","g9-batch-$i");root.put("firstSequence",i+1);root.put("lastSequence",i+1)
            val outcome=FiniteLifecycleFixtures.outcome(root)
            outcome.put("commandId","g9-command-$i");outcome.put("runId",binding.run);outcome.put("instrumentId",binding.instrument);outcome.put("orderId",order);outcome.put("streamSequence",i+1)
            val cmd=outcome["lifecycleCommand"] as ObjectNode
            cmd.put("finiteBindingDigest",binding.digest);cmd.put("sourceProfileHash",binding.profileHash);cmd.put("commandId","g9-command-$i")
            cmd.put("runId",binding.run);cmd.put("venueSessionId",binding.session);cmd.put("instrumentId",binding.instrument);cmd.put("orderId",order)
            cmd.put("participantId",party.participant);cmd.put("accountId",party.account)
            for(k in listOf("traceId","causationId","correlationId","actorId"))cmd.put(k,"x".repeat(128))
            val submit=cmd["submit"] as ObjectNode;submit.put("clientOrderId","client-$i-"+"c".repeat(119));submit.put("side",if(party==seller)"SELL" else "BUY")
            submit.put("quantityUnits","10");submit.put("limitPrice",if(party==seller)"100000000000" else "99999999999");submit.put("currency",binding.currency)
            val accepted=outcome["result"]["accepted"] as ObjectNode;accepted.put("orderId",order);accepted.put("engineOrderId","engine-$i-"+"e".repeat(119));accepted.put("eventId","event-$i")
            val fact=outcome["result"]["acceptedOrder"] as ObjectNode
            fact.put("orderId",order);fact.put("engineOrderId",accepted["engineOrderId"].textValue());fact.put("clientOrderId",submit["clientOrderId"].textValue())
            fact.put("runId",binding.run);fact.put("venueSessionId",binding.session);fact.put("instrumentId",binding.instrument)
            fact.put("participantId",party.participant);fact.put("accountId",party.account);fact.put("side",submit["side"].textValue())
            fact.put("quantityUnits","10");fact.put("limitPrice",submit["limitPrice"].textValue());fact.put("currency",binding.currency)
        }
        require(body.size<=8000);body+ByteArray(8000-body.size){32}
    }
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==5){"opening case phase stateDirectory receiptDirectory required"}
        val case=args[1];val phase=args[2];require(case in listOf("healthy","fault","transport") && phase in listOf("write","restore"))
        val dir=Path.of(args[4]);require(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir) &&
            Files.getPosixFilePermissions(dir)==PosixFilePermissions.fromString("rwx------"))
        val opening=FiniteO2FaultSelection.opening(Path.of(args[0]),requireNotNull(System.getenv("CALCIFY_FINITE_BINDING_DIGEST")))
        val binding=opening.toCaptureBinding();require(binding.budget.stateBytes==6307840L && binding.budget.publications==16 && binding.genesisOffset==0L)
        val restore=phase=="restore"
        fun receipt(name:String)=FiniteBindingContract.strictObject(Files.readAllBytes(dir.resolve(name)),16384)
        val prior=when { restore->receipt("$case-write.json");case=="fault"->receipt("healthy-restore.json");case=="transport"->receipt("fault-restore.json");else->null }
        if(!restore){
            val inputs=when(case){"healthy"->healthyInputs(binding,FiniteManagedStateTransportProbe::class.java.classLoader.getResourceAsStream("g9-source.jsonl")!!.bufferedReader().use{it.readLines().map{line->line.toByteArray()}})
                "fault"->List(7){ByteArray(8000){120}};else->listOf("g9-transport-only".toByteArray())}
            val start=when(case){"healthy"->0;"fault"->8;else->15}
            val lines=inputs.mapIndexed{i,bytes->FiniteBindingContract.encode(json().apply{put("offset",start+i);put("payloadBytes",bytes.size);put("payloadSha256",FiniteLifecycleContract.sha(bytes));put("valueBase64",Base64.getEncoder().encodeToString(bytes))}).toString(Charsets.UTF_8)}
            write(dir.resolve("$case-inputs.jsonl"),(lines.joinToString("\n")+"\n").toByteArray())
        }
        gate(dir.resolve("$case-$phase.start"))
        val started=System.nanoTime();val deadline=started+TimeUnit.SECONDS.toNanos(75)
        val observed=AtomicReference<ByteArray>();val samples=java.util.concurrent.CopyOnWriteArrayList<ObjectNode>()
        val processorBlocked=AtomicReference<Boolean>()
        fun initialized(){write(dir.resolve("$case-$phase.initialized.json"),FiniteBindingContract.encode(json().apply{
            put("schema","calcify-finite-managed-state-transport-v1-initialized");put("case",case);put("phase",phase);put("bindingDigest",binding.digest)
        }))}
        fun sample(label:String){samples+=json().apply{put("stage",label);put("heapUsedBytes",ManagementFactory.getMemoryMXBean().heapMemoryUsage.used.toString())
            val pools=putArray("bufferPools");ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java).forEach{pools.addObject().put("name",it.name).put("memoryUsedBytes",it.memoryUsed.toString()).put("totalCapacityBytes",it.totalCapacity.toString())}}}
        fun ready(bytes:ByteArray){observed.set(bytes);sample("managed-observe")}
        val topology=if(case=="transport")rawTopology(binding,restore,requireNotNull(prior)["stateSha256"].textValue(),::ready,::initialized)
        else FiniteLifecycleCaptureProcessor.modelTopology(binding,FiniteLifecycleStartCut.Genesis(0,0,0),blocked={processorBlocked.set(it)},verifiedInitialize={existing->
            val state=if(prior==null)healthyInitial(binding,existing)
                else requireNotNull(existing).also{require(FiniteLifecycleContract.sha(it.toByteArray())==prior["stateSha256"].textValue())}
            FiniteLifecycleReducer.validateState(state,binding);sample("typed-initialize");initialized();state
        },observe={state->
            val target=if(case=="healthy")state.completedRecordsCount==8 && state.fault.isEmpty() else state.completedRecordsCount==8 && state.retainedSuffixCount==7 && state.fault.isNotEmpty()
            if(target)ready(state.toByteArray())
        })
        val bootstrap=requireNotNull(System.getenv("CALCIFY_FINITE_KAFKA_BOOTSTRAP_SERVERS"));val user=requireNotNull(System.getenv("CALCIFY_FINITE_CAPTURE_KAFKA_USERNAME"))
        val password=Files.readString(Path.of(requireNotNull(System.getenv("CALCIFY_FINITE_CAPTURE_KAFKA_PASSWORD_FILE")))).trim()
        FiniteKafka(opening,bootstrap,user,password).use { kafka ->
            kafka.verifyIdentities();val properties=FiniteLifecycleCaptureRuntime.liveProperties(binding,kafka.properties(),args[3])
            val failure=AtomicReference<Throwable>();val streams=KafkaStreams(topology,properties)
            streams.setUncaughtExceptionHandler{ex->failure.set(ex);org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT}
            var committed:ObjectNode?=null
            try {
                streams.start()
                while(committed==null){failure.get()?.let{throw IllegalStateException("Streams qualification failure",it)};check(System.nanoTime()<deadline){"qualification deadline"}
                    val bytes=observed.get()
                    if(bytes!=null)kafka.consumer().use{reader->
                        val topic=TopicPartition(opening.topic("CAPTURE_CHANGELOG").name,0);reader.assign(listOf(topic));reader.seekToBeginning(listOf(topic))
                        val end=reader.endOffsets(listOf(topic),Duration.ofSeconds(2)).getValue(topic);var count=0;var latest:org.apache.kafka.clients.consumer.ConsumerRecord<ByteArray,ByteArray>?=null
                        while(reader.position(topic)<end){check(System.nanoTime()<deadline);for(record in reader.poll(Duration.ofMillis(100)))if(record.offset()<end){require(++count<=33);require(record.key().contentEquals("state".toByteArray()) && record.value().size<=binding.budget.stateBytes);latest=record}}
                        if(latest?.value()?.contentEquals(bytes)==true){val record=latest!!;committed=json().apply{put("topic",record.topic());put("topicId",opening.topic("CAPTURE_CHANGELOG").id);put("partition",record.partition());put("offset",record.offset().toString());put("readCommittedEndOffset",end.toString());put("observedRecords",count);put("valueBytes",record.value().size);put("valueSha256",FiniteLifecycleContract.sha(record.value()));put("keyBase64",Base64.getEncoder().encodeToString(record.key()));val headers=putArray("headers");record.headers().forEach{headers.addObject().put("key",it.key()).put("valueBase64",Base64.getEncoder().encodeToString(it.value()))};require(record.headers().count()==0){"unexpected production changelog headers"}}}
                    }
                    if(committed==null)Thread.sleep(25)
                }
            } finally {check(streams.close(Duration.ofSeconds(10))){"Streams close failed"}}
            val bytes=requireNotNull(observed.get());val out=json().apply{
                put("schema","calcify-finite-managed-state-transport-v1");put("case",case);put("phase",phase);put("bindingDigest",binding.digest);put("applicationId",binding.applicationId)
                put("sourceTopicId",binding.sourceTopicId);put("changelogTopicId",opening.topic("CAPTURE_CHANGELOG").id);put("success",true)
                put("factoryClassSha256",FiniteLifecycleContract.sha(FiniteLifecycleCaptureProcessor::class.java.getResourceAsStream("FiniteLifecycleCaptureProcessor.class")!!.use{it.readAllBytes()}))
                put("storeName",FiniteLifecycleCaptureProcessor.STORE);put("stateSha256",FiniteLifecycleContract.sha(bytes));put("stateBytes",bytes.size);put("syntheticTransportOnly",case=="transport")
                put("typedAuthority",case!="transport");put("productionReadinessOrRelease",false);put("processingGuarantee","exactly_once_v2");put("compression","none");put("stateDirectory",args[3]);put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started).toString())
                put("producerMaxRequestBytes",(properties[org.apache.kafka.streams.StreamsConfig.producerPrefix("max.request.size")] as Number).toInt())
                put("producerBufferBytes",(properties[org.apache.kafka.streams.StreamsConfig.producerPrefix("buffer.memory")] as Number).toLong().toString())
                put("fetchMaxBytes",(properties[org.apache.kafka.streams.StreamsConfig.consumerPrefix("fetch.max.bytes")] as Number).toInt())
                put("maxPartitionFetchBytes",(properties[org.apache.kafka.streams.StreamsConfig.consumerPrefix("max.partition.fetch.bytes")] as Number).toInt())
                put("storeCaching",false);put("storeLogging",FiniteLifecycleCaptureProcessor.managedStoreBuilder().loggingEnabled());put("storePersistent",true)
                set<ObjectNode>("committedChangelog",requireNotNull(committed));val sampleRows=putArray("sampledMemory");samples.forEach{sampleRows.add(it)}
                if(case!="transport"){val state=FiniteLifecycleStateV1.parseFrom(bytes);FiniteLifecycleReducer.validateState(state,binding)
                    require(processorBlocked.get()==state.fault.isNotEmpty());put("processorBlocked",processorBlocked.get());put("sqlAdmissionStateClaim",false)
                    put("orders",state.ordersCount);put("completedRecords",state.completedRecordsCount);put("batches",state.batchesCount);put("retainedSuffix",state.retainedSuffixCount);put("fault",state.fault);put("admissionBarrier",if(state.fault.isNotEmpty())"STOPPED" else "TEST_ONLY_NO_ADMISSION");put("sourceBytes",state.sourceBytes.toString())}
            }
            if(restore){require(bytes.size==prior!!["stateBytes"].intValue() && out["stateSha256"]==prior["stateSha256"]);out.put("coldRestoreExactBytes",true)}
            write(dir.resolve("$case-$phase.json"),FiniteBindingContract.encode(out))
        }
        gate(dir.resolve("$case-$phase.exit"))
    }
}
