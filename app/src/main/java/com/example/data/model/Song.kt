package com.example.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
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
    val formattedDuration: String = formatDurationMs(durationMs)
    val durationSuffix: String = " • $formattedDuration"
    val artistAlbumSubtitle: String = "$artist • $album"
    val artistDurationSubtitle: String = "$artist • $formattedDuration"
    val folderSuffix: String = if (folderName.isNotBlank()) " • $folderName" else ""
    val contentUriString: String = contentUri.toString()
    val albumArtUriString: String? = albumArtUri?.toString()?.takeIf { it.isNotBlank() }
    val artworkSourceIdentity: String = if (albumArtUriString != null && albumArtUriString != contentUriString) {
        "$albumArtUriString|$contentUriString"
    } else {
        contentUriString
    }

    companion object {
        fun formatDurationMs(durationMs: Long): String {
            if (durationMs <= 0L) return "--:--"
            val totalSeconds = durationMs / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return if (seconds < 10L) "$minutes:0$seconds" else "$minutes:$seconds"
        }
    }
}
