package com.friend.ios.phonemic.alwayson

import com.friend.ios.phonemic.alwayson.asr.TranscriptSerialization
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptSerializationTest {
    @Test
    fun timestampsAreClampedAndMonotonic() {
        assertEquals(
            listOf(0L, 120L, 120L, 900L, 1000L),
            TranscriptSerialization.clampTimestamps(
                listOf(-10L, 120L, 100L, 900L, 1100L),
                durationMs = 1000L,
            ),
        )
    }

    @Test
    fun serializesCompactJsonArrays() {
        assertEquals("[\"你\",\"好\"]", TranscriptSerialization.strings(listOf("你", "好")))
        assertEquals("[120,450]", TranscriptSerialization.longs(listOf(120L, 450L)))
    }
}
