package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import com.reef.platform.api.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Dedicated authenticated finite surface; shares canonical boundary and envelope helpers. */
internal class FiniteHttpServer(private val opening:FiniteOpening,private val ingress:FiniteIngress,
    clients:Map<String,StaticTokenClientConfig>,private val serviceToken:String,
    address:InetSocketAddress,private val witness:(String)->ObjectNode,private val capture:(String)->ObjectNode):AutoCloseable {
    private val boundary=ExternalApiBoundary(StaticTokenAuthHook(clients))
    private val server=HttpServer.create(address,32)
    private val executor=ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(32))
    val port:Int get()=server.address.port
    init {server.executor=executor;server.createContext("/",::handle)}
    fun start()=server.start()
    private fun handle(exchange:HttpExchange) {
        try {
            val path=exchange.requestURI.path
            if(path.startsWith("/internal/calcify/")) {
                if(exchange.requestMethod!="GET" || exchange.requestHeaders["Authorization"]?.size!=1 ||
                    !MessageDigest.isEqual(exchange.requestHeaders.getFirst("Authorization").orEmpty().toByteArray(),("Bearer "+serviceToken).toByteArray())) {
                    reply(exchange,401,"SERVICE_AUTH_REQUIRED");return
                }
                val query=exchange.requestURI.rawQuery.orEmpty()
                require(query.startsWith("nonce=") && query.count{it=='='}==1 && !query.contains('&')){"nonce required"}
                val nonce=query.removePrefix("nonce=");require(FiniteBindingContract.validNonce(nonce))
                val result=when(path) {
                    "/internal/calcify/finite-bindings/${opening.digest}"->witness(nonce)
                    "/internal/calcify/finite-capture-readiness"->capture(nonce)
                    else->{reply(exchange,404,"ROUTE_UNAVAILABLE");return}
                }
                reply(exchange,200,FiniteBindingContract.encode(result).toString(StandardCharsets.UTF_8),true);return
            }
            if(exchange.requestMethod!="POST" || path !in setOf("/api/v1/orders/submit","/api/v1/orders/modify","/api/v1/orders/cancel")) {
                reply(exchange,404,"ROUTE_UNAVAILABLE");return
            }
            if(listOf("Authorization","X-Client-Id","Idempotency-Key").any{exchange.requestHeaders[it]?.size!=1}) {
                reply(exchange,401,"AUTH_HEADERS_REQUIRED");return
            }
            boundary.checkWrite(exchange.requestHeaders,path)?.let {reply(exchange,it.status,it.code);return}
            val bytes=exchange.requestBody.readNBytes(opening.budget.commandBytes+1)
            val body=FiniteBindingContract.strictObject(bytes,opening.budget.commandBytes)
            val canonical=FiniteBindingContract.encode(body).toString(StandardCharsets.UTF_8)
            boundary.checkOrderMutationIdentity(exchange.requestHeaders,JsonCodec.parseObject(canonical))?.let {reply(exchange,it.status,it.code);return}
            // Registered membership is authorization scope and precedes bounded ledger admission.
            if(listOf("runId" to opening.run,"venueSessionId" to opening.session,"instrumentId" to opening.instrument).any{(k,v)->body[k]?.textValue()!=v}) {
                reply(exchange,403,"FINITE_SCOPE_DENIED");return
            }
            val envelope=when(val built=StreamCommandEnvelopeBuilder.fromRequest(boundary.clientId(exchange.requestHeaders)!!,
                route=path,idempotencyKey=boundary.idempotencyKey(exchange.requestHeaders)!!,body=canonical,
                config=StreamCommandConfig(opening.topic("COMMAND").name,opening.json()["commandSubjectPrefix"].textValue(),1))) {
                is EitherBoundaryError.Error->{reply(exchange,built.error.status,built.error.code);return}
                is EitherBoundaryError.Envelope->built.envelope
            }
            ingress.submit(envelope).whenComplete {result,error->
                if(error!=null || result==null) reply(exchange,503,"FINITE_UNAVAILABLE")
                else if(result.status==202) reply(exchange,202,StreamCommandResponse.acceptedJson(
                    envelope.reference(opening.topic("COMMAND").name,result.sequence!!).copy(commandId=result.commandId),result.acceptedAt!!),true)
                else reply(exchange,result.status,result.disposition)
            }
        }catch(ex:IllegalArgumentException){reply(exchange,400,"INVALID_FINITE_REQUEST")}
        catch(ex:Exception){reply(exchange,503,"FINITE_UNAVAILABLE")}
    }
    private fun reply(exchange:HttpExchange,status:Int,value:String,json:Boolean=false) {
        val bytes=(if(json)value else JsonCodec.writeObject("code" to value)).toByteArray(StandardCharsets.UTF_8)
        runCatching {exchange.responseHeaders.set("Content-Type","application/json");exchange.responseHeaders.set("Cache-Control","no-store")
            exchange.sendResponseHeaders(status,bytes.size.toLong());exchange.responseBody.use{it.write(bytes)}}
        exchange.close()
    }
    override fun close(){server.stop(0);executor.shutdownNow()}
}
