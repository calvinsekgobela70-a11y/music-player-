package com.ceecept.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

/** Offline/local lyrics matched like the uploaded app's library scanner: exact sidecar,
 * nested Lyrics folders, fuzzy folder scan, LRC/SRT/plain text parsing and timed output. */
data class LyricsWord(val timeMs: Long, val text: String)

data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val endTimeMs: Long = -1L,
    val words: List<LyricsWord> = emptyList()
)

data class LyricsResult(
    val source: String,
    val timed: Boolean,
    val lines: List<LyricsLine>,
    val plainText: String
)

private data class LyricsCandidate(val file: File, val score: Int)

class LyricsRepository {
    private val cache = LinkedHashMap<Long, LyricsResult?>()
    private val extensions = setOf("lrc", "txt", "srt", "vtt")

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
        val audio = track.filePath.takeIf { it.isNotBlank() }?.let { File(it) } ?: return null
        val folder = audio.parentFile?.takeIf { it.canRead() } ?: return null
        val base = cleanName(audio.nameWithoutExtension)
        val title = cleanName(track.title)
        val artist = cleanName(track.artist)
        val album = cleanName(track.album)
        val artistTitle = cleanName("$artist - $title")

        // Uploaded-app style: exact same-name and title/artist-title sidecars first,
        // in the song folder and common nested Lyrics folders.
        exactFiles(folder, base, title, artistTitle).firstOrNull { isUsableLyricFile(it) }
            ?.let { return parse(it) }

        // Then scan a small local lyrics graph: current folder, nested Lyrics folders,
        // and a sibling/parent Lyrics folder used by many Android music players.
        val keys = listOf(base, title, artistTitle, "$artist$title", "$title$artist", album)
            .map { it.normalizeKey() }
            .filter { it.isNotBlank() }
            .distinct()
        return lyricRoots(folder)
            .flatMap { root -> scanLyrics(root, maxDepth = if (root == folder) 1 else 2) }
            .mapNotNull { f -> scoreCandidate(f, keys)?.let { LyricsCandidate(f, it) } }
            .maxWithOrNull(compareBy<LyricsCandidate> { it.score }.thenByDescending { it.file.length() })
            ?.file
            ?.let { parse(it) }
    }

    private fun exactFiles(folder: File, base: String, title: String, artistTitle: String): List<File> {
        val roots = lyricRoots(folder)
        val names = listOf(base, title, artistTitle).filter { it.isNotBlank() }.distinct()
        return roots.flatMap { root ->
            names.flatMap { name -> extensions.map { ext -> File(root, "$name.$ext") } }
        }.distinctBy { it.absolutePath.lowercase() }
    }

    private fun lyricRoots(folder: File): List<File> = buildList {
        add(folder)
        listOf("lyrics", "Lyrics", ".lyrics", "LRC", "lrc").forEach { add(File(folder, it)) }
        folder.parentFile?.let { parent ->
            listOf("lyrics", "Lyrics", ".lyrics", "LRC", "lrc").forEach { add(File(parent, it)) }
        }
    }.filter { it.isDirectory && it.canRead() }.distinctBy { it.absolutePath.lowercase() }

    private fun scanLyrics(root: File, maxDepth: Int): List<File> {
        val out = ArrayList<File>(64)
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || out.size > 700) return
            val files = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in files) {
                when {
                    f.isFile && isUsableLyricFile(f) -> out += f
                    f.isDirectory && depth < maxDepth && f.name.length < 80 -> walk(f, depth + 1)
                }
                if (out.size > 700) return
            }
        }
        walk(root, 0)
        return out
    }

    private fun isUsableLyricFile(file: File): Boolean =
        file.extension.lowercase() in extensions && file.length() in 1..768_000L

    private fun scoreCandidate(file: File, keys: List<String>): Int? {
        val key = file.nameWithoutExtension.normalizeKey()
        if (key.isBlank()) return null
        val best = keys.maxOfOrNull { wanted ->
            when {
                key == wanted -> 150
                key.contains(wanted) || wanted.contains(key) -> 105
                tokenOverlap(key, wanted) >= 0.72f -> 82
                tokenOverlap(key, wanted) >= 0.52f -> 54
                else -> 0
            }
        } ?: 0
        if (best <= 0) return null
        val extBoost = when (file.extension.lowercase()) {
            "lrc" -> 24
            "srt", "vtt" -> 18
            else -> 6
        }
        val folderBoost = if (file.parentFile?.name?.contains("lyric", ignoreCase = true) == true ||
            file.parentFile?.name?.equals("lrc", ignoreCase = true) == true) 8 else 0
        return best + extBoost + folderBoost
    }

    private fun parse(file: File): LyricsResult? = try {
        val text = readTextFlexible(file)
        when (file.extension.lowercase()) {
            "srt", "vtt" -> parseCueText(file.name, text) ?: parseText(file.name, text)
            "lrc" -> parseLrc(file.name, text) ?: parseText(file.name, text)
            else -> parseLrc(file.name, text) ?: parseCueText(file.name, text) ?: parseText(file.name, text)
        }
    } catch (e: Exception) {
        null
    }

    private fun parseLrc(source: String, text: String): LyricsResult? {
        val lines = ArrayList<LyricsLine>()
        val timeRegex = Regex("\\[(?:(\\d{1,2}):)?(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val enhancedWordTimeRegex = Regex("<(?:(\\d{1,2}):)?(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")
        val offsetMs = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L
        text.lineSequence().forEach { raw ->
            val matches = timeRegex.findAll(raw).toList()
            val wordMatches = enhancedWordTimeRegex.findAll(raw).toList()
            val lyric = raw
                .replace(timeRegex, "")
                .replace(enhancedWordTimeRegex, "")
                .replace(Regex("\\[[a-zA-Z]+:.*?]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (lyric.isBlank()) return@forEach
            val lineTimes = matches.map { parseBracketTime(it.groupValues) }
                .ifEmpty { wordMatches.firstOrNull()?.let { listOf(parseBracketTime(it.groupValues)) } ?: emptyList() }
            if (lineTimes.isNotEmpty()) {
                val words = parseEnhancedWords(raw, offsetMs)
                lineTimes.forEach { t ->
                    lines += LyricsLine((t + offsetMs).coerceAtLeast(0L), lyric, words = words)
                }
            }
        }
        return timedResult(source, lines)
    }

    private fun parseEnhancedWords(raw: String, offsetMs: Long): List<LyricsWord> {
        val wordRegex = Regex("<(?:(\\d{1,2}):)?(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")
        val matches = wordRegex.findAll(raw).toList()
        if (matches.isEmpty()) return emptyList()
        val out = ArrayList<LyricsWord>(matches.size)
        for (i in matches.indices) {
            val start = matches[i].range.last + 1
            val end = matches.getOrNull(i + 1)?.range?.first ?: raw.length
            val token = raw.substring(start, end)
                .replace(wordRegex, "")
                .replace(Regex("\\[[^]]+]"), "")
                .trim()
            if (token.isNotBlank()) {
                out += LyricsWord((parseBracketTime(matches[i].groupValues) + offsetMs).coerceAtLeast(0L), token)
            }
        }
        return out
    }

    private fun parseCueText(source: String, text: String): LyricsResult? {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val blockRegex = Regex(
            "(?ms)^\\s*(?:\\d+\\s*)?\\n?((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[,.]\\d{1,3})\\s*-->\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[,.]\\d{1,3}).*?\\n(.*?)(?=\\n\\s*\\n|\\z)"
        )
        val lines = blockRegex.findAll(normalized).mapNotNull { match ->
            val start = parseCueTime(match.groupValues[1])
            val end = parseCueTime(match.groupValues[2])
            val lyric = match.groupValues[3]
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.all(Char::isDigit) && !it.startsWith("WEBVTT", ignoreCase = true) }
                .joinToString(" ")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\{[^}]+}"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            lyric.takeIf { it.isNotBlank() }?.let { LyricsLine(start, it, endTimeMs = end) }
        }.toList()
        return timedResult(source, lines)
    }

    private fun timedResult(source: String, rawLines: List<LyricsLine>): LyricsResult? {
        if (rawLines.isEmpty()) return null
        val sorted = rawLines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<LyricsLine> { it.timeMs }.thenBy { it.text })
        if (sorted.isEmpty()) return null
        val deduped = ArrayList<LyricsLine>(sorted.size)
        for (line in sorted) {
            val previous = deduped.lastOrNull()
            if (previous != null && previous.timeMs == line.timeMs && previous.text == line.text) continue
            deduped += line
        }
        val withEnds = deduped.mapIndexed { index, line ->
            val nextStart = deduped.getOrNull(index + 1)?.timeMs
            val end = when {
                line.endTimeMs > line.timeMs -> line.endTimeMs
                nextStart != null -> (nextStart - 80L).coerceAtLeast(line.timeMs + 500L)
                else -> line.timeMs + estimateLineDuration(line.text)
            }
            line.copy(endTimeMs = end)
        }
        return LyricsResult(source, timed = true, lines = withEnds, plainText = withEnds.joinToString("\n") { it.text })
    }

    private fun estimateLineDuration(text: String): Long {
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
        return (900L + words * 360L).coerceIn(1_400L, 6_500L)
    }

    private fun parseText(source: String, text: String): LyricsResult? {
        val plain = text.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("[") &&
                    !line.contains("-->") &&
                    !line.all { it.isDigit() }
            }
            .joinToString("\n")
        return if (plain.isBlank()) null else LyricsResult(source, timed = false, lines = emptyList(), plainText = plain)
    }

    private fun parseBracketTime(groups: List<String>): Long {
        val hours = groups.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: 0L
        val min = groups.getOrNull(2)?.toLongOrNull() ?: 0L
        val sec = groups.getOrNull(3)?.toLongOrNull() ?: 0L
        val frac = groups.getOrNull(4).orEmpty()
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100L
            2 -> frac.toLong() * 10L
            else -> frac.take(3).toLong()
        }
        return hours * 3_600_000L + min * 60_000L + sec * 1000L + ms
    }

    private fun parseCueTime(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        val (h, m, secPart) = when (parts.size) {
            3 -> Triple(parts[0].toLongOrNull() ?: 0L, parts[1].toLongOrNull() ?: 0L, parts[2])
            2 -> Triple(0L, parts[0].toLongOrNull() ?: 0L, parts[1])
            else -> Triple(0L, 0L, parts.lastOrNull() ?: "0")
        }
        val secPieces = secPart.split('.', limit = 2)
        val s = secPieces.getOrNull(0)?.toLongOrNull() ?: 0L
        val ms = secPieces.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        return h * 3_600_000L + m * 60_000L + s * 1000L + ms
    }

    private fun readTextFlexible(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { file.readText(Charsets.UTF_16) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: file.readText(Charsets.ISO_8859_1)

    private fun cleanName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokenOverlap(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        val shorter = minOf(a.length, b.length).coerceAtLeast(1)
        var common = 0
        val seen = BooleanArray(b.length)
        for (ch in a) {
            val idx = b.indexOf(ch)
            if (idx >= 0 && !seen[idx]) {
                seen[idx] = true
                common++
            }
        }
        return common.toFloat() / shorter
    }

    private fun String.normalizeKey(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
}
