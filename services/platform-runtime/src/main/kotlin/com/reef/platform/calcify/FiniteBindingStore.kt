package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import javax.sql.DataSource

internal data class FiniteIngressDecision(val disposition:String,val commandId:String,val streamSequence:Long?=null)
internal data class FinitePublishedReceipt(val commandId:String,val sequence:Long,val acceptedAt:String)

/** Runtime exposes only constrained SQL functions, never registrar release or DDL. */
internal interface FiniteBindingStore {
    fun snapshot(digest:String):ObjectNode
    fun receipt(digest:String,commandId:String):FinitePublishedReceipt
    fun beginAttempt(digest:String,epoch:Long,request:ObjectNode):FiniteIngressDecision
    fun authorizeSend(digest:String,epoch:Long,commandId:String):Boolean
    fun acknowledge(digest:String,commandId:String,originalEpoch:Long,offset:Long,sequence:Long):Boolean
    fun unknown(digest:String,commandId:String,originalEpoch:Long):Boolean
    fun stop(digest:String,epoch:Long):Boolean
    fun claimOwner(digest:String,expectedEpoch:Long,instance:String):Long
}

internal class PostgresFiniteBindingStore(private val dataSource:DataSource):FiniteBindingStore {
    override fun snapshot(digest:String):ObjectNode = json("SELECT boundary.calcify_finite_snapshot(?)",digest)
    override fun receipt(digest:String,commandId:String):FinitePublishedReceipt {
        val row=json("SELECT boundary.calcify_finite_receipt(?, ?)",digest,commandId)
        FiniteBindingContract.fields(row,listOf("commandId","streamSequence","acceptedAt"))
        return FinitePublishedReceipt(FiniteBindingContract.text(row,"commandId",128),
            FiniteBindingContract.number(row,"streamSequence",FiniteBindingContract.MAX_OFFSET),FiniteBindingContract.text(row,"acceptedAt",64))
    }
    override fun beginAttempt(digest:String,epoch:Long,request:ObjectNode):FiniteIngressDecision {
        val row=json("SELECT boundary.calcify_finite_begin_attempt(?, ?, ?::jsonb)",digest,epoch,
            FiniteBindingContract.encode(request).toString(Charsets.UTF_8))
        return FiniteIngressDecision(row["disposition"].textValue(),row["commandId"].textValue(),
            row["streamSequence"].takeUnless { it==null || it.isNull }?.textValue()?.toLong())
    }
    override fun authorizeSend(digest:String,epoch:Long,commandId:String)=bool("SELECT boundary.calcify_finite_authorize_send(?, ?, ?)",digest,epoch,commandId)
    override fun acknowledge(digest:String,commandId:String,originalEpoch:Long,offset:Long,sequence:Long)=
        bool("SELECT boundary.calcify_finite_ack(?, ?, ?, ?, ?)",digest,commandId,originalEpoch,offset,sequence)
    override fun unknown(digest:String,commandId:String,originalEpoch:Long)=bool("SELECT boundary.calcify_finite_unknown(?, ?, ?)",digest,commandId,originalEpoch)
    override fun stop(digest:String,epoch:Long)=bool("SELECT boundary.calcify_finite_stop(?, ?)",digest,epoch)
    override fun claimOwner(digest:String,expectedEpoch:Long,instance:String):Long = query(
        "SELECT boundary.calcify_finite_claim_owner(?, ?, ?)",arrayOf<Any>(digest,expectedEpoch,instance)){it.getLong(1)}
    private fun json(sql:String,vararg values:Any):ObjectNode=query(sql,values){
        FiniteBindingContract.strictObject(it.getString(1).toByteArray(Charsets.UTF_8),FiniteBindingContract.WIRE_BYTES)
    }
    private fun bool(sql:String,vararg values:Any)=query(sql,values){it.getBoolean(1)}
    private fun <T> query(sql:String,values:Array<out Any>,read:(java.sql.ResultSet)->T):T = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { ps ->
            values.forEachIndexed { i,v -> when(v) {is Long->ps.setLong(i+1,v);is String->ps.setString(i+1,v);else->error("unsupported finite SQL parameter")} }
            ps.executeQuery().use { result -> check(result.next()){"finite SQL returned no row"};read(result) }
        }
    }
}
