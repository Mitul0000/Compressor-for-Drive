package com.digifello.compressorfordrive.ui.Core

import android.content.Context

/** Small settings store shared by the screen and the background worker. */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var level: CompressionLevel
        get() = runCatching { CompressionLevel.valueOf(prefs.getString("level", "")!!) }
            .getOrDefault(CompressionLevel.BALANCED)
        set(value) = prefs.edit().putString("level", value.name).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean("chargingOnly", false)
        set(value) = prefs.edit().putBoolean("chargingOnly", value).apply()
}