package com.reef.platform.admin

fun main(args: Array<String>) {
    val output = AdminCliAdapter().execute(args.toList())
    println(output)
}
