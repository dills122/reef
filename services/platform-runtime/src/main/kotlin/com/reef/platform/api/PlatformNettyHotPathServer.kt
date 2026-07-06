package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.sun.net.httpserver.Headers
import io.grpc.netty.shaded.io.netty.bootstrap.ServerBootstrap
import io.grpc.netty.shaded.io.netty.buffer.Unpooled
import io.grpc.netty.shaded.io.netty.channel.ChannelFutureListener
import io.grpc.netty.shaded.io.netty.channel.ChannelHandlerContext
import io.grpc.netty.shaded.io.netty.channel.ChannelInitializer
import io.grpc.netty.shaded.io.netty.channel.ChannelOption
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.SimpleChannelInboundHandler
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.socket.SocketChannel
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerSocketChannel
import io.grpc.netty.shaded.io.netty.handler.codec.TooLongFrameException
import io.grpc.netty.shaded.io.netty.handler.codec.http.DefaultFullHttpResponse
import io.grpc.netty.shaded.io.netty.handler.codec.http.FullHttpRequest
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpHeaderNames
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpHeaderValues
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpObjectAggregator
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpResponseStatus
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpServerCodec
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpUtil
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpVersion
import io.grpc.netty.shaded.io.netty.handler.codec.http.QueryStringDecoder
import io.grpc.netty.shaded.io.netty.util.concurrent.DefaultEventExecutorGroup
import io.grpc.netty.shaded.io.netty.util.concurrent.RejectedExecutionHandlers
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class PlatformNettyHotPathServer(
    private val delegate: PlatformHttpServer = PlatformHttpServer(),
    private val port: Int = RuntimeEnv.int("PLATFORM_RUNTIME_PORT", 8080),
    private val backlog: Int = RuntimeEnv.int("PLATFORM_HTTP_BACKLOG", 1024, min = 64),
    private val maxRequestBodyBytes: Int = RuntimeEnv.int("PLATFORM_HTTP_MAX_REQUEST_BYTES", 1024 * 1024, min = 1024),
    private val bossThreads: Int = RuntimeEnv.int("PLATFORM_NETTY_BOSS_THREADS", 1, min = 1),
    private val workerThreads: Int = RuntimeEnv.int("PLATFORM_NETTY_WORKER_THREADS", 0, min = 0),
    private val applicationThreads: Int =
        RuntimeEnv.int("PLATFORM_NETTY_APPLICATION_THREADS", defaultApplicationThreads(), min = 1),
    private val applicationMaxPendingTasks: Int =
        RuntimeEnv.int("PLATFORM_NETTY_APPLICATION_MAX_PENDING_TASKS", defaultApplicationMaxPendingTasks(), min = 1),
    private val streamIngressEnabled: Boolean = RuntimeEnv.bool("STREAM_INGRESS_ENABLED", false)
) {
    fun start(): RunningPlatformNettyServer {
        val bossGroup = NioEventLoopGroup(bossThreads)
        val workerGroup = if (workerThreads > 0) NioEventLoopGroup(workerThreads) else NioEventLoopGroup()
        val applicationGroup = DefaultEventExecutorGroup(
            applicationThreads,
            namedThreadFactory("reef-netty-app"),
            applicationMaxPendingTasks,
            RejectedExecutionHandlers.reject()
        )
        var channelStarted = false
        try {
            val channel = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .option(ChannelOption.SO_BACKLOG, backlog)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline()
                            .addLast(HttpServerCodec())
                            .addLast(HttpObjectAggregator(maxRequestBodyBytes))
                            .addLast(applicationGroup, HotPathHandler(delegate))
                    }
                })
                .bind(port)
                .sync()
                .channel()
            channelStarted = true
            delegate.startRuntimeLoops()
            val streamIngress = if (streamIngressEnabled) {
                PlatformStreamIngressServer(delegate).start()
            } else {
                null
            }
            val boundPort = (channel.localAddress() as InetSocketAddress).port
            println("platform-runtime adapter=netty-hot-path listening on :$boundPort")
            return RunningPlatformNettyServer(channel, bossGroup, workerGroup, applicationGroup, streamIngress, boundPort)
        } catch (ex: Exception) {
            applicationGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
            bossGroup.shutdownGracefully()
            if (!channelStarted) {
                throw ex
            }
            throw IllegalStateException("netty hot-path server failed after channel start", ex)
        }
    }

    private class HotPathHandler(
        private val delegate: PlatformHttpServer
    ) : SimpleChannelInboundHandler<FullHttpRequest>() {
        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            val keepAlive = HttpUtil.isKeepAlive(request)
            val future = try {
                val decoded = QueryStringDecoder(request.uri())
                val hotPathRequest = PlatformHotPathRequest(
                    method = request.method().name(),
                    path = decoded.path(),
                    query = rawQuery(request.uri()),
                    headers = request.headers().toSunHeaders(),
                    body = request.content().toString(StandardCharsets.UTF_8)
                )
                delegate.handleHotPathRequestAsync(hotPathRequest)
            } catch (ex: Exception) {
                java.util.concurrent.CompletableFuture.completedFuture(
                    PlatformHotPathResponse(500, JsonCodec.writeObject("error" to "runtime unavailable", "message" to (ex.message ?: "unknown")))
                )
            }
            future.whenComplete { response, failure ->
                val finalResponse = when {
                    failure != null -> PlatformHotPathResponse(
                        500,
                        JsonCodec.writeObject("error" to "runtime unavailable", "message" to (failure.message ?: "unknown"))
                    )
                    response != null -> response
                    else -> PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "not found"))
                }
                ctx.executor().execute {
                    writeResponse(ctx, keepAlive, finalResponse)
                }
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            if (isClientDisconnect(cause)) {
                ctx.close()
                return
            }
            val response = if (cause is TooLongFrameException) {
                PlatformHotPathResponse(413, JsonCodec.writeObject("error" to "request body too large"))
            } else {
                PlatformHotPathResponse(500, JsonCodec.writeObject("error" to "runtime unavailable", "message" to (cause.message ?: "unknown")))
            }
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            val nettyResponse = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.status),
                Unpooled.wrappedBuffer(bytes)
            )
            response.contentType?.let { nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, it) }
            nettyResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
            ctx.writeAndFlush(nettyResponse).addListener(ChannelFutureListener.CLOSE)
        }

        private fun writeResponse(
            ctx: ChannelHandlerContext,
            keepAlive: Boolean,
            response: PlatformHotPathResponse
        ) {
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            val nettyResponse = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.status),
                Unpooled.wrappedBuffer(bytes)
            )
            response.contentType?.let { nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, it) }
            nettyResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
            if (keepAlive) {
                nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                ctx.writeAndFlush(nettyResponse)
            } else {
                ctx.writeAndFlush(nettyResponse).addListener(ChannelFutureListener.CLOSE)
            }
        }

        private fun rawQuery(uri: String): String? {
            val index = uri.indexOf('?')
            if (index < 0 || index == uri.length - 1) return null
            return uri.substring(index + 1)
        }
    }
}

private fun isClientDisconnect(cause: Throwable): Boolean {
    if (cause is ClosedChannelException) return true
    if (cause is IOException) {
        val message = cause.message?.lowercase() ?: return true
        return "connection reset" in message ||
            "broken pipe" in message ||
            "forcibly closed" in message ||
            "connection aborted" in message
    }
    return false
}

class RunningPlatformNettyServer internal constructor(
    private val channel: io.grpc.netty.shaded.io.netty.channel.Channel,
    private val bossGroup: EventLoopGroup,
    private val workerGroup: EventLoopGroup,
    private val applicationGroup: DefaultEventExecutorGroup,
    private val streamIngress: RunningPlatformStreamIngressServer?,
    val port: Int
) {
    fun stop() {
        streamIngress?.stop()
        channel.close().syncUninterruptibly()
        applicationGroup.shutdownGracefully().syncUninterruptibly()
        workerGroup.shutdownGracefully().syncUninterruptibly()
        bossGroup.shutdownGracefully().syncUninterruptibly()
    }
}

private fun io.grpc.netty.shaded.io.netty.handler.codec.http.HttpHeaders.toSunHeaders(): Headers {
    val headers = Headers()
    for (entry in entries()) {
        headers.add(entry.key, entry.value)
    }
    return headers
}

private fun defaultApplicationThreads(): Int {
    val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return (processors * 2).coerceIn(4, 32)
}

private fun defaultApplicationMaxPendingTasks(): Int = 2_048

private fun namedThreadFactory(prefix: String): ThreadFactory {
    val counter = AtomicInteger(0)
    return ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
