package com.friend.ios.phonemic.alwayson

import com.friend.ios.phonemic.alwayson.storage.AlwaysOnChunkPublisher
import com.friend.ios.phonemic.alwayson.storage.AlwaysOnChunkFileInspector
import com.friend.ios.phonemic.alwayson.storage.AlwaysOnOpusChunkSink
import com.friend.ios.phonemic.alwayson.storage.RecordedAlwaysOnChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class AlwaysOnOpusChunkSinkTest {
    @Test
    fun `speech segment is atomically published with persisted packet duration`() {
        val dir = Files.createTempDirectory("omi-always-on").toFile()
        val published = mutableListOf<RecordedAlwaysOnChunk>()
        val sink = AlwaysOnOpusChunkSink(
            directory = dir,
            publisher = AlwaysOnChunkPublisher { published += it },
            nowMs = { 10_000L },
        )

        sink.onSpeechStart()
        sink.appendPackets(listOf(byteArrayOf(1, 2, 3)))
        sink.appendPackets(listOf(byteArrayOf(4, 5)))
        sink.onSpeechEnd()

        assertEquals(1, published.size)
        val chunk = published.single()
        assertEquals(2L, chunk.opusPacketCount)
        assertEquals(40L, chunk.speechDurationMs)
        assertEquals(9_960L, chunk.startedAtMs)
        assertEquals(10_000L, chunk.endedAtMs)
        assertTrue(chunk.byteCount > 0)
        assertTrue(File(chunk.audioPath).exists())
        assertFalse(File(chunk.audioPath + ".part").exists())
        assertEquals(2L, AlwaysOnChunkFileInspector.inspect(File(chunk.audioPath))?.opusPacketCount)
    }

    @Test
    fun `capture gap finalizes an active segment`() {
        val dir = Files.createTempDirectory("omi-always-on-gap").toFile()
        val published = mutableListOf<RecordedAlwaysOnChunk>()
        val sink = AlwaysOnOpusChunkSink(dir, AlwaysOnChunkPublisher { published += it })

        sink.onSpeechStart()
        sink.appendPackets(listOf(byteArrayOf(7)))
        sink.onCaptureGap()

        assertEquals(1, published.size)
        assertEquals(20L, published.single().speechDurationMs)
    }

    @Test
    fun `part recovery truncates torn packet and promotes complete prefix`() {
        val dir = Files.createTempDirectory("omi-always-on-repair").toFile()
        val part = File(dir, "audio_alwayson_test-id.bin.part")
        RandomAccessFile(part, "rw").use { out ->
            writeFrame(out, byteArrayOf(1, 2, 3))
            // Torn second frame: length says 5, only two bytes reached disk.
            out.write(byteArrayOf(5, 0, 0, 0, 9, 8))
        }

        val promoted = AlwaysOnChunkFileInspector.recoverPartFile(part)
        assertNotNull(promoted)
        assertFalse(part.exists())
        val inspected = AlwaysOnChunkFileInspector.inspect(promoted!!)
        assertNotNull(inspected)
        assertEquals(1L, inspected!!.opusPacketCount)
    }

    private fun writeFrame(out: RandomAccessFile, packet: ByteArray) {
        val size = packet.size
        out.write(byteArrayOf(size.toByte(), (size ushr 8).toByte(), (size ushr 16).toByte(), (size ushr 24).toByte()))
        out.write(packet)
    }
}
