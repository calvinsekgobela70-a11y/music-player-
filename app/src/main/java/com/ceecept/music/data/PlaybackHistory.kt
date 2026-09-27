package com.ceecept.music.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State

/**
 * The app's memory.
 *
 * Remembers what was playing and where, what the queue was, how often each track has
 * been played and when it was last heard. Backed by SharedPreferences because it is
 * written on every track change and read synchronously at startup — a few kilobytes of
 * key/value data, which is exactly what SharedPreferences is for.
 *
 * Nothing here leaves the device.
 */
class PlaybackHistory(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ceecept_history", Context.MODE_PRIVATE)

    /** id -> plays, id -> last played (epoch ms). Kept in memory, flushed on change. */
    private val plays = HashMap<Long, Int>()
    private val lastPlayed = HashMap<Long, Long>()

    private val _revision = mutableStateOf(0)

    /** Bumped whenever the statistics change, so Compose can recompose lists. */
    val revision: State<Int> = _revision

    init {
        prefs.getString(KEY_STATS, "")?.split(';')?.forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(':')
            if (parts.size >= 3) {
                val id = parts[0].toLongOrNull() ?: return@forEach
                plays[id] = parts[1].toIntOrNull() ?: 0
                lastPlayed[id] = parts[2].toLongOrNull() ?: 0L
            }
        }
    }

    // ---------------------------------------------------------------- statistics

    fun playCount(id: Long): Int = plays[id] ?: 0

    fun lastPlayedAt(id: Long): Long = lastPlayed[id] ?: 0L

    /** Called when a track has been listened to for long enough to count. */
    fun recordPlay(id: Long) {
        if (id <= 0) return
        plays[id] = (plays[id] ?: 0) + 1
        lastPlayed[id] = System.currentTimeMillis()
        flushStats()
        _revision.value = _revision.value + 1
    }

    fun recentlyPlayedIds(limit: Int = 25): List<Long> =
        lastPlayed.entries.sortedByDescending { it.value }.take(limit).map { it.key }

    fun mostPlayedIds(limit: Int = 25): List<Long> =
        plays.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            .take(limit).map { it.key }

    fun hasHistory(): Boolean = lastPlayed.isNotEmpty()

    private fun flushStats() {
        // Only the 400 most recently heard tracks are worth keeping.
        val trimmed = lastPlayed.entries.sortedByDescending { it.value }.take(400)
        val encoded = trimmed.joinToString(";") { (id, at) ->
            "$id:${plays[id] ?: 0}:$at"
        }
        prefs.edit().putString(KEY_STATS, encoded).apply()
    }

    // ------------------------------------------------------------- resume state

    var lastTrackId: Long
        get() = prefs.getLong(KEY_TRACK, -1L)
        set(value) = prefs.edit().putLong(KEY_TRACK, value).apply()

    var lastPositionMs: Long
        get() = prefs.getLong(KEY_POSITION, 0L)
        set(value) = prefs.edit().putLong(KEY_POSITION, value).apply()

    var shuffle: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE, value).apply()

    var repeatMode: Int
        get() = prefs.getInt(KEY_REPEAT, 0)
        set(value) = prefs.edit().putInt(KEY_REPEAT, value).apply()

    var lastTab: String
        get() = prefs.getString(KEY_TAB, "library") ?: "library"
        set(value) = prefs.edit().putString(KEY_TAB, value).apply()

    var sortOrder: String
        get() = prefs.getString(KEY_SORT, TrackSort.TITLE.name) ?: TrackSort.TITLE.name
        set(value) = prefs.edit().putString(KEY_SORT, value).apply()

    var sortAscending: Boolean
        get() = prefs.getBoolean(KEY_SORT_ASC, true)
        set(value) = prefs.edit().putBoolean(KEY_SORT_ASC, value).apply()

    var resumeOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_RESUME, true)
        set(value) = prefs.edit().putBoolean(KEY_RESUME, value).apply()

    /** Track ids of the queue that was playing, in order. */
    var queueIds: List<Long>
        get() = prefs.getString(KEY_QUEUE, "")?.split(',')
            ?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        set(value) {
            // A very long queue is not worth persisting in full.
            val capped = if (value.size > 600) value.take(600) else value
            prefs.edit().putString(KEY_QUEUE, capped.joinToString(",")).apply()
        }

    var queueIndex: Int
        get() = prefs.getInt(KEY_QUEUE_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_QUEUE_INDEX, value).apply()

    /** One call at every meaningful playback change. */
    fun saveNowPlaying(trackId: Long, positionMs: Long, index: Int) {
        prefs.edit()
            .putLong(KEY_TRACK, trackId)
            .putLong(KEY_POSITION, positionMs)
            .putInt(KEY_QUEUE_INDEX, index)
            .apply()
    }

    private companion object {
        const val KEY_STATS = "stats"
        const val KEY_TRACK = "last_track"
        const val KEY_POSITION = "last_position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
        const val KEY_TAB = "tab"
        const val KEY_SORT = "sort"
        const val KEY_SORT_ASC = "sort_asc"
        const val KEY_RESUME = "resume"
        const val KEY_QUEUE = "queue"
        const val KEY_QUEUE_INDEX = "queue_index"
    }
}
