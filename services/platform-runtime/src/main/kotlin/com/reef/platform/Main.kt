package com.reef.platform

import com.reef.platform.calcify.CalcifyPipeline
import com.reef.platform.api.PlatformNettyHotPathServer
import com.reef.platform.api.PlatformHttpServer
import com.reef.platform.infrastructure.config.RuntimeEnv

fun main() {
    val calcifyStage = RuntimeEnv.string("CALCIFY_STAGE", "").trim()
    if (calcifyStage.isNotEmpty()) {
        CalcifyPipeline.run(calcifyStage)
        return
    }
    when (RuntimeEnv.string("PLATFORM_HTTP_SERVER", "jdk").trim().lowercase()) {
        "netty", "netty-hot-path" -> PlatformNettyHotPathServer().start()
        "jdk", "jdk-httpserver" -> PlatformHttpServer().start()
        else -> throw IllegalArgumentException("Unsupported PLATFORM_HTTP_SERVER")
    }
}
