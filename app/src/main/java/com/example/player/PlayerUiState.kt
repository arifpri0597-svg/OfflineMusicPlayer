package com.example.player

import androidx.compose.runtime.Immutable
import com.example.data.model.RepeatMode
import com.example.data.model.Song

@Immutable
data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val isBuffering: Boolean = false,
    val errorMessage: String? = null
) {
    val progress: Float =
        if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedPosition: String =
        if (positionMs <= 0L) "0:00" else Song.formatDurationMs(positionMs)

    val formattedDuration: String =
        if (durationMs <= 0L) "--:--" else Song.formatDurationMs(durationMs)
}
