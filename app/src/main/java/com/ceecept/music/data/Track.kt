package com.ceecept.music.data

import android.net.Uri

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
    val trackNumber: Int,
    val year: Int,
    val mimeType: String,
    /** Absolute path when MediaStore still exposes one — used for folder artwork. */
    val filePath: String = "",
    /** MediaStore DATE_ADDED, in seconds. */
    val dateAddedSec: Long = 0L,
    val genre: String = "",
    val composer: String = "",
    val albumArtist: String = ""
)

data class ArtistEntry(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val sample: Track
)

data class AlbumEntry(
    val albumId: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val trackCount: Int,
    val sample: Track
)

data class GenreEntry(val name: String, val trackCount: Int, val sample: Track)

data class ComposerEntry(val name: String, val trackCount: Int, val sample: Track)

data class YearEntry(val year: Int, val trackCount: Int, val sample: Track)

/** Ways the library can be ordered. */
enum class TrackSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DATE_ADDED("Recently added"),
    LAST_PLAYED("Recently played"),
    PLAY_COUNT("Most played"),
    DURATION("Duration"),
    YEAR("Year");

    companion object {
        fun fromName(name: String?): TrackSort =
            entries.firstOrNull { it.name == name } ?: TITLE
    }
}
