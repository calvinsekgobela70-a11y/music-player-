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
    val mimeType: String
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
