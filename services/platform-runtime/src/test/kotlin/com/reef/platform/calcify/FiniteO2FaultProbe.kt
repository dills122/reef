package com.reef.platform.calcify

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.fasterxml.jackson.databind.node.ObjectNode
import reef.contracts.calcify.v1.FiniteLifecycleStateV1
import reef.contracts.calcify.v1.LifecycleMemberKindV1

/** Test-only process wrapper; observes real SQL/Kafka composition at actual crash boundaries. */
object FiniteO2FaultProbe {
    @JvmStatic fun main(args:Array<String>) {
        val selection=FiniteO2FaultSelection.parse(args)
        val dir=Path.of(args[0]);require(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir))
        require(Files.getPosixFilePermissions(dir)==PosixFilePermissions.fromString("rwx------")){"private gate directory required"}
        val target=selection.boundary;val command=selection.commandId
        val digest=System.getenv("CALCIFY_FINITE_BINDING_DIGEST")?:error("binding required")
        val captureBinding=selection.openingFile?.let{FiniteO2FaultSelection.opening(it,digest).toCaptureBinding()}
        val roots=listOf("runtime" to FiniteRuntime::class.java,"probe" to FiniteO2FaultProbe::class.java)
        val manifest=roots.joinToString("\n") { (label,type)->
            val classRoot=Path.of(type.protectionDomain.codeSource.location.toURI())
            val classes=if(Files.isDirectory(classRoot)) Files.walk(classRoot).use{paths->paths.filter{Files.isRegularFile(it) && it.toString().endsWith(".class")}.sorted().toList()}
                else listOf(classRoot)
            classes.joinToString("\n"){label+"/"+(if(Files.isDirectory(classRoot))classRoot.relativize(it).toString() else classRoot.fileName.toString())+" "+FiniteLifecycleContract.sha(Files.readAllBytes(it))}
        }
        val manifestBytes=manifest.toByteArray();writePrivate(dir.resolve("class-manifest.txt"),manifestBytes)
        val manifestHash=FiniteLifecycleContract.sha(manifestBytes)
        fun baseEvent():com.fasterxml.jackson.databind.node.ObjectNode = FiniteBindingContract.strictObject("{}".toByteArray(),2).apply {
            put("schema","calcify-finite-fault-probe-v1");put("pid",ProcessHandle.current().pid().toString())
            put("bindingDigest",digest);put("classManifestHash",manifestHash)
        }
        fun gate(name:String,event:com.fasterxml.jackson.databind.node.ObjectNode) {
            writePrivate(dir.resolve("$name.event.json"),FiniteBindingContract.encode(event))
            val release=dir.resolve("$name.release");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!Files.exists(release,LinkOption.NOFOLLOW_LINKS)) {
                require(System.nanoTime()<deadline){"fault probe gate expired"};Thread.sleep(10)
            }
            require(Files.isRegularFile(release,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(release))
        }
        val captureGate=captureBinding?.let{binding->FiniteO2CaptureCommandGate(binding,requireNotNull(selection.captureCommandId),::baseEvent){name,event->gate(name,event)}}
        val runtime=FiniteRuntime.create(observer=FiniteIngressObserver{boundary,id,ack->
            if(boundary==target && id==command) {
                val event=baseEvent();event.put("commandId",id);event.put("boundary",boundary.name)
                if(ack!=null){val a=event.putObject("ack");a.put("topic",ack.topic);a.put("partition",ack.partition);a.put("offset",ack.offset.toString())}else event.putNull("ack")
                gate("$id-${boundary.name}",event)
            }
        },captureObserver={state->captureGate?.observe(state)})
        Runtime.getRuntime().addShutdownHook(Thread{runtime.close()})
        try {runtime.start();CountDownLatch(1).await()}catch(ex:Exception){runtime.close();throw ex}
    }
    private fun writePrivate(path:Path,bytes:ByteArray) {
        Files.newByteChannel(path,setOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use{channel->
            val buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer)
        }
    }
}

internal data class FiniteO2FaultSelection(val boundary:FiniteIngressBoundary,val commandId:String,
    val captureCommandId:String?=null,val openingFile:Path?=null) {
    companion object {
        fun parse(args:Array<String>):FiniteO2FaultSelection {
            require(args.size==3 || args.size==5){"gate directory, boundary, command ID; mixed capture requires command selector and opening file"}
            val boundary=FiniteIngressBoundary.valueOf(args[1]);val command=args[2]
            require(command.matches(Regex("[A-Za-z0-9_-]{1,128}"))){"invalid ingress command ID"}
            if(args.size==3)return FiniteO2FaultSelection(boundary,command)
            require(boundary==FiniteIngressBoundary.PUBLISHING && command.endsWith("-cmd-02")){"dedicated mixed ingress target required"}
            require(command.removeSuffix("-cmd-02").matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))){"dedicated mixed prefix required"}
            val capture=args[3].removePrefix("captureCommandId=")
            require(args[3]=="captureCommandId=$capture" && capture.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                capture==command.removeSuffix("-cmd-02")+"-cmd-01"){"exact prearmed cmd01 selector required"}
            require(args[4]=="openingFile=/fixture/state/fault-opening.json"){"exact private opening file required"}
            return FiniteO2FaultSelection(boundary,command,capture,Path.of("/fixture/state/fault-opening.json"))
        }
        fun opening(path:Path,digest:String):FiniteOpening {
            require(digest.matches(Regex("[0-9a-f]{64}"))){"binding digest required"}
            require(path.isAbsolute && path.normalize()==path && generateSequence(path){it.parent}.none{Files.isSymbolicLink(it)}){"opening path contains symlink"}
            require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) &&
                Files.getPosixFilePermissions(path)==PosixFilePermissions.fromString("rw-------")){"private0600 opening file required"}
            val before=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            require(before.size() in 1..FiniteBindingContract.WIRE_BYTES.toLong()){"opening file byte cap"}
            val bytes=Files.newByteChannel(path,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use{channel->
                val buffer=java.nio.ByteBuffer.allocate(FiniteBindingContract.WIRE_BYTES+1)
                while(buffer.hasRemaining() && channel.read(buffer)>=0){}
                require(buffer.position()<=FiniteBindingContract.WIRE_BYTES){"opening file byte cap"}
                buffer.flip();ByteArray(buffer.remaining()).also{buffer.get(it)}
            }
            val after=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            require(before.fileKey()==after.fileKey() && before.size()==after.size() && before.lastModifiedTime()==after.lastModifiedTime() && bytes.size.toLong()==after.size()){"opening changed while reading"}
            return FiniteBindingContract.parseOpening(bytes).also{opening->
                require(opening.digest==digest && bytes.contentEquals(FiniteBindingContract.encode(opening.json()))){"opening digest/canonical bytes mismatch"}
            }
        }
    }
}

/** Callback remains synchronous: gate cannot return to Streams until released or process killed. */
internal class FiniteO2CaptureCommandGate(private val binding:FiniteLifecycleBinding,private val commandId:String,
    private val baseEvent:()->ObjectNode,private val gate:(String,ObjectNode)->Unit) {
    private val emitted=AtomicBoolean(false)
    fun observe(state:FiniteLifecycleStateV1) {
        if(emitted.get())return
        val records=state.completedRecordsList.filter{record->record.membersList.any{
            it.id.kind==LifecycleMemberKindV1.LIFECYCLE_MEMBER_COMMAND && it.command.commandId==commandId}}
        if(records.isEmpty())return
        require(records.size==1 && state.completedRecordsCount==1 && state.fault.isEmpty() && state.retainedSuffixCount==0){"mixed capture requires first complete prefix"}
        FiniteLifecycleReducer.validateState(state,binding)
        val record=records.single();val source=record.source
        val member=record.membersList.single{it.id.kind==LifecycleMemberKindV1.LIFECYCLE_MEMBER_COMMAND && it.command.commandId==commandId}
        val command=member.command
        require(record.finiteBindingDigest==binding.digest && source.sourceTopic==binding.sourceTopic && source.sourceTopicId==binding.sourceTopicId &&
            source.sourceGeneration==binding.generation && source.sourcePartition==binding.partition && source.sourceOffset in 0 until FiniteBindingContract.MAX_OFFSET &&
            record.completedFrontier==source && state.completedFrontier==source && record.resumeOffset==source.sourceOffset+1 && state.resumeOffset==record.resumeOffset){"capture source binding/lane/frontier mismatch"}
        require(command.commandId==commandId && member.id.source.commandId==commandId && command.finiteBindingDigest==binding.digest &&
            command.sourceProfileHash==binding.profileHash && command.runId==binding.run && command.venueSessionId==binding.session && command.instrumentId==binding.instrument &&
            member.id.source.toBuilder().clearCommandId().clearOutcomeOrdinal().build()==source){"actual capture command member mismatch"}
        val event=baseEvent();event.put("boundary","CAPTURE_PROCESSED_BEFORE_COMMIT");event.put("captureCommandId",command.commandId)
        event.put("sourceTopic",source.sourceTopic);event.put("sourceTopicId",source.sourceTopicId)
        event.put("sourceGeneration",source.sourceGeneration);event.put("sourcePartition",source.sourcePartition)
        event.put("sourceOffset",source.sourceOffset.toString());event.put("sourceContentDigest",record.sourceContentDigest)
        event.put("stateDigest",FiniteLifecycleContract.sha(state.toByteArray()));event.put("completedRecordsCount",state.completedRecordsCount)
        event.put("resumeOffset",record.resumeOffset.toString());event.put("completedFrontierSourceOffset",record.completedFrontier.sourceOffset.toString())
        val ids=event.putArray("commandIds");record.membersList.filter{it.id.kind==LifecycleMemberKindV1.LIFECYCLE_MEMBER_COMMAND}.forEach{ids.add(it.command.commandId)}
        if(emitted.compareAndSet(false,true))gate("capture-${command.commandId}",event)
    }
}
