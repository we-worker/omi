package com.friend.ios.phonemic.alwayson.processing

import android.content.Context

/** User-facing processing modes from the product plan. */
enum class ProcessingPolicy(val storedValue: String) {
    REALTIME("realtime"),
    MANUAL("manual"),
    CHARGING_ONLY("charging_only"),
    CHARGING_AND_WIFI("charging_and_wifi");

    companion object {
        const val PREF_KEY = "flutter.phoneAlwaysOnProcessingPolicy"
        const val DEFAULT_VALUE = "charging_only"

        fun fromStored(value: String?): ProcessingPolicy =
            entries.firstOrNull { it.storedValue == value } ?: CHARGING_ONLY

        fun fromPreferences(context: Context): ProcessingPolicy {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            return fromStored(prefs.getString(PREF_KEY, DEFAULT_VALUE))
        }
    }
}
