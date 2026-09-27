package com.friend.ios.phonemic.alwayson.storage

import com.friend.ios.batch.BatchFrameEncoder
import java.io.File
import java.io.RandomAccessFile

/** Parsed metadata for Omi's length-prefixed Opus chunk container. */
data class InspectedAlwaysOnChunkFile(
    val id: String,
    val file: File,
    val byteCount: Long,
    val opusPacketCount: Long,
)

/**
 * Crash-recovery helper for finalized and interrupted always-on files.
 *
 * Final .bin files are only accepted when every length-prefixed packet reaches the
 * exact end of the file. A leftover .part file is first truncated to the complete
 * prefix using Omi's existing [BatchFrameEncoder.recover], then promoted to .bin.
 */
object AlwaysOnChunkFileInspector {
    private val finalName = Regex("^audio_alwayson_([A-Za-z0-9-]+)\\.bin$")
    private val partName = Regex("^audio_alwayson_([A-Za-z0-9-]+)\\.bin\\.part$")

    fun recoverPartFile(part: File): File? {
        val match = partName.matchEntire(part.name) ?: return null
        return try {
            RandomAccessFile(part, "rw").use { out ->
                val completeBytes = BatchFrameEncoder.recover(out)
                if (completeBytes <= 0L) {
                    part.delete()
                    return null
                }
                out.fd.sync()
            }
            val finalFile = File(part.parentFile, "audio_alwayson_${match.groupValues[1]}.bin")
            if (finalFile.exists()) {
                // Prefer the already-published final file; the stale part is disposable.
                part.delete()
                finalFile
            } else if (part.renameTo(finalFile)) {
                finalFile
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun inspect(file: File): InspectedAlwaysOnChunkFile? {
        val match = finalName.matchEntire(file.name) ?: return null
        if (!file.isFile || file.length() <= 4L) return null

        return try {
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                var offset = 0L
                var packets = 0L
                while (length - offset >= 4L) {
                    input.seek(offset)
                    var packetSize = 0L
                    repeat(4) { index ->
                        packetSize = packetSize or (input.readUnsignedByte().toLong() shl (index * 8))
                    }
                    // RFC 6716 caps one Opus packet at 1275 bytes. Rejecting impossible
                    // sizes prevents a corrupted length header from being treated as audio.
                    if (packetSize <= 0L || packetSize > MAX_OPUS_PACKET_BYTES) return null
                    val next = offset + 4L + packetSize
                    if (next > length) return null
                    offset = next
                    packets += 1
                }
                if (offset != length || packets == 0L) return null
                InspectedAlwaysOnChunkFile(
                    id = match.groupValues[1],
                    file = file,
                    byteCount = length,
                    opusPacketCount = packets,
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private const val MAX_OPUS_PACKET_BYTES = 1275L
}
