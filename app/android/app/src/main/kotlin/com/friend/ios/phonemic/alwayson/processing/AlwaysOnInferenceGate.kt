package com.friend.ios.phonemic.alwayson.processing

import kotlinx.coroutines.sync.Mutex

/** Serializes heavyweight local inference so ASR and speaker models do not peak RAM together. */
object AlwaysOnInferenceGate {
    val mutex = Mutex()
}
