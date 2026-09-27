package com.friend.ios.phonemic.alwayson

import com.friend.ios.phonemic.alwayson.storage.AlwaysOnOpusPacketReader
import java.io.EOFException
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlwaysOnOpusPacketReaderTest {
    @Test
    fun readsLengthPrefixedPackets() {
        val file = tempFile()
        file.outputStream().use { out ->
            writePacket(out, byteArrayOf(1, 2, 3))
            writePacket(out, byteArrayOf(9, 8))
        }

        val packets = mutableListOf<ByteArray>()
        val count = AlwaysOnOpusPacketReader.read(file) { packets += it }
        assertEquals(2L, count)
        assertArrayEquals(byteArrayOf(1, 2, 3), packets[0])
        assertArrayEquals(byteArrayOf(9, 8), packets[1])
    }

    @Test
    fun rejectsTruncatedPayload() {
        val file = tempFile()
        file.outputStream().use { out ->
            out.write(byteArrayOf(4, 0, 0, 0, 1, 2))
        }
        assertThrows(EOFException::class.java) {
            AlwaysOnOpusPacketReader.read(file) { }
        }
    }

    @Test
    fun rejectsImpossiblePacketSize() {
        val file = tempFile()
        file.outputStream().use { out ->
            val size = 1276
            out.write(byteArrayOf(
                (size and 0xff).toByte(),
                ((size ushr 8) and 0xff).toByte(),
                0,
                0,
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AlwaysOnOpusPacketReader.read(file) { }
        }
    }

    private fun tempFile(): File = File.createTempFile("always-on-opus", ".bin").apply { deleteOnExit() }

    private fun writePacket(out: java.io.OutputStream, packet: ByteArray) {
        val size = packet.size
        out.write(byteArrayOf(
            (size and 0xff).toByte(),
            ((size ushr 8) and 0xff).toByte(),
            ((size ushr 16) and 0xff).toByte(),
            ((size ushr 24) and 0xff).toByte(),
        ))
        out.write(packet)
    }
}
