package com.friend.ios.phonemic.alwayson.asr

import com.friend.ios.phonemic.PhoneMicOpusDecoder
import com.friend.ios.phonemic.alwayson.storage.AlwaysOnOpusPacketReader
import java.io.File

/** Fully decoded PCM passed to sherpa. Samples are normalized to [-1, 1]. */
data class DecodedAlwaysOnAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val decodedPacketCount: Long,
)

fun interface AlwaysOnAudioDecoder {
    fun decode(file: File): DecodedAlwaysOnAudio
}

class NativeOpusAlwaysOnAudioDecoder : AlwaysOnAudioDecoder {
    override fun decode(file: File): DecodedAlwaysOnAudio {
        val decoder = PhoneMicOpusDecoder.create()
            ?: throw IllegalStateException("could not create native Opus decoder")
        val samples = FloatArrayBuilder()
        var packetCount = 0L
        try {
            AlwaysOnOpusPacketReader.read(file) { packet ->
                val pcm = decoder.decode(packet)
                    ?: throw IllegalStateException("Opus packet decode failed at packet $packetCount")
                if (pcm.size % 2 != 0) throw IllegalStateException("decoder returned odd PCM byte count")
                var index = 0
                while (index < pcm.size) {
                    val lo = pcm[index].toInt() and 0xff
                    val hi = pcm[index + 1].toInt()
                    val sample = ((hi shl 8) or lo).toShort().toInt()
                    samples.add((sample / 32768.0f).coerceIn(-1.0f, 1.0f))
                    index += 2
                }
                packetCount += 1
            }
        } finally {
            decoder.destroy()
        }
        return DecodedAlwaysOnAudio(
            samples = samples.toArray(),
            sampleRate = SAMPLE_RATE,
            decodedPacketCount = packetCount,
        )
    }

    private class FloatArrayBuilder(initialCapacity: Int = 16_000) {
        private var values = FloatArray(initialCapacity)
        private var size = 0

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf((values.size * 2).coerceAtLeast(1))
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
