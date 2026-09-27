package com.friend.ios.phonemic.alwayson.speaker

data class DiarizedRegion(
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val clusterId: Int,
    val confidence: Float? = null,
)

object ClusterAudioAssembler {
    fun assemble(
        samples: FloatArray,
        sampleRate: Int,
        regions: List<DiarizedRegion>,
        maxDurationMs: Long = 20_000L,
    ): FloatArray {
        require(sampleRate > 0)
        val maxSamples = ((maxDurationMs * sampleRate) / 1000L).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (maxSamples == 0 || samples.isEmpty()) return FloatArray(0)
        val out = FloatArray(maxSamples)
        var written = 0
        for (region in regions.sortedBy { it.startOffsetMs }) {
            if (written >= maxSamples) break
            val start = ((region.startOffsetMs.coerceAtLeast(0L) * sampleRate) / 1000L).toInt().coerceIn(0, samples.size)
            val end = ((region.endOffsetMs.coerceAtLeast(region.startOffsetMs) * sampleRate) / 1000L).toInt().coerceIn(start, samples.size)
            val count = minOf(end - start, maxSamples - written)
            if (count > 0) {
                samples.copyInto(out, written, start, start + count)
                written += count
            }
        }
        return out.copyOf(written)
    }

    fun slice(samples: FloatArray, sampleRate: Int, startOffsetMs: Long, endOffsetMs: Long): FloatArray {
        return assemble(samples, sampleRate, listOf(DiarizedRegion(startOffsetMs, endOffsetMs, 0)), endOffsetMs - startOffsetMs)
    }
}
