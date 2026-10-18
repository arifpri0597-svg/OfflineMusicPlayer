package com.example.player

import com.example.data.model.RepeatMode
import com.example.data.model.Song

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
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedPosition: String
        get() {
            if (positionMs <= 0) return "0:00"
            val totalSeconds = positionMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }

    val formattedDuration: String
        get() {
            if (durationMs <= 0) return "--:--"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}
