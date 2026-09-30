package com.reef.platform.calcify

internal data class ResolverSettings(
    val generation:Int,
    val sourceTopic:String,
    val sourceTopicId:String,
    val verifiedTopic:String,
    val outputTopic:String,
    val maxPending:Int=200,
    val maxSourceBytes:Int=4*1024*1024,
    val maxTargetBytes:Int=16*1024*1024,
    val maxRowBytes:Int=64*1024,
    val maxWorkPerDrain:Int=200,
) {
    init {
        require(generation>0 && sourceTopic.isNotBlank() && sourceTopicId.isNotBlank())
        require(setOf(sourceTopic,verifiedTopic,outputTopic).size==3)
        require(maxPending>0 && maxSourceBytes>0 && maxTargetBytes>0 && maxRowBytes>0 && maxWorkPerDrain>0)
    }
}

internal data class VenueSourceEntry(val offset:Long,val payload:ByteArray)
internal interface VenueSourceReader:AutoCloseable {
    fun next(cursor:Long,target:Long):VenueSourceEntry?
    fun validateIdentity() {}
}
