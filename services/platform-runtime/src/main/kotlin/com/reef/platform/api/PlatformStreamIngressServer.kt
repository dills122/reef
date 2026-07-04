package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import io.grpc.netty.shaded.io.netty.bootstrap.ServerBootstrap
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
import io.grpc.netty.shaded.io.netty.handler.codec.string.LineEncoder
import io.grpc.netty.shaded.io.netty.handler.codec.string.LineSeparator
import io.grpc.netty.shaded.io.netty.handler.codec.string.StringDecoder
import io.grpc.netty.shaded.io.netty.handler.codec.string.StringEncoder
import io.grpc.netty.shaded.io.netty.util.CharsetUtil
import java.net.InetSocketAddress

class PlatformStreamIngressServer(
    private val delegate: PlatformHttpServer,
    private val port: Int = RuntimeEnv.int("STREAM_INGRESS_PORT", 8090),
    private val backlog: Int = RuntimeEnv.int("STREAM_INGRESS_BACKLOG", 1024, min = 64),
    private val maxFrameBytes: Int = RuntimeEnv.int("STREAM_INGRESS_MAX_FRAME_BYTES", 1024 * 1024, min = 1024),
    private val bossThreads: Int = RuntimeEnv.int("STREAM_INGRESS_BOSS_THREADS", 1, min = 1),
    private val workerThreads: Int = RuntimeEnv.int("STREAM_INGRESS_WORKER_THREADS", 0, min = 0)
) {
    fun start(): RunningPlatformStreamIngressServer {
        val bossGroup = NioEventLoopGroup(bossThreads)
        val workerGroup = if (workerThreads > 0) NioEventLoopGroup(workerThreads) else NioEventLoopGroup()
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
                            .addLast(io.grpc.netty.shaded.io.netty.handler.codec.LineBasedFrameDecoder(maxFrameBytes))
                            .addLast(StringDecoder(CharsetUtil.UTF_8))
                            .addLast(StringEncoder(CharsetUtil.UTF_8))
                            .addLast(LineEncoder(LineSeparator.UNIX, CharsetUtil.UTF_8))
                            .addLast(StreamIngressHandler(delegate))
                    }
                })
                .bind(port)
                .sync()
                .channel()
            channelStarted = true
            val boundPort = (channel.localAddress() as InetSocketAddress).port
            println("platform-runtime stream-ingress=line-json listening on :$boundPort")
            return RunningPlatformStreamIngressServer(channel, bossGroup, workerGroup, boundPort)
        } catch (ex: Exception) {
            workerGroup.shutdownGracefully()
            bossGroup.shutdownGracefully()
            if (!channelStarted) {
                throw ex
            }
            throw IllegalStateException("stream ingress server failed after channel start", ex)
        }
    }

    private class StreamIngressHandler(
        private val delegate: PlatformHttpServer
    ) : SimpleChannelInboundHandler<String>() {
        override fun channelRead0(ctx: ChannelHandlerContext, frame: String) {
            val body = frame.trim()
            if (body.isBlank()) {
                ctx.writeAndFlush("400").addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
                return
            }
            val started = System.nanoTime()
            val future = try {
                delegate.handleStreamIngressSubmitAsync(body)
            } catch (ex: Exception) {
                java.util.concurrent.CompletableFuture.completedFuture(
                    PlatformHotPathResponse(500, JsonCodec.writeObject("error" to "runtime unavailable", "message" to (ex.message ?: "unknown")))
                )
            }
            future.whenComplete { response, failure ->
                HotPathMetrics.record("streamIngress.total", System.nanoTime() - started)
                val status = when {
                    failure != null -> 503
                    response != null -> response.status
                    else -> 500
                }
                val body = if (response != null && status >= 400 && response.body.isNotBlank()) {
                    "\t${response.body}"
                } else {
                    ""
                }
                ctx.executor().execute {
                    ctx.writeAndFlush("$status$body").addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
                }
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            val status = if (cause is TooLongFrameException) "413" else "500"
            ctx.writeAndFlush(status).addListener(ChannelFutureListener.CLOSE)
        }
    }
}

class RunningPlatformStreamIngressServer internal constructor(
    private val channel: io.grpc.netty.shaded.io.netty.channel.Channel,
    private val bossGroup: EventLoopGroup,
    private val workerGroup: EventLoopGroup,
    val port: Int
) {
    fun stop() {
        channel.close().syncUninterruptibly()
        workerGroup.shutdownGracefully().syncUninterruptibly()
        bossGroup.shutdownGracefully().syncUninterruptibly()
    }
}
