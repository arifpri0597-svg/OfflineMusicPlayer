package com.example.player

import androidx.compose.runtime.Immutable
import com.example.data.model.RepeatMode
import com.example.data.model.Song
import kotlin.math.abs

@Immutable
data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackSpeed: Float = 1.0f,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val isBuffering: Boolean = false,
    val errorMessage: String? = null,
    val mediaArtworkUriString: String? = null,
    val artworkBytes: ByteArray? = null
) {
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedPosition: String
        get() = if (positionMs <= 0L) "0:00" else Song.formatDurationMs(positionMs)

    val formattedDuration: String
        get() = if (durationMs <= 0L) "--:--" else Song.formatDurationMs(durationMs)

    val formattedPlaybackSpeed: String
        get() = formatSpeed(playbackSpeed)

    val formattedPlaybackSpeedButton: String
        get() = formatSpeedButtonLabel(playbackSpeed)

    companion object {
        val PLAYBACK_SPEED_OPTIONS: List<Float> = listOf(
            0.5f,
            0.75f,
            1.0f,
            1.25f,
            1.5f,
            2.0f
        )

        fun formatSpeed(speed: Float): String {
            val safe = speed.coerceIn(0.25f, 3.0f)
            return when {
                abs(safe - 0.5f) < 0.01f -> "0.5×"
                abs(safe - 0.75f) < 0.01f -> "0.75×"
                abs(safe - 1.0f) < 0.01f -> "1×"
                abs(safe - 1.25f) < 0.01f -> "1.25×"
                abs(safe - 1.5f) < 0.01f -> "1.5×"
                abs(safe - 1.75f) < 0.01f -> "1.75×"
                abs(safe - 2.0f) < 0.01f -> "2×"
                safe % 1.0f == 0f -> "${safe.toInt()}×"
                else -> "${safe}×"
            }
        }

        fun formatSpeedButtonLabel(speed: Float): String {
            val safe = speed.coerceIn(0.25f, 3.0f)
            return when {
                abs(safe - 0.5f) < 0.01f -> "0.5X"
                abs(safe - 0.75f) < 0.01f -> "0.75X"
                abs(safe - 1.0f) < 0.01f -> "1.0X"
                abs(safe - 1.25f) < 0.01f -> "1.25X"
                abs(safe - 1.5f) < 0.01f -> "1.5X"
                abs(safe - 1.75f) < 0.01f -> "1.75X"
                abs(safe - 2.0f) < 0.01f -> "2.0X"
                safe % 1.0f == 0f -> "${safe.toInt()}.0X"
                else -> "${safe}X"
            }
        }
    }
}
