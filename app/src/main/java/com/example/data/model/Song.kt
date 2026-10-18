package com.example.data.model

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUri: Uri,
    val albumArtUri: Uri?,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val sizeBytes: Long = 0L,
    val dateAdded: Long = 0L,
    val folderName: String = "",
    val path: String = "",
    val isFavorite: Boolean = false
) {
    val formattedDuration: String
        get() {
            if (durationMs <= 0) return "--:--"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}
