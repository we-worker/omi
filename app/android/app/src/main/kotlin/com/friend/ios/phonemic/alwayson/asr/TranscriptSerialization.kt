package com.friend.ios.phonemic.alwayson.asr

import org.json.JSONArray

internal object TranscriptSerialization {
    fun strings(values: List<String>): String = JSONArray().apply {
        values.forEach { put(it) }
    }.toString()

    fun longs(values: List<Long>): String = JSONArray().apply {
        values.forEach { put(it) }
    }.toString()

    /**
     * Keep token timestamps inside the durable audio interval even if a model emits
     * a slightly negative or overhanging timestamp around feature-padding edges.
     */
    fun clampTimestamps(values: List<Long>, durationMs: Long): List<Long> {
        var previous = 0L
        return values.map { raw ->
            val clamped = raw.coerceIn(0L, durationMs.coerceAtLeast(0L)).coerceAtLeast(previous)
            previous = clamped
            clamped
        }
    }
}
