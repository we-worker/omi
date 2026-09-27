package com.friend.ios.phonemic

import android.util.Log

/**
 * Thin JNI wrapper around libopus' decoder. It mirrors [PhoneMicOpusEncoder] so
 * always-on chunks can be decoded entirely on-device without involving Flutter.
 *
 * One decoder instance is used for one chunk. Calls are confined to the
 * WorkManager processing path and are not made from the AudioRecord thread.
 */
class PhoneMicOpusDecoder private constructor(private var handle: Long) {
    fun decode(packet: ByteArray): ByteArray? {
        if (handle == 0L || packet.isEmpty()) return null
        return try {
            nativeDecodePacket(handle, packet)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeDecodePacket threw", t)
            null
        }
    }

    fun destroy() {
        if (handle == 0L) return
        nativeDestroyDecoder(handle)
        handle = 0L
    }

    companion object {
        private const val TAG = "PhoneMicOpusDecoder"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1

        @Volatile
        private var librariesLoaded = false

        fun create(): PhoneMicOpusDecoder? {
            if (!ensureLibrariesLoaded()) return null
            val handle = try {
                nativeCreateDecoder(SAMPLE_RATE, CHANNELS)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeCreateDecoder threw", t)
                0L
            }
            return if (handle == 0L) null else PhoneMicOpusDecoder(handle)
        }

        @Synchronized
        private fun ensureLibrariesLoaded(): Boolean {
            if (librariesLoaded) return true
            return try {
                System.loadLibrary("opus")
                System.loadLibrary("phonemicopus")
                librariesLoaded = true
                true
            } catch (t: Throwable) {
                Log.e(TAG, "failed to load opus native libraries", t)
                false
            }
        }

        @JvmStatic
        private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long

        @JvmStatic
        private external fun nativeDecodePacket(handle: Long, packet: ByteArray): ByteArray?

        @JvmStatic
        private external fun nativeDestroyDecoder(handle: Long)
    }
}
