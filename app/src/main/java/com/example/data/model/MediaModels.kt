package com.example.data.model

import android.net.Uri

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val artworkUri: Uri?,
    val songCount: Int,
    val year: Int = 0
)

data class Artist(
    val name: String,
    val songCount: Int,
    val albumCount: Int
)

data class Folder(
    val name: String,
    val path: String,
    val songCount: Int
)

data class Playlist(
    val id: Long,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val trackCount: Int = 0
)

enum class SortOrder {
    TITLE_ASC,
    ARTIST_ASC,
    DURATION_DESC,
    DATE_ADDED_DESC
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}
