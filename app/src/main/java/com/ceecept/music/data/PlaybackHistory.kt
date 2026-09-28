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
    private val liked = HashSet<Long>()
    private val playlists = LinkedHashMap<String, MutableList<Long>>()
    private val djAnalyses = HashMap<Long, DjTrackAnalysis>()

    private val _revision = mutableStateOf(0)

    /** Bumped whenever statistics, likes, playlists, or DJ analysis change. */
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
        prefs.getString(KEY_LIKED, "")?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?.forEach { liked += it }
        decodePlaylists(prefs.getString(KEY_PLAYLISTS, "") ?: "")
        decodeDjAnalyses(prefs.getString(KEY_DJ_ANALYSIS, "") ?: "")
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

    // ------------------------------------------------------------- likes

    fun isLiked(id: Long): Boolean = liked.contains(id)

    fun toggleLike(id: Long): Boolean {
        if (id <= 0) return false
        val nowLiked = if (liked.contains(id)) {
            liked.remove(id)
            false
        } else {
            liked.add(id)
            true
        }
        prefs.edit().putString(KEY_LIKED, liked.joinToString(",")).apply()
        _revision.value = _revision.value + 1
        return nowLiked
    }

    fun likedIds(): List<Long> = liked.toList()

    // ------------------------------------------------------------- playlists

    fun playlistNames(): List<String> = playlists.keys.toList()

    fun playlistIds(name: String): List<Long> = playlists[name]?.toList() ?: emptyList()

    fun createPlaylist(name: String): Boolean {
        val clean = name.trim().take(48)
        if (clean.isBlank() || playlists.containsKey(clean)) return false
        playlists[clean] = mutableListOf()
        flushPlaylists()
        _revision.value = _revision.value + 1
        return true
    }

    fun deletePlaylist(name: String) {
        if (playlists.remove(name) != null) {
            flushPlaylists()
            _revision.value = _revision.value + 1
        }
    }

    fun addToPlaylist(name: String, id: Long) {
        if (id <= 0) return
        val list = playlists.getOrPut(name.trim().take(48).ifBlank { "My Playlist" }) { mutableListOf() }
        if (!list.contains(id)) {
            list += id
            flushPlaylists()
            _revision.value = _revision.value + 1
        }
    }

    fun removeFromPlaylist(name: String, id: Long) {
        val list = playlists[name] ?: return
        if (list.remove(id)) {
            flushPlaylists()
            _revision.value = _revision.value + 1
        }
    }

    private fun flushPlaylists() {
        val encoded = playlists.entries.joinToString(";") { (name, ids) ->
            "${escape(name)}|${ids.take(800).joinToString(",")}"
        }
        prefs.edit().putString(KEY_PLAYLISTS, encoded).apply()
    }

    private fun decodePlaylists(encoded: String) {
        encoded.split(';').forEach { row ->
            if (row.isBlank()) return@forEach
            val parts = row.split('|', limit = 2)
            val name = unescape(parts.getOrNull(0) ?: "").ifBlank { return@forEach }
            val ids = parts.getOrNull(1)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toMutableList()
                ?: mutableListOf()
            playlists[name] = ids
        }
    }

    // ------------------------------------------------------------- DJ Mode analysis cache

    var djMode: Boolean
        get() = prefs.getBoolean(KEY_DJ_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DJ_MODE, value).apply()

    fun djAnalysis(id: Long): DjTrackAnalysis? = djAnalyses[id]

    fun saveDjAnalysis(analysis: DjTrackAnalysis) {
        djAnalyses[analysis.trackId] = analysis
        flushDjAnalyses()
        _revision.value = _revision.value + 1
    }

    private fun flushDjAnalyses() {
        val encoded = djAnalyses.values
            .sortedByDescending { lastPlayed[it.trackId] ?: 0L }
            .take(500)
            .joinToString(";") { a ->
                listOf(
                    a.trackId, a.bpm, a.key, if (a.minor) 1 else 0, a.energy, a.confidence,
                    a.beatMs, a.introCueMs, a.outroCueMs, a.downbeatOffsetMs,
                    if (a.tempoStable) 1 else 0, if (a.mixable) 1 else 0, a.keyConfidence,
                    a.firstOnsetMs, a.introEndMs, a.outroStartMs, a.lastAudibleMs,
                    a.lastVocalEndMs, a.loudnessDb, a.lowEnergy, a.midEnergy, a.highEnergy
                ).joinToString(",")
            }
        prefs.edit().putString(KEY_DJ_ANALYSIS, encoded).apply()
    }

    private fun decodeDjAnalyses(encoded: String) {
        encoded.split(';').forEach { row ->
            if (row.isBlank()) return@forEach
            val p = row.split(',')
            if (p.size < 6) return@forEach
            val id = p[0].toLongOrNull() ?: return@forEach
            val bpm = p[1].toFloatOrNull() ?: return@forEach
            val beat = p.getOrNull(6)?.toLongOrNull()
                ?: (60_000f / bpm.coerceIn(70f, 190f)).toLong()
            val intro = p.getOrNull(7)?.toLongOrNull() ?: 0L
            val outro = p.getOrNull(8)?.toLongOrNull() ?: 0L
            val confidence = p[5].toFloatOrNull() ?: 0f
            val energy = p[4].toFloatOrNull() ?: 0.4f
            djAnalyses[id] = DjTrackAnalysis(
                trackId = id,
                bpm = bpm,
                key = p[2].toIntOrNull() ?: return@forEach,
                minor = p[3] == "1",
                energy = energy,
                confidence = confidence,
                beatMs = beat,
                introCueMs = intro,
                outroCueMs = outro,
                downbeatOffsetMs = p.getOrNull(9)?.toLongOrNull() ?: 0L,
                tempoStable = (p.getOrNull(10)?.toIntOrNull() ?: 1) == 1,
                mixable = (p.getOrNull(11)?.toIntOrNull() ?: if (confidence >= 0.42f) 1 else 0) == 1,
                keyConfidence = p.getOrNull(12)?.toFloatOrNull() ?: confidence,
                firstOnsetMs = p.getOrNull(13)?.toLongOrNull() ?: intro,
                introEndMs = p.getOrNull(14)?.toLongOrNull() ?: intro,
                outroStartMs = p.getOrNull(15)?.toLongOrNull() ?: outro,
                lastAudibleMs = p.getOrNull(16)?.toLongOrNull() ?: 0L,
                lastVocalEndMs = p.getOrNull(17)?.toLongOrNull() ?: outro,
                loudnessDb = p.getOrNull(18)?.toFloatOrNull() ?: -18f,
                lowEnergy = p.getOrNull(19)?.toFloatOrNull() ?: energy,
                midEnergy = p.getOrNull(20)?.toFloatOrNull() ?: energy,
                highEnergy = p.getOrNull(21)?.toFloatOrNull() ?: energy
            )
        }
    }

    private fun escape(value: String): String = value
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace(";", "%3B")

    private fun unescape(value: String): String = value
        .replace("%3B", ";")
        .replace("%7C", "|")
        .replace("%25", "%")

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
        const val KEY_LIKED = "liked"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_DJ_MODE = "dj_mode"
        const val KEY_DJ_ANALYSIS = "dj_analysis"
    }
}
