package com.ceecept.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

/** Offline/local lyrics: sidecar .lrc/.txt files matched like the uploaded app's library. */
data class LyricsLine(val timeMs: Long, val text: String)

data class LyricsResult(
    val source: String,
    val timed: Boolean,
    val lines: List<LyricsLine>,
    val plainText: String
)

class LyricsRepository {
    private val cache = LinkedHashMap<Long, LyricsResult?>()

    suspend fun lyricsFor(track: Track): LyricsResult? = withContext(Dispatchers.IO) {
        synchronized(cache) { if (cache.containsKey(track.id)) return@withContext cache[track.id] }
        val found = findLyrics(track)
        synchronized(cache) {
            cache[track.id] = found
            if (cache.size > 160) cache.remove(cache.keys.first())
        }
        found
    }

    fun clear() = synchronized(cache) { cache.clear() }

    private fun findLyrics(track: Track): LyricsResult? {
        val file = track.filePath.takeIf { it.isNotBlank() }?.let { File(it) } ?: return null
        val folder = file.parentFile?.takeIf { it.canRead() } ?: return null
        val base = file.nameWithoutExtension
        val title = cleanName(track.title)
        val artistTitle = cleanName("${track.artist} - ${track.title}")
        val candidates = buildList {
            add(File(folder, "$base.lrc")); add(File(folder, "$base.txt"))
            add(File(folder, "${track.title}.lrc")); add(File(folder, "${track.title}.txt"))
            add(File(folder, "${track.artist} - ${track.title}.lrc")); add(File(folder, "${track.artist} - ${track.title}.txt"))
            add(File(folder, "$title.lrc")); add(File(folder, "$title.txt"))
            add(File(folder, "$artistTitle.lrc")); add(File(folder, "$artistTitle.txt"))
            add(File(folder, "lyrics/$base.lrc")); add(File(folder, "lyrics/$base.txt"))
            add(File(folder, "Lyrics/$base.lrc")); add(File(folder, "Lyrics/$base.txt"))
        }.distinctBy { it.absolutePath.lowercase() }
        candidates.firstOrNull { it.isFile && it.length() in 1..512_000L }?.let { return parse(it) }

        // Fuzzy fallback: scan nearby lyric files and prefer title/filename matches.
        val normalizedTitle = title.normalizeKey()
        val normalizedBase = base.normalizeKey()
        return folder.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("lrc", "txt") }
            ?.mapNotNull { f ->
                val key = f.nameWithoutExtension.normalizeKey()
                val score = when {
                    key == normalizedBase -> 100
                    key == normalizedTitle -> 95
                    key.contains(normalizedTitle) || normalizedTitle.contains(key) -> 75
                    key.contains(normalizedBase) || normalizedBase.contains(key) -> 65
                    else -> 0
                }
                if (score > 0 && f.length() in 1..512_000L) score to f else null
            }
            ?.maxByOrNull { it.first }
            ?.second
            ?.let { parse(it) }
    }

    private fun parse(file: File): LyricsResult? = try {
        val text = file.readText(Charsets.UTF_8).ifBlank { file.readText(Charsets.ISO_8859_1) }
        val lines = ArrayList<LyricsLine>()
        val timeRegex = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val offsetMs = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L
        text.lineSequence().forEach { raw ->
            val matches = timeRegex.findAll(raw).toList()
            val lyric = raw.replace(timeRegex, "").trim()
            if (matches.isNotEmpty() && lyric.isNotBlank()) {
                matches.forEach { m ->
                    val min = m.groupValues[1].toLongOrNull() ?: 0L
                    val sec = m.groupValues[2].toLongOrNull() ?: 0L
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100L
                        2 -> frac.toLong() * 10L
                        else -> frac.take(3).toLong()
                    }
                    lines += LyricsLine((min * 60_000L + sec * 1000L + ms + offsetMs).coerceAtLeast(0L), lyric)
                }
            }
        }
        if (lines.isNotEmpty()) {
            LyricsResult(file.name, timed = true, lines = lines.sortedBy { it.timeMs }, plainText = text)
        } else {
            val plain = text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("[") }
                .joinToString("\n")
            if (plain.isBlank()) null else LyricsResult(file.name, timed = false, lines = emptyList(), plainText = plain)
        }
    } catch (e: Exception) {
        null
    }

    private fun cleanName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.normalizeKey(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
}
