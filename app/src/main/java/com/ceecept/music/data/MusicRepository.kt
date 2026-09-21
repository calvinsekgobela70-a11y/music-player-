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

    private val artCache = LruCache<Long, Bitmap>(64)

    fun findById(id: Long): Track? = synchronized(byId) { byId[id] }

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
                artist = first.artist,
                year = list.maxOf { it.year },
                trackCount = list.size,
                sample = first
            )
        }.sortedBy { it.title.lowercase() }
    }

    fun tracksByArtist(name: String): List<Track> =
        _tracks.value.filter { it.artist == name }.sortedWith(
            compareBy({ it.album }, { it.trackNumber }, { it.title })
        )

    fun tracksByAlbum(albumId: Long): List<Track> =
        _tracks.value.filter { it.albumId == albumId }
            .sortedWith(compareBy({ it.trackNumber }, { it.title }))

    fun refresh() {
        appScope.launch { load() }
    }

    suspend fun load() {
        withContext(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val list = queryTracks()
                synchronized(byId) {
                    byId.clear()
                    list.forEach { byId[it.id] = it }
                }
                _tracks.value = list
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun queryTracks(): List<Track> {
        val result = mutableListOf<Track>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
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
                        mimeType = cursor.getString(mimeCol) ?: ""
                    )
                )
            }
        }
        return result
    }

    private fun String.normalizeArtist(): String =
        if (equals("<unknown>", ignoreCase = true)) "Unknown Artist" else this

    /** Album artwork thumbnail, memory-cached. Null when the file has no embedded art. */
    suspend fun artwork(track: Track, sizePx: Int = 512): Bitmap? {
        artCache.get(track.albumId)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val bmp = context.contentResolver.loadThumbnail(
                        track.uri, Size(sizePx, sizePx), null
                    )
                    artCache.put(track.albumId, bmp)
                    bmp
                } else {
                    @Suppress("DEPRECATION")
                    val artUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), track.albumId
                    )
                    context.contentResolver.openInputStream(artUri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)?.also { bmp ->
                            artCache.put(track.albumId, bmp)
                            bmp
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
