package com.friend.ios.phonemic.alwayson.speaker

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SpeakerEmbeddingCodecTest {
    @Test
    fun roundTripsLittleEndianFloat32() {
        val input = floatArrayOf(-1f, 0f, .125f, 1f)
        assertArrayEquals(input, SpeakerEmbeddingCodec.decode(SpeakerEmbeddingCodec.encode(input)), 0f)
    }
}
