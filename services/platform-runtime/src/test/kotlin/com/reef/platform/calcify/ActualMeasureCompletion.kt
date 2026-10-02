package com.reef.platform.calcify

import java.util.concurrent.atomic.AtomicReference

/** Stdin completion is enabled only for actual-topic measurements. */
internal class ActualMeasureCompletion {
    data class Finish(val expected: Int, val receivedNanos: Long, val observedRecords: Int)
    private val finished=AtomicReference<Finish?>(null)
    private val failure=AtomicReference<String?>(null)
    fun accept(line:String,receivedNanos:Long,observedRecords:Int) {
        val value=if(line.startsWith("finish ")) line.removePrefix("finish ").toIntOrNull() else null
        if(value==null || value<0) {failure.compareAndSet(null,"invalid finish command");return}
        if(!finished.compareAndSet(null,Finish(value,receivedNanos,observedRecords))) failure.compareAndSet(null,"duplicate finish command")
    }
    fun finish()=finished.get()
    fun error()=failure.get()
    fun awaiting(count:Int)=error()==null && (finish()?.let {count<it.expected} ?: true)
}
