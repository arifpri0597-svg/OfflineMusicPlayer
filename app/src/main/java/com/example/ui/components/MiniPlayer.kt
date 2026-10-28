package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.RepeatMode
import com.example.player.PlayerUiState
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonPurple

private val MiniPlayerShape = RoundedCornerShape(20.dp)
private val MiniArtworkShape = RoundedCornerShape(13.dp)
private val MiniArtworkBrush = Brush.linearGradient(colors = listOf(ElectricViolet, NeonPink))
private val MiniPlayButtonBrush = Brush.linearGradient(colors = listOf(NeonPink, NeonPurple))

@Composable
fun MiniPlayer(
    playerState: PlayerUiState,
    progressFlow: kotlinx.coroutines.flow.StateFlow<PlayerUiState>? = null,
    isFavorite: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onCycleRepeatMode: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onExpandNowPlaying: () -> Unit,
    modifier: Modifier = Modifier
) {
    val song = playerState.currentSong
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    AnimatedVisibility(
        visible = song != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        if (song == null) return@AnimatedVisibility

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(MiniPlayerShape)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragAccumulator > 60f) {
                                onSkipPrevious()
                            } else if (dragAccumulator < -60f) {
                                onSkipNext()
                            }
                            dragAccumulator = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            dragAccumulator += dragAmount
                        }
                    )
                }
                .clickable(onClick = onExpandNowPlaying)
                .testTag("mini_player_overlay"),
            shape = MiniPlayerShape,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MiniPlayerProgressBar(
                    fallbackProgress = playerState.progress,
                    progressFlow = progressFlow
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MiniPlayerArtwork(
                        albumArtUriString = song.albumArtUriString ?: playerState.mediaArtworkUriString,
                        fallbackUriString = song.contentUriString,
                        fallbackPath = song.path,
                        artworkBytes = playerState.artworkBytes,
                        isBuffering = playerState.isBuffering
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("mini_player_title")
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            MiniPlayerPositionText(
                                fallbackPositionMs = playerState.positionMs,
                                progressFlow = progressFlow
                            )
                        }
                    }

                    MiniPlayerControls(
                        isPlaying = playerState.isPlaying,
                        isShuffleEnabled = playerState.isShuffleEnabled,
                        repeatMode = playerState.repeatMode,
                        onToggleShuffle = onToggleShuffle,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipNext = onSkipNext,
                        onCycleRepeatMode = onCycleRepeatMode
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerProgressBar(
    fallbackProgress: Float,
    progressFlow: kotlinx.coroutines.flow.StateFlow<PlayerUiState>?
) {
    if (progressFlow != null) {
        val liveState = progressFlow.collectAsState()
        LinearProgressIndicator(
            progress = { liveState.value.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .testTag("mini_player_progress"),
            color = NeonPink,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round
        )
    } else {
        LinearProgressIndicator(
            progress = { fallbackProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .testTag("mini_player_progress"),
            color = NeonPink,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
private fun MiniPlayerPositionText(
    fallbackPositionMs: Long,
    progressFlow: kotlinx.coroutines.flow.StateFlow<PlayerUiState>?
) {
    val currentSecond = if (progressFlow != null) {
        val liveState = progressFlow.collectAsState()
        val derivedSecond by remember {
            derivedStateOf { liveState.value.positionMs / 1000L }
        }
        derivedSecond
    } else {
        fallbackPositionMs / 1000L
    }
    val displayLabel = remember(currentSecond) {
        " • ${com.example.data.model.Song.formatDurationMs(currentSecond * 1000L)}"
    }
    Text(
        text = displayLabel,
        style = MaterialTheme.typography.labelSmall,
        color = NeonPink
    )
}

@Composable
private fun MiniPlayerArtwork(
    albumArtUriString: String?,
    fallbackUriString: String? = null,
    fallbackPath: String? = null,
    artworkBytes: ByteArray? = null,
    isBuffering: Boolean
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(MiniArtworkShape)
            .background(MiniArtworkBrush),
        contentAlignment = Alignment.Center
    ) {
        FastArtworkImage(
            artworkUri = albumArtUriString ?: fallbackUriString,
            cacheKeyPrefix = "thumb",
            sizePx = 144,
            contentDescription = "Mini player artwork",
            fallbackUri = fallbackUriString,
            fallbackPath = fallbackPath,
            artworkBytes = artworkBytes,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        if (isBuffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerControls(
    isPlaying: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onToggleShuffle: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onCycleRepeatMode: () -> Unit
) {
    val shuffleColor by animateColorAsState(
        targetValue = if (isShuffleEnabled) NeonPink else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        label = "mini_shuffle_color"
    )
    IconButton(
        onClick = onToggleShuffle,
        modifier = Modifier
            .size(34.dp)
            .testTag("mini_player_shuffle_btn")
    ) {
        Icon(
            imageVector = Icons.Default.Shuffle,
            contentDescription = if (isShuffleEnabled) "Shuffle On" else "Shuffle Off",
            tint = shuffleColor,
            modifier = Modifier.size(18.dp)
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(NeonPink, NeonPurple)
                )
            )
            .clickable(onClick = onTogglePlayPause)
            .testTag("mini_player_play_pause_btn")
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }

    IconButton(
        onClick = onSkipNext,
        modifier = Modifier
            .size(34.dp)
            .testTag("mini_player_next_btn")
    ) {
        Icon(
            imageVector = Icons.Default.SkipNext,
            contentDescription = "Next Track",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(21.dp)
        )
    }

    val repeatIcon = when (repeatMode) {
        RepeatMode.ONE -> Icons.Default.RepeatOne
        else -> Icons.Default.Repeat
    }
    val isRepeatActive = repeatMode != RepeatMode.OFF
    val repeatColor by animateColorAsState(
        targetValue = if (isRepeatActive) NeonPink else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        label = "mini_repeat_color"
    )
    IconButton(
        onClick = onCycleRepeatMode,
        modifier = Modifier
            .size(34.dp)
            .testTag("mini_player_repeat_btn")
    ) {
        Icon(
            imageVector = repeatIcon,
            contentDescription = "Repeat: $repeatMode",
            tint = repeatColor,
            modifier = Modifier.size(18.dp)
        )
    }
}

