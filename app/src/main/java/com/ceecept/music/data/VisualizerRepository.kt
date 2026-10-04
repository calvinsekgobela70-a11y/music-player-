package com.ceecept.music.data

import android.content.Context
import kotlin.math.abs

/** Offline MilkDrop preset index imported from the uploaded app. */
class VisualizerRepository(private val context: Context) {
    val presets: List<String> by lazy {
        runCatching {
            context.assets.list("milk_presets")
                ?.filter { it.endsWith(".milk", ignoreCase = true) }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun presetFor(trackId: Long, positionMs: Long): String? {
        val list = presets
        if (list.isEmpty()) return null
        val slot = (positionMs / 30_000L).toInt()
        val idx = abs(((trackId * 1103515245L + slot * 31L) xor (trackId ushr 7)).toInt()) % list.size
        return list[idx].removeSuffix(".milk")
    }
}
