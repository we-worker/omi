package com.friend.ios.phonemic.alwayson.asr

/** Model-independent result stored by the processing pipeline. */
data class AlwaysOnAsrResult(
    val text: String,
    val tokens: List<String>,
    val tokenTimestampsMs: List<Long>,
    val language: String?,
    val emotion: String?,
    val event: String?,
    val modelId: String,
)

interface AlwaysOnAsrEngine {
    fun transcribe(audio: DecodedAlwaysOnAudio): AlwaysOnAsrResult
    fun close()
}
