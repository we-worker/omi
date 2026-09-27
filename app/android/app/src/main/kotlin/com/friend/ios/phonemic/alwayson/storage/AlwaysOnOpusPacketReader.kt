package com.friend.ios.phonemic.alwayson.storage

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/** Reads Omi's little-endian length-prefixed Opus container without decoding it. */
object AlwaysOnOpusPacketReader {
    fun read(file: File, onPacket: (ByteArray) -> Unit): Long {
        var count = 0L
        RandomAccessFile(file, "r").use { input ->
            while (input.filePointer < input.length()) {
                val remaining = input.length() - input.filePointer
                if (remaining < 4L) throw EOFException("truncated Opus length header")
                var packetSize = 0L
                repeat(4) { index ->
                    packetSize = packetSize or (input.readUnsignedByte().toLong() shl (index * 8))
                }
                if (packetSize <= 0L || packetSize > MAX_OPUS_PACKET_BYTES) {
                    throw IllegalArgumentException("invalid Opus packet size: $packetSize")
                }
                if (packetSize > input.length() - input.filePointer) {
                    throw EOFException("truncated Opus payload")
                }
                val packet = ByteArray(packetSize.toInt())
                input.readFully(packet)
                onPacket(packet)
                count += 1
            }
        }
        return count
    }

    const val MAX_OPUS_PACKET_BYTES = 1275L
}
