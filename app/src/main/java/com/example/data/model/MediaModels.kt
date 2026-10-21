package com.example.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val artworkUri: Uri?,
    val songCount: Int,
    val year: Int = 0
)

@Immutable
data class Artist(
    val name: String,
    val songCount: Int,
    val albumCount: Int
)

@Immutable
data class Folder(
    val name: String,
    val path: String,
    val songCount: Int
)

@Immutable
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
