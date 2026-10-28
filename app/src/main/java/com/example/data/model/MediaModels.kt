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
    val year: Int = 0,
    val fallbackAudioUri: Uri? = null,
    val fallbackAudioPath: String = ""
) {
    val stableKey: String = "${id}_${title}"
    val artworkUriString: String? = artworkUri?.toString()?.takeIf { it.isNotBlank() }
    val fallbackAudioUriString: String? = fallbackAudioUri?.toString()?.takeIf { it.isNotBlank() }
    val artworkSourceIdentity: String = when {
        artworkUriString != null && fallbackAudioUriString != null && artworkUriString != fallbackAudioUriString ->
            "$artworkUriString|$fallbackAudioUriString"
        artworkUriString != null -> artworkUriString
        fallbackAudioUriString != null -> fallbackAudioUriString
        fallbackAudioPath.isNotBlank() -> "file:${fallbackAudioPath.trim()}"
        else -> ""
    }
    val albumCacheKey: String = if (artworkSourceIdentity.isNotEmpty()) "256_$artworkSourceIdentity" else ""
    val tracksSubtitle: String = "$artist • $songCount tracks"
    val songCountLabel: String = "$songCount songs"
}

@Immutable
data class Artist(
    val name: String,
    val songCount: Int,
    val albumCount: Int
) {
    val subtitle: String = "$songCount songs • $albumCount albums"
}

@Immutable
data class Folder(
    val name: String,
    val path: String,
    val songCount: Int
) {
    val stableKey: String = "${name}_${path}"
    val subtitle: String = "$songCount audio files"
}

@Immutable
data class Playlist(
    val id: Long,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val trackCount: Int = 0
) {
    val trackCountSubtitle: String = "$trackCount tracks"
}

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
