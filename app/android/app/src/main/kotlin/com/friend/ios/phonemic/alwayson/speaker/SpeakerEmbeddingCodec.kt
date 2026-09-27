package com.friend.ios.phonemic.alwayson.speaker

import java.nio.ByteBuffer
import java.nio.ByteOrder

object SpeakerEmbeddingCodec {
    fun encode(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "speaker embedding byte count must be divisible by 4" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
