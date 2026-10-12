package com.reef.platform.calcify

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.apache.kafka.common.Uuid
import reef.contracts.orderexecution.v1.OrderSide

/** Registered opening identity; observation/activation evidence remains separate. */
internal class FiniteOpening internal constructor(private val root: ObjectNode) {
    fun json(): ObjectNode = root.deepCopy()
    val profileHash: String get() = root["profileHash"].textValue()
    val run: String get() = root["runId"].textValue()
    val session: String get() = root["venueSessionId"].textValue()
    val instrument: String get() = root["instrumentId"].textValue()
    val applicationId: String get() = root["captureApplicationId"].textValue()
    val generation: Int get() = root["sourceGeneration"].intValue()
    fun topic(role: String): FiniteTopic = root["topics"].single { it["role"].textValue() == role }
        .let { FiniteTopic(role, it["name"].textValue(), it["topicId"].textValue()) }
    val parties: List<FiniteParty> get() = root["parties"].map { FiniteParty(it["participantId"].textValue(),
        it["accountId"].textValue(), if(it["side"].textValue()=="BUY") OrderSide.ORDER_SIDE_BUY else OrderSide.ORDER_SIDE_SELL) }
    val budget: FiniteLifecycleBudget get() {
        val b=root["budget"]; val p=root["profile"]
        fun n(k:String)=b[k].textValue().toLong()
        return FiniteLifecycleBudget(n("attempts"),n("publications").toInt(),n("commandBytes").toInt(),
            n("outcomes").toInt(),n("trades").toInt(),n("openWindows").toInt(),n("sourceBytes").toInt(),
            n("captureBytes").toInt(),n("attemptBytes").toInt(),n("openingBytes").toInt(),
            p["maxOrderIds"].intValue(),p["maxQuantityUnits"].longValue(),p["maxLimitPrice"].longValue())
    }
    fun canonicalBytes(): ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("calcify-finite-binding-v1\u0000".toByteArray(Charsets.US_ASCII))
            writeCanonicalBody(out)
        }
        return bytes.toByteArray()
    }
    /** Everything after the schema prefix; v2 reuses it unchanged and appends the finance suffix. */
    internal fun writeCanonicalBody(out: DataOutputStream) {
        out.write(FiniteLifecycleContract.hex(profileHash)); out.write(FiniteLifecycleContract.hex(root["budgetHash"].textValue()))
        listOf("lifecycleSchema","policyId","runId","venueSessionId","instrumentId","quoteCurrency","baseAssetId").forEach { out.frame(root[it].textValue()) }
        out.writeInt(parties.size)
        parties.sortedWith(compareBy<FiniteParty>{it.participant}.thenBy{it.account}).forEach {
            out.frame(it.participant);out.frame(it.account);out.writeByte(if(it.side==OrderSide.ORDER_SIDE_BUY)1 else 2)
        }
        root["topics"].forEach { out.frame(it["name"].textValue());out.frame(it["topicId"].textValue()) }
        listOf("sourceGeneration","partition","partitionCount").forEach { out.writeInt(root[it].intValue()) }
        listOf("commandConsumerGroup","ownershipGroup","matcherShardId","commandSubjectPrefix","sourceSubjectPrefix",
            "brokerClusterId","captureApplicationId","storeName","stateVersion").forEach { out.frame(root[it].textValue()) }
        out.writeByte(1)
        FiniteBindingContract.initialOffsets.forEach { out.writeLong(root[it].textValue().toLong()) }
    }
    val digest: String get() = FiniteLifecycleContract.sha(canonicalBytes())
    fun toCaptureBinding(): FiniteLifecycleBinding = FiniteLifecycleBinding(digest,profileHash,run,session,instrument,
        root["quoteCurrency"].textValue(),parties,topic("SOURCE").name,topic("SOURCE").id,generation,0,
        topic("COMMAND").name,topic("COMMAND").id,topic("CAPTURE").name,topic("CAPTURE").id,applicationId,0,budget)
}

/** Binding v2 (docs/work/CALCIFY_FINITE_P3_BINDING_V2_SPEC.md): the v1 opening plus a finance block; built only by [FiniteBindingContract.parseOpeningV2]. */
internal class FiniteOpeningV2 internal constructor(private val root: ObjectNode) {
    private val core = FiniteOpening(root)
    fun json(): ObjectNode = root.deepCopy()
    val financeApplicationId: String get() = root["financeApplicationId"].textValue()
    val controlTransactionalId: String get() = root["controlTransactionalId"].textValue()
    val openingAccountId: String get() = root["openingAccountId"].textValue()
    val financeConfigDigest: String get() = root["financeConfigDigest"].textValue()
    fun topic(role: String): FiniteTopic = core.topic(role)
    /** The O3a configuration this opening pins; its digest is [financeConfigDigest] (checked by the parser). */
    fun financeConfig(): com.reef.platform.calcify.finance.FinanceConfig {
        val b=root["financeBudget"]; fun n(k:String)=b[k].textValue().toInt()
        return com.reef.platform.calcify.finance.FinanceConfig(core.parties.map{com.reef.platform.calcify.finance.AccountId(it.account)}.toSet(),
            com.reef.platform.calcify.finance.AssetId(root["quoteCurrency"].textValue()),com.reef.platform.calcify.finance.AssetId(root["baseAssetId"].textValue()),
            com.reef.platform.calcify.finance.AccountId(openingAccountId),n("obligations"),n("captures"),n("settleAttempts"),n("controls"))
    }
    fun canonicalBytes(): ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("calcify-finite-binding-v2\u0000".toByteArray(Charsets.US_ASCII))
            core.writeCanonicalBody(out)
            listOf("financeApplicationId","financeStoreName","financeStateVersion","openingAccountId","controlTransactionalId").forEach { out.frame(root[it].textValue()) }
            out.write(FiniteLifecycleContract.hex(financeConfigDigest))
            listOf("obligations","captures","settleAttempts","controls").forEach { out.writeInt(root["financeBudget"][it].textValue().toInt()) }
            FiniteBindingContract.initialOffsetsV2.forEach { out.writeLong(root[it].textValue().toLong()) }
        }
        return bytes.toByteArray()
    }
    val digest: String get() = FiniteLifecycleContract.sha(canonicalBytes())
}

internal data class FiniteTopic(val role: String,val name: String,val id: String)
internal data class FiniteStartupWitness(val opening: FiniteOpening,val run: ObjectNode,val cuts: ObjectNode,val journal: List<ObjectNode>)

internal object FiniteBindingContract {
    const val WIRE_BYTES=32768
    const val HEADER_BYTES=8192
    const val JOURNAL_BYTES=1024
    const val MAX_OFFSET=281474976710655L
    val roles=listOf("COMMAND","SOURCE","CAPTURE","OWNERSHIP","CAPTURE_CHANGELOG")
    val rolesV2=roles+listOf("CONTROL","FINANCE_DECISIONS","FINANCE_CHANGELOG")
    val initialOffsetsV2=listOf("initialControlNextOffset","initialFinanceDecisionsNextOffset","initialFinanceCaptureNextOffset","initialFinanceControlNextOffset")
    private val financeFields=listOf("financeApplicationId","financeStoreName","financeStateVersion","openingAccountId","controlTransactionalId",
        "financeConfigDigest","financeBudget")+initialOffsetsV2
    private val asciiIdentifier=Regex("[A-Za-z0-9._:-]{1,128}")
    private fun prefixRelated(a:String,b:String)=a.startsWith(b)||b.startsWith(a)
    val initialOffsets=listOf("initialCommandNextOffset","initialSourceNextOffset","initialCaptureNextOffset","initialCaptureSourceGroupNextOffset")
    private val mapper=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    private val openingFields=listOf("schema","profile","profileHash","budget","budgetHash","lifecycleSchema","policyId","runId",
        "venueSessionId","instrumentId","quoteCurrency","baseAssetId","parties","topics","sourceGeneration","partition","partitionCount",
        "commandConsumerGroup","ownershipGroup","matcherShardId","commandSubjectPrefix","sourceSubjectPrefix","brokerClusterId",
        "captureApplicationId","storeName","stateVersion","openingMode")+initialOffsets
    private val budgetFields=listOf("attempts","publications","commandBytes","outcomes","trades","openWindows","sourceBytes","captureBytes","attemptBytes","openingBytes")
    private val profileFields=listOf("schema","runIds","venueSessionId","instrumentId","currency","maxOrderIds","maxQuantityUnits","maxLimitPrice")
    private val hash=Regex("[0-9a-f]{64}")
    private val decimal=Regex("0|[1-9][0-9]*")
    private val noncePattern=Regex("[A-Za-z0-9_-]{1,128}")
    fun validNonce(nonce:String)=noncePattern.matches(nonce)

    fun strictObject(bytes:ByteArray,cap:Int):ObjectNode {
        require(bytes.size<=cap){"finite JSON byte cap exceeded"}
        try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            val root=mapper.readTree(bytes)
            require(root is ObjectNode){"finite JSON must be object"}
            validateUnicode(root)
            return root
        } catch(ex:Exception) { throw IllegalArgumentException("invalid finite JSON",ex) }
    }
    fun encode(node:JsonNode):ByteArray=mapper.writeValueAsBytes(node)
    fun fields(node:JsonNode,names:List<String>) {
        require(node.isObject && node.properties().map { it.key }.toSet()==names.toSet()){ "finite fields missing/unknown" }
    }
    fun text(node:JsonNode,key:String,cap:Int=256,operational:Boolean=true):String {
        val value=node[key];require(value!=null && value.isTextual){"finite $key must be text"}
        val s=value.textValue();require(s.isNotEmpty() && s.toByteArray(Charsets.UTF_8).size<=cap){"finite $key length"}
        if(operational) require(s==s.trim() && s.none { it.isISOControl() }){"finite $key whitespace/control"}
        return s
    }
    fun number(node:JsonNode,key:String,max:Long=Long.MAX_VALUE):Long {
        val s=text(node,key,32,false);require(decimal.matches(s)){"finite $key decimal spelling"}
        val n=s.toLongOrNull();require(n!=null && n in 0..max){"finite $key out of range"};return n
    }
    private fun small(node:JsonNode,key:String,min:Int,max:Int):Int {
        val n=node[key];require(n!=null && n.isIntegralNumber && n.canConvertToInt() && n.intValue() in min..max){"finite $key integer"};return n.intValue()
    }
    fun parseOpening(bytes:ByteArray):FiniteOpening=opening(strictObject(bytes,16384))

    /** Strict v2 adapter: every v1 rule is applied through the v1 validator on the v1-shaped core; v2 only adds constraints. */
    fun parseOpeningV2(bytes:ByteArray):FiniteOpeningV2 {
        val node=strictObject(bytes,16384)
        fields(node,openingFields+financeFields)
        require(text(node,"schema")=="calcify-finite-binding-v2"){"finite v2 schema"}
        val topics=node["topics"]
        require(topics.isArray && topics.size()==8){"finite v2 topics"}
        val core=node.deepCopy().apply {
            put("schema","calcify-finite-binding-v1"); financeFields.forEach { remove(it) }
            set<JsonNode>("topics",mapper.createArrayNode().apply { (0 until 5).forEach { add(topics[it].deepCopy<JsonNode>()) } })
        }
        val v1=opening(core)
        // Roles 5..7 plus global distinctness over all eight topics.
        for(i in 5 until 8) {
            val t=topics[i];fields(t,listOf("role","name","topicId"))
            require(text(t,"role")==rolesV2[i]){"finite v2 topic role order"};text(t,"name")
            val id=text(t,"topicId",22);val uuid=try { Uuid.fromString(id) } catch(ex:Exception){throw IllegalArgumentException("finite topic UUID",ex)}
            require(uuid!=Uuid.ZERO_UUID && uuid.toString()==id){"finite topic UUID spelling"}
        }
        require(topics.map{it["name"].textValue()}.distinct().size==8 && topics.map{it["topicId"].textValue()}.distinct().size==8){"finite duplicate topic"}
        // Finance block.
        require(text(node,"financeStoreName")=="finite-finance" && text(node,"financeStateVersion")=="calcify-finite-finance-state-v1"){"finite finance version"}
        val app=text(node,"financeApplicationId");val ctl=text(node,"controlTransactionalId");val opening=text(node,"openingAccountId",128)
        require(topics[7]["name"].textValue()==app+"-finite-finance-changelog"){"finite finance changelog name"}
        val baseAsset=v1.json()["baseAssetId"].textValue();val quote=v1.json()["quoteCurrency"].textValue()
        val accounts=v1.parties.map{it.account}
        require((accounts+baseAsset+opening).all{asciiIdentifier.matches(it)}){"finite ledger identifiers must be [A-Za-z0-9._:-]{1,128}"}
        require(quote!=baseAsset && opening !in accounts){"finite ledger identity collision"}
        val budget=node["financeBudget"];fields(budget,listOf("obligations","captures","settleAttempts","controls"))
        val limits=listOf("obligations","captures","settleAttempts","controls").map{ number(budget,it) }
        require(limits==listOf(256L,128L,160L,8L)){"finite finance budget differs from the compiled O3a limits"}
        initialOffsetsV2.forEach { require(number(node,it,MAX_OFFSET)==0L){"finite genesis offset"} }
        val digest=text(node,"financeConfigDigest",64)
        val expected=com.reef.platform.calcify.finance.FinanceConfig(accounts.map{com.reef.platform.calcify.finance.AccountId(it)}.toSet(),
            com.reef.platform.calcify.finance.AssetId(quote),com.reef.platform.calcify.finance.AssetId(baseAsset),com.reef.platform.calcify.finance.AccountId(opening),
            limits[0].toInt(),limits[1].toInt(),limits[2].toInt(),limits[3].toInt()).digest()
        require(hash.matches(digest) && digest==expected){"finite finance config digest"}
        // Prefix-distinctness: finance principal prefix ACLs must cover only finance resources.
        val j=v1.json()
        val others=listOf(j["captureApplicationId"].textValue(),j["commandConsumerGroup"].textValue(),j["ownershipGroup"].textValue(),j["matcherShardId"].textValue(),ctl)
        require(others.none{prefixRelated(app,it)}){"finite finance application id prefix collision"}
        require((0 until 8).none{it!=7 && prefixRelated(app,topics[it]["name"].textValue())}){"finite finance application id topic prefix collision"}
        require(listOf(j["captureApplicationId"].textValue(),j["commandConsumerGroup"].textValue(),j["ownershipGroup"].textValue()).none{prefixRelated(ctl,it)}){"finite control transactional id prefix collision"}
        // Normalized (party-sorted) full document.
        val normalized=node.deepCopy().apply { set<JsonNode>("parties",v1.json()["parties"]) }
        // Measured on the strict JSON bytes of the full v2 document (party order does not change the length), as Go does with json.Marshal.
        require(encode(node).size<=v1.budget.openingBytes){"finite v2 opening measured bytes"}
        val result=FiniteOpeningV2(normalized)
        require(result.canonicalBytes().size<=16384){"finite v2 canonical bytes"}
        return result
    }
    private fun opening(node:ObjectNode):FiniteOpening {
        fields(node,openingFields)
        require(text(node,"schema")=="calcify-finite-binding-v1" && text(node,"lifecycleSchema")==FiniteLifecycleContract.COMMAND_SCHEMA &&
            text(node,"policyId")=="calcify-limit-day-v1" && text(node,"storeName")=="finite-lifecycle" &&
            text(node,"stateVersion")==FiniteLifecycleContract.STATE_VERSION && text(node,"openingMode")=="GENESIS"){"finite opening versions"}
        listOf("runId","venueSessionId","instrumentId").forEach { text(node,it,128) }
        require(text(node,"quoteCurrency",3).matches(Regex("[A-Z]{3}"))){"finite currency"}
        openingFields.filter { it !in listOf("profile","budget","parties","topics","sourceGeneration","partition","partitionCount")+initialOffsets }
            .forEach { text(node,it) }
        val p=node["profile"];fields(p,profileFields)
        require(text(p,"schema")=="calcify-finite-source-v1" && p["runIds"].isArray && p["runIds"].size()==1 &&
            p["runIds"][0].isTextual && p["runIds"][0].textValue()==node["runId"].textValue()){"finite profile run"}
        listOf("venueSessionId","instrumentId").forEach { require(text(p,it,128)==text(node,it,128)){"finite profile scope"} }
        require(text(p,"currency",3)==text(node,"quoteCurrency",3)){"finite profile currency"}
        small(p,"maxOrderIds",1,8);small(p,"maxQuantityUnits",1,10)
        require(p["maxLimitPrice"].isIntegralNumber && p["maxLimitPrice"].canConvertToLong() && p["maxLimitPrice"].longValue() in 1..100000000000){"finite profile price"}
        val canonicalProfile=mapper.createObjectNode();profileFields.forEach { canonicalProfile.set<JsonNode>(it,p[it]) }
        // Preserve Go encoding/json HTML and line-separator escaping for frozen P0.
        val profileBytes=mapper.writeValueAsString(canonicalProfile).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026")
            .replace("\u2028","\\u2028").replace("\u2029","\\u2029").toByteArray(Charsets.UTF_8)
        require(hash.matches(text(node,"profileHash",64)) && FiniteLifecycleContract.sha(profileBytes)==text(node,"profileHash",64)){"finite profile hash"}
        fields(node["budget"],budgetFields);budgetFields.forEach { number(node["budget"],it) }
        require(node["parties"].isArray && node["parties"].size()==2){"finite parties"}
        node["parties"].forEach { fields(it,listOf("participantId","accountId","side"));text(it,"participantId",128);text(it,"accountId",128);require(text(it,"side") in listOf("BUY","SELL")){"finite side"} }
        val parties=node["parties"].map { it["participantId"].textValue() to it["accountId"].textValue() }
        require(parties.map{it.first}.distinct().size==2 && parties.map{it.second}.distinct().size==2 && node["parties"].map{it["side"].textValue()}.toSet()==setOf("BUY","SELL")){"finite duplicate party"}
        require(node["topics"].isArray && node["topics"].size()==5){"finite topics"}
        node["topics"].forEachIndexed { i,t ->
            fields(t,listOf("role","name","topicId"));require(text(t,"role")==roles[i]){"finite topic role order"};text(t,"name")
            val id=text(t,"topicId",22);val uuid=try { Uuid.fromString(id) } catch(ex:Exception){throw IllegalArgumentException("finite topic UUID",ex)}
            require(uuid!=Uuid.ZERO_UUID && uuid.toString()==id){"finite topic UUID spelling"}
        }
        require(node["topics"].map{it["name"].textValue()}.distinct().size==5 && node["topics"].map{it["topicId"].textValue()}.distinct().size==5){"finite duplicate topic"}
        small(node,"sourceGeneration",1,Int.MAX_VALUE);small(node,"partition",0,0);small(node,"partitionCount",1,1)
        initialOffsets.forEach { require(number(node,it,MAX_OFFSET)==0L){"finite genesis offset"} }
        require(text(node,"commandSubjectPrefix").trim('.')==text(node,"commandSubjectPrefix") && text(node,"sourceSubjectPrefix").trim('.')==text(node,"sourceSubjectPrefix")){"finite subject normalization"}
        require(node["topics"][4]["name"].textValue()==text(node,"captureApplicationId")+"-finite-lifecycle-changelog"){"finite changelog name"}
        val normalized=node.deepCopy()
        normalized.set<JsonNode>("parties",mapper.createArrayNode().apply {
            node["parties"].sortedWith(compareBy<JsonNode>{it["participantId"].textValue()}.thenBy{it["accountId"].textValue()}).forEach{add(it.deepCopy<JsonNode>())}
        })
        val result=FiniteOpening(normalized)
        val b=result.budget
        require(hash.matches(text(node,"budgetHash",64)) && FiniteLifecycleContract.sha(b.canonical(result.profileHash,"calcify-limit-day-v1",result.run,result.session,result.instrument,
            text(node,"quoteCurrency"),result.parties))==text(node,"budgetHash",64)){"finite budget hash"}
        require(encode(node).size<=b.openingBytes){"finite opening measured bytes"}
        return result
    }

    fun parseStartup(bytes:ByteArray,expectedDigest:String,nonce:String):FiniteStartupWitness {
        require(hash.matches(expectedDigest) && validNonce(nonce)){"finite witness expectation"}
        val root=strictObject(bytes,WIRE_BYTES);fields(root,listOf("schema","nonce","bindingDigest","opening","run","cuts","journal"))
        require(text(root,"schema")=="calcify-finite-startup-v1" && text(root,"nonce",128)==nonce && text(root,"bindingDigest",64)==expectedDigest){"finite witness challenge"}
        val rawOpening=root["opening"] as? ObjectNode ?: throw IllegalArgumentException("finite opening object")
        val o=opening(rawOpening);require(o.digest==expectedDigest){"finite binding digest"}
        require(rawOpening["parties"]==o.json()["parties"]){"finite witness party order"}
        val r=root["run"];fields(r,listOf("ownerInstanceId","ownerEpoch","activationEpoch","admissionState","recordedAttempts","possiblePublications","commandPayloadBytes","attemptRecordBytes","unresolvedPublications"))
        text(r,"ownerInstanceId",128);val epoch=number(r,"ownerEpoch");require(epoch>0){"finite owner epoch"};number(r,"activationEpoch")
        require(text(r,"admissionState") in listOf("STOPPED","ACTIVE")){"finite admission state"}
        val b=o.budget;number(r,"recordedAttempts",b.attempts);val publications=number(r,"possiblePublications",b.publications.toLong())
        val payloadBytes=number(r,"commandPayloadBytes",b.publications.toLong()*b.commandBytes);number(r,"attemptRecordBytes",b.attempts*b.attemptBytes)
        val unresolved=number(r,"unresolvedPublications",b.publications.toLong())
        val c=root["cuts"];fields(c,listOf("commandGroup","captureSourceGroup","ranges"))
        for((key,id) in listOf("commandGroup" to o.json()["commandConsumerGroup"].textValue(),"captureSourceGroup" to o.applicationId)) {
            fields(c[key],listOf("groupId","nextOffset"));require(text(c[key],"groupId")==id){"finite group identity"};number(c[key],"nextOffset",MAX_OFFSET)
        }
        require(c["ranges"].isArray && c["ranges"].size()==5){"finite ranges"}
        c["ranges"].forEachIndexed { i,v ->
            fields(v,listOf("role","partition","beginningOffset","endOffset","readCommittedEndOffset"));require(text(v,"role")==roles[i]){"finite range role"};small(v,"partition",0,0)
            val begin=number(v,"beginningOffset",MAX_OFFSET);val end=number(v,"endOffset",MAX_OFFSET);val committed=number(v,"readCommittedEndOffset",MAX_OFFSET)
            require(begin<=committed && committed<=end){"finite range cuts"}
        }
        val j=root["journal"];require(j.isArray && j.size().toLong()==publications){"finite journal count"}
        val ids=HashSet<String>();var unresolvedCount=0L;var byteCount=0L
        j.forEachIndexed { i,v ->
            fields(v,listOf("publicationOrdinal","commandId","commandType","payloadHash","keyHash","subjectHash","routeHash","ownerEpoch","state","partition","offset","streamSequence","payloadBytes"))
            require(number(v,"publicationOrdinal")==i+1L && ids.add(text(v,"commandId",128))){"finite journal identity/order"}
            require(text(v,"commandType") in listOf("SubmitOrder","ModifyOrder","CancelOrder")){"finite command type"}
            listOf("payloadHash","keyHash","subjectHash","routeHash").forEach { require(hash.matches(text(v,it,64))){"finite journal hash"} }
            require(number(v,"ownerEpoch") in 1..epoch){"finite journal epoch"};small(v,"partition",0,0)
            val state=text(v,"state");require(state in listOf("RESERVED","PUBLISHING","UNKNOWN","PUBLISHED","DEFINITELY_NOT_SENT")){"finite journal state"}
            if(state=="PUBLISHED") { val offset=number(v,"offset",MAX_OFFSET-1);require(number(v,"streamSequence",MAX_OFFSET)==offset+1){"finite ACK position"} }
            else { require(v["offset"].isNull && v["streamSequence"].isNull){"finite uncertain position"};if(state!="DEFINITELY_NOT_SENT")unresolvedCount++ }
            val n=number(v,"payloadBytes",b.commandBytes.toLong());require(n>0){"finite payload empty"};byteCount=Math.addExact(byteCount,n)
            require(encode(v).size<=JOURNAL_BYTES){"finite journal measured bytes"}
        }
        require(unresolved==unresolvedCount && payloadBytes==byteCount){"finite journal accounting"}
        require(encode(root.deepCopy().apply {set<JsonNode>("journal",mapper.createArrayNode())}).size<=HEADER_BYTES){"finite witness header bytes"}
        return FiniteStartupWitness(o,(r as ObjectNode).deepCopy(),(c as ObjectNode).deepCopy(),j.map{(it as ObjectNode).deepCopy()})
    }

    private fun validateUnicode(node:JsonNode) {
        if(node.isTextual) {
            val s=node.textValue();var i=0
            while(i<s.length) { val ch=s[i++]
                if(ch.isHighSurrogate()) require(i<s.length && s[i++].isLowSurrogate()){"finite lone surrogate"}
                else require(!ch.isLowSurrogate()){"finite lone surrogate"}
            }
        } else if(node.isObject) node.properties().forEach { validateUnicode(mapper.nodeFactory.textNode(it.key));validateUnicode(it.value) }
        else if(node.isArray) node.forEach(::validateUnicode)
    }
}

private fun DataOutputStream.frame(value:String) {val bytes=value.toByteArray(Charsets.UTF_8);writeInt(bytes.size);write(bytes)}
