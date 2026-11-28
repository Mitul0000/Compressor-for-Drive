package com.digifello.compressorfordrive.ui.Core

import android.content.Context

enum class Scope { ALL, NEW_ONLY }

/** Small settings store shared by the screen and the background workers. */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var level: CompressionLevel
        get() = runCatching { CompressionLevel.valueOf(prefs.getString("level", "")!!) }
            .getOrDefault(CompressionLevel.BALANCED)
        set(value) = prefs.edit().putString("level", value.name).apply()

    var scope: Scope
        get() = runCatching { Scope.valueOf(prefs.getString("scope", "")!!) }.getOrDefault(Scope.ALL)
        set(value) = prefs.edit().putString("scope", value.name).apply()

    /** Only files added after this moment (epoch seconds) count in [Scope.NEW_ONLY]. */
    var sinceSeconds: Long
        get() = prefs.getLong("since", 0L)
        set(value) = prefs.edit().putLong("since", value).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean("chargingOnly", false)
        set(value) = prefs.edit().putBoolean("chargingOnly", value).apply()

    var dailyCheck: Boolean
        get() = prefs.getBoolean("dailyCheck", false)
        set(value) = prefs.edit().putBoolean("dailyCheck", value).apply()
}