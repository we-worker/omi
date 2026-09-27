package com.friend.ios

import android.app.Application
import com.friend.ios.phonemic.alwayson.asr.AlwaysOnAsrRuntime
import io.maido.intercom.IntercomFlutterPlugin

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.INTERCOM_APP_ID.isNotEmpty() && BuildConfig.INTERCOM_ANDROID_API_KEY.isNotEmpty()) {
            IntercomFlutterPlugin.initSdk(this, appId = BuildConfig.INTERCOM_APP_ID, androidApiKey = BuildConfig.INTERCOM_ANDROID_API_KEY)
        }
        // Register lightweight/lazy local processors. ASR and speaker models are not
        // constructed here; WorkManager loads them only when their jobs run.
        AlwaysOnAsrRuntime.install(this)
    }
}