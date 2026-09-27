package com.friend.ios.phonemic.alwayson.speaker

import com.friend.ios.phonemic.alwayson.data.AlwaysOnSpeakerIdentity
import kotlin.math.sqrt

data class MeMatchResult(
    val identity: String,
    val score: Float?,
    val centroidScore: Float?,
    val bestSampleScore: Float?,
    val enrollmentCount: Int,
)

/** Conservative ME matcher: under-enrollment or ambiguous audio stays UNKNOWN. */
class MeProfileMatcher(
    private val minEnrollmentSamples: Int = 3,
    private val centroidThreshold: Float = 0.64f,
    private val bestSampleThreshold: Float = 0.70f,
) {
    fun match(candidate: FloatArray, enrolled: List<FloatArray>): MeMatchResult {
        val usable = enrolled.filter { it.size == candidate.size && it.isNotEmpty() }
        if (candidate.isEmpty() || usable.size < minEnrollmentSamples) {
            return MeMatchResult(AlwaysOnSpeakerIdentity.UNKNOWN, null, null, null, usable.size)
        }
        val c = normalize(candidate) ?: return MeMatchResult(AlwaysOnSpeakerIdentity.UNKNOWN, null, null, null, usable.size)
        val normalized = usable.mapNotNull(::normalize)
        if (normalized.size < minEnrollmentSamples) {
            return MeMatchResult(AlwaysOnSpeakerIdentity.UNKNOWN, null, null, null, normalized.size)
        }

        val centroidRaw = FloatArray(c.size)
        normalized.forEach { sample -> sample.indices.forEach { i -> centroidRaw[i] += sample[i] } }
        val centroid = normalize(centroidRaw) ?: return MeMatchResult(AlwaysOnSpeakerIdentity.UNKNOWN, null, null, null, normalized.size)
        val centroidScore = cosine(c, centroid)
        val bestScore = normalized.maxOf { cosine(c, it) }
        val isMe = centroidScore >= centroidThreshold && bestScore >= bestSampleThreshold
        return MeMatchResult(
            identity = if (isMe) AlwaysOnSpeakerIdentity.ME else AlwaysOnSpeakerIdentity.UNKNOWN,
            score = minOf(centroidScore, bestScore),
            centroidScore = centroidScore,
            bestSampleScore = bestScore,
            enrollmentCount = normalized.size,
        )
    }

    private fun normalize(v: FloatArray): FloatArray? {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat()
        if (!norm.isFinite() || norm <= 1e-8f) return null
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum.coerceIn(-1f, 1f)
    }
}
