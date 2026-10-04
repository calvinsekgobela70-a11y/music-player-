package com.ceecept.music.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline-first music library backed by MediaStore. No network involved.
 */
class MusicRepository(
    private val context: Context,
    private val appScope: CoroutineScope
) {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val byId = mutableMapOf<Long, Track>()

    private val artCache = LruCache<Long, Bitmap>(96)

    /** Album art paths from the MediaStore albums table, resolved once per scan. */
    private val albumArtPaths = mutableMapOf<Long, String>()

    /**
     * Albums probed without success, with the time of the attempt. Unlike a permanent
     * "no art" set this expires: on some devices (notably EMUI) the very first probe
     * can fail while the media provider is still warming up, and a permanent negative
     * cache would then show blank artwork until the app is restarted.
     */
    private val artMisses = mutableMapOf<Long, Long>()
    private val missRetryMs = 1_200L

    fun findById(id: Long): Track? = synchronized(byId) { byId[id] }

    fun findAll(ids: List<Long>): List<Track> = synchronized(byId) {
        ids.mapNotNull { byId[it] }
    }

    fun artists(): List<ArtistEntry> {
        return _tracks.value.groupBy { it.artist }.map { (name, list) ->
            ArtistEntry(
                name = name,
                trackCount = list.size,
                albumCount = list.map { it.albumId }.toSet().size,
                sample = list.first()
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun albums(): List<AlbumEntry> {
        return _tracks.value.groupBy { it.albumId }.map { (albumId, list) ->
            val first = list.first()
            AlbumEntry(
                albumId = albumId,
                title = first.album,
                artist = first.albumArtist.ifBlank { first.artist },
                year = list.maxOf { it.year },
                trackCount = list.size,
                sample = first
            )
        }.sortedBy { it.title.lowercase() }
    }

    fun genres(): List<GenreEntry> = _tracks.value
        .filter { it.genre.isNotBlank() }
        .groupBy { it.genre }
        .map { (name, list) -> GenreEntry(name, list.size, list.first()) }
        .sortedBy { it.name.lowercase() }

    fun composers(): List<ComposerEntry> = _tracks.value
        .filter { it.composer.isNotBlank() }
        .groupBy { it.composer }
        .map { (name, list) -> ComposerEntry(name, list.size, list.first()) }
        .sortedBy { it.name.lowercase() }

    fun years(): List<YearEntry> = _tracks.value
        .filter { it.year > 0 }
        .groupBy { it.year }
        .map { (year, list) -> YearEntry(year, list.size, list.first()) }
        .sortedByDescending { it.year }

    fun tracksByGenre(name: String): List<Track> =
        _tracks.value.filter { it.genre == name }.sortedWith(compareBy({ it.artist }, { it.album }, { it.trackNumber }))

    fun tracksByComposer(name: String): List<Track> =
        _tracks.value.filter { it.composer == name }.sortedWith(compareBy({ it.artist }, { it.album }, { it.trackNumber }))

    fun tracksByYear(year: Int): List<Track> =
        _tracks.value.filter { it.year == year }.sortedWith(compareBy({ it.artist }, { it.album }, { it.trackNumber }))

    fun tracksByArtist(name: String): List<Track> =
        _tracks.value.filter { it.artist == name }.sortedWith(
            compareBy({ it.album }, { it.trackNumber }, { it.title })
        )

    fun tracksByAlbum(albumId: Long): List<Track> =
        _tracks.value.filter { it.albumId == albumId }
            .sortedWith(compareBy({ it.trackNumber }, { it.title }))

    /** Apply one of the library sort orders. History is needed for the play-based ones. */
    fun sorted(
        list: List<Track>,
        sort: TrackSort,
        ascending: Boolean,
        history: PlaybackHistory?
    ): List<Track> {
        val base = when (sort) {
            TrackSort.TITLE -> list.sortedBy { it.title.lowercase() }
            TrackSort.ARTIST -> list.sortedWith(
                compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNumber })
            )
            TrackSort.ALBUM -> list.sortedWith(
                compareBy({ it.album.lowercase() }, { it.trackNumber }, { it.title.lowercase() })
            )
            TrackSort.DATE_ADDED -> list.sortedByDescending { it.dateAddedSec }
            TrackSort.DURATION -> list.sortedBy { it.durationMs }
            TrackSort.YEAR -> list.sortedByDescending { it.year }
            TrackSort.LAST_PLAYED -> list.sortedByDescending { history?.lastPlayedAt(it.id) ?: 0L }
            TrackSort.PLAY_COUNT -> list.sortedByDescending { history?.playCount(it.id) ?: 0 }
        }
        return if (ascending) base else base.reversed()
    }

    fun refresh() {
        appScope.launch { load() }
    }

    suspend fun load() {
        withContext(Dispatchers.IO) {
            _isLoading.value = true
            try {
                loadAlbumArtPaths()
                val list = queryTracks(includeRichTags = true).ifEmpty { queryTracks(includeRichTags = false) }
                synchronized(byId) {
                    byId.clear()
                    list.forEach { byId[it.id] = it }
                }
                synchronized(artMisses) { artMisses.clear() }
                _tracks.value = list
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun queryTracks(includeRichTags: Boolean): List<Track> {
        val result = mutableListOf<Track>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val baseProjection = listOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val richProjection = if (includeRichTags) listOf("genre", "composer", "album_artist") else emptyList()
        val projection = (baseProjection + richProjection).toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        try {
            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val addedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val genreCol = cursor.getColumnIndex("genre")
            val composerCol = cursor.getColumnIndex("composer")
            val albumArtistCol = cursor.getColumnIndex("album_artist")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val duration = cursor.getLong(durationCol)
                if (duration < 3000) continue // skip ringtones / fragments
                val rawArtist = cursor.getString(artistCol) ?: ""
                val rawAlbum = cursor.getString(albumCol) ?: ""
                result.add(
                    Track(
                        id = id,
                        title = cursor.getString(titleCol) ?: "Unknown Title",
                        artist = rawArtist.ifBlank { "Unknown Artist" }.normalizeArtist(),
                        album = rawAlbum.ifBlank { "Unknown Album" },
                        albumId = cursor.getLong(albumIdCol),
                        durationMs = duration,
                        uri = ContentUris.withAppendedId(uri, id),
                        trackNumber = cursor.getInt(trackCol),
                        year = cursor.getInt(yearCol),
                        mimeType = cursor.getString(mimeCol) ?: "",
                        filePath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else "",
                        dateAddedSec = if (addedCol >= 0) cursor.getLong(addedCol) else 0L,
                        genre = if (genreCol >= 0) cursor.getString(genreCol)?.normalizeUnknown().orEmpty() else "",
                        composer = if (composerCol >= 0) cursor.getString(composerCol)?.normalizeUnknown().orEmpty() else "",
                        albumArtist = if (albumArtistCol >= 0) cursor.getString(albumArtistCol)?.normalizeUnknown().orEmpty() else ""
                    )
                )
            }
            }
        } catch (e: Exception) {
            if (!includeRichTags) com.ceecept.music.CrashReporter.recordSoft(context, "MusicRepository.queryTracks", e)
        }
        return result
    }

    /**
     * The albums table still carries a plain file path to the cached cover on most
     * devices, including EMUI, and reading it is far cheaper and far more reliable
     * than asking the media provider for a thumbnail.
     */
    private fun loadAlbumArtPaths() {
        albumArtPaths.clear()
        try {
            @Suppress("DEPRECATION")
            val projection = arrayOf(
                MediaStore.Audio.Albums._ID,
                MediaStore.Audio.Albums.ALBUM_ART
            )
            context.contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, projection, null, null, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
                val artCol = c.getColumnIndex("album_art")
                if (artCol < 0) return
                while (c.moveToNext()) {
                    val path = c.getString(artCol)
                    if (!path.isNullOrBlank()) albumArtPaths[c.getLong(idCol)] = path
                }
            }
        } catch (e: Exception) {
            // Android 11+ removed the column on some builds; the other sources cover it.
        }
    }

    private fun String.normalizeArtist(): String =
        if (equals("<unknown>", ignoreCase = true)) "Unknown Artist" else this

    private fun String.normalizeUnknown(): String =
        trim().takeUnless { it.isBlank() || it.equals("<unknown>", ignoreCase = true) } ?: ""

    /**
     * Album artwork, memory-cached.
     *
     * Probes every offline source there is, cheapest and most reliable first, because
     * no single one works on every device: the MediaStore album-art file, the legacy
     * album-art provider, the file's own tags (read through a file descriptor, which
     * some OEM builds need), the tags through a content URI, a cover image sitting next
     * to the track, and finally the provider thumbnail API.
     *
     * Null only when the album genuinely carries no picture anywhere.
     */
    suspend fun artwork(track: Track, sizePx: Int = 512): Bitmap? {
        val key = if (track.albumId > 0) track.albumId else -track.id
        artCache.get(key)?.let { return it }
        synchronized(artMisses) {
            val missedAt = artMisses[key]
            if (missedAt != null && System.currentTimeMillis() - missedAt < missRetryMs) return null
        }
        return withContext(Dispatchers.IO) {
            val bmp = albumArtFile(track, sizePx)
                ?: legacyAlbumArt(track, sizePx)
                ?: providerThumbnail(track, sizePx)
                ?: embeddedViaDescriptor(track, sizePx)
                ?: embeddedViaPath(track, sizePx)
                ?: embeddedViaUri(track, sizePx)
                ?: folderArt(track, sizePx)
                ?: siblingArt(track, sizePx)
                ?: audioThumbnail(track, sizePx)
            if (bmp != null) {
                artCache.put(key, bmp)
                synchronized(artMisses) { artMisses.remove(key) }
            } else if (sizePx == 512) {
                // Do not negative-cache failed oversized decodes — or tiny song-list
                // probes. Some EMUI builds fail one route/size while 512 succeeds later;
                // only a full 512 px probe is authoritative enough to delay retries.
                synchronized(artMisses) { artMisses[key] = System.currentTimeMillis() }
            }
            bmp
        }
    }

    /** The cover file the media scanner already extracted for this album. */
    private fun albumArtFile(track: Track, sizePx: Int): Bitmap? = try {
        val path = albumArtPaths[track.albumId]
        if (path.isNullOrBlank()) null else decodeFileSampled(path, sizePx)
    } catch (e: Exception) {
        null
    }

    /** content://media/external/audio/albumart — served on many devices, all API levels. */
    private fun legacyAlbumArt(track: Track, sizePx: Int): Bitmap? = try {
        if (track.albumId <= 0) {
            null
        } else {
            @Suppress("DEPRECATION")
            val artUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), track.albumId
            )
            context.contentResolver.openInputStream(artUri)?.use { stream ->
                decodeStreamSampled(stream.readBytes(), sizePx)
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Tags read through a file descriptor. `setDataSource(context, uri)` fails on a
     * number of OEM builds where the same file opens perfectly through a descriptor,
     * which is the usual reason artwork "just doesn't show" on one particular phone.
     */
    private fun embeddedViaDescriptor(track: Track, sizePx: Int): Bitmap? = try {
        context.contentResolver.openFileDescriptor(track.uri, "r")?.use { pfd ->
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor)
                retriever.embeddedPicture?.let { decodeSampled(it, sizePx) }
            } finally {
                retriever.release()
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Reads art straight out of the file path when MediaStore still exposes DATA. */
    private fun embeddedViaPath(track: Track, sizePx: Int): Bitmap? = try {
        if (track.filePath.isBlank()) {
            null
        } else {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(track.filePath)
                retriever.embeddedPicture?.let { bytes -> decodeSampled(bytes, sizePx) }
            } finally {
                retriever.release()
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Reads art straight out of the file's tags (ID3 APIC / FLAC picture / MP4 covr). */
    private fun embeddedViaUri(track: Track, sizePx: Int): Bitmap? = try {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, track.uri)
            retriever.embeddedPicture?.let { bytes -> decodeSampled(bytes, sizePx) }
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        null
    }

    /** cover.jpg / folder.jpg / Track Name.jpg sitting in the same directory as the track. */
    private fun folderArt(track: Track, sizePx: Int): Bitmap? {
        return try {
            val audioFile = track.filePath.takeIf { it.isNotBlank() }?.let { File(it) }
            val parent = audioFile?.parentFile
            if (parent == null || !parent.canRead()) {
                null
            } else {
                val base = audioFile.nameWithoutExtension
                val names = listOf(
                    "$base.jpg", "$base.jpeg", "$base.png", "$base.webp",
                    "cover.jpg", "cover.png", "cover.jpeg", "cover.webp",
                    "folder.jpg", "folder.png", "folder.jpeg", "folder.webp",
                    "album.jpg", "album.png", "album.jpeg", "album.webp",
                    "albumart.jpg", "albumart.png", "front.jpg", "front.png",
                    "artwork.jpg", "artwork.png", "Cover.jpg", "Folder.jpg", ".folder.jpg", ".folder.png"
                )
                for (n in names) {
                    val f = File(parent, n)
                    if (f.isFile && f.length() > 0) {
                        val bmp = decodeFileSampled(f.absolutePath, sizePx)
                        if (bmp != null) return bmp
                    }
                }
                val imageExts = setOf("jpg", "jpeg", "png", "webp")
                fun rank(file: File): Int {
                    val n = file.nameWithoutExtension.lowercase()
                    val b = base.lowercase()
                    return when {
                        n == b -> 0
                        n == "cover" || n == "folder" || n == "front" -> 1
                        "cover" in n || "folder" in n || "front" in n -> 2
                        "album" in n || "artwork" in n || "art" in n -> 3
                        else -> 99
                    }
                }
                parent.listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.length() > 0 && it.extension.lowercase() in imageExts }
                    ?.map { rank(it) to it }
                    ?.filter { it.first < 99 }
                    ?.sortedBy { it.first }
                    ?.firstOrNull()
                    ?.second
                    ?.let { decodeFileSampled(it.absolutePath, sizePx) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Try another track from the same album/folder when MediaStore only exposes art on one file. */
    private fun siblingArt(track: Track, sizePx: Int): Bitmap? {
        return try {
            val folder = track.filePath.takeIf { it.isNotBlank() }?.let { File(it).parent }
            val siblings = _tracks.value.asSequence()
                .filter { it.id != track.id }
                .filter {
                    (track.albumId > 0 && it.albumId == track.albumId) ||
                        (it.album.equals(track.album, ignoreCase = true) && it.artist.equals(track.artist, ignoreCase = true)) ||
                        (folder != null && it.filePath.takeIf { p -> p.isNotBlank() }?.let { p -> File(p).parent } == folder)
                }
                .take(10)
                .toList()
            for (s in siblings) {
                val bmp = embeddedViaDescriptor(s, sizePx)
                    ?: embeddedViaPath(s, sizePx)
                    ?: embeddedViaUri(s, sizePx)
                    ?: folderArt(s, sizePx)
                    ?: audioThumbnail(s, sizePx)
                if (bmp != null) return bmp
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** MediaProvider album thumbnail (fast, size-capped). API 29+ expects the Albums URI. */
    private fun providerThumbnail(track: Track, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29 && track.albumId > 0) {
            val albumUri = ContentUris.withAppendedId(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                track.albumId
            )
            context.contentResolver.loadThumbnail(albumUri, Size(sizePx, sizePx), null)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** Last-chance file thumbnail. Some providers attach art to the audio item itself. */
    private fun audioThumbnail(track: Track, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(track.uri, Size(sizePx, sizePx), null)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** Downsamples embedded art so list rows never hold multi-megapixel bitmaps. */
    private fun decodeSampled(bytes: ByteArray, sizePx: Int): Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, sizePx)
            }
        )
    } catch (e: Exception) {
        null
    }

    private fun decodeStreamSampled(bytes: ByteArray, sizePx: Int): Bitmap? = decodeSampled(bytes, sizePx)

    private fun decodeFileSampled(path: String, sizePx: Int): Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) {
            null
        } else {
            android.graphics.BitmapFactory.decodeFile(
                path,
                android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, sizePx)
                }
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }
}
