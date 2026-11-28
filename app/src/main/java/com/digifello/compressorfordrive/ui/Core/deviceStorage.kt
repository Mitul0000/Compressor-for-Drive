package com.digifello.compressorfordrive.ui.Core

import android.os.Environment
import android.os.StatFs

data class DeviceStorage(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)

    companion object {
        fun read(): DeviceStorage = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            DeviceStorage(stat.totalBytes, stat.availableBytes)
        } catch (_: Exception) {
            DeviceStorage(0, 0)
        }
    }
}