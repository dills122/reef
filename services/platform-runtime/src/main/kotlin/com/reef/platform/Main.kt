package com.reef.platform

import com.reef.platform.calcify.CalcifyPipeline
import com.reef.platform.api.PlatformNettyHotPathServer
import com.reef.platform.api.PlatformHttpServer
import com.reef.platform.infrastructure.config.RuntimeEnv

fun main() {
    if (RuntimeEnv.string("CALCIFY_FINITE_BINDING_DIGEST", "").isNotBlank()) {
        // Finite P3 is its own process role: a leftover CALCIFY_STAGE must not silently pick a different mode.
        require(RuntimeEnv.string("CALCIFY_STAGE", "").isBlank()) { "CALCIFY_STAGE must not be set with CALCIFY_FINITE_BINDING_DIGEST" }
        com.reef.platform.calcify.FiniteRuntime.run()
        return
    }
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
