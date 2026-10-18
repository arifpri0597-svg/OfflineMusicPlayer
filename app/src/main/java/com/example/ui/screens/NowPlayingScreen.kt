package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.RepeatMode
import com.example.player.PlayerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerState: PlayerUiState,
    isFavorite: Boolean,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekRelative: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onJumpToQueueItem: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onCollapse()
    }

    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragPositionRatio by remember { mutableFloatStateOf(0f) }
    var showQueueSheet by remember { mutableStateOf(false) }

    val song = playerState.currentSong ?: return

    Surface(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("now_playing_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top App Bar Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.testTag("collapse_now_playing_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PLAYING FROM LIBRARY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = song.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.testTag("now_playing_fav_btn")
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showQueueSheet = true },
                        modifier = Modifier.testTag("now_playing_queue_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Large Hero Album Artwork
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = "Album art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(96.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Track Title & Artist
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress Slider & Timestamps
            Column(modifier = Modifier.fillMaxWidth()) {
                val currentPositionRatio = if (isDraggingSlider) {
                    dragPositionRatio
                } else {
                    playerState.progress
                }

                Slider(
                    value = currentPositionRatio,
                    onValueChange = { ratio ->
                        isDraggingSlider = true
                        dragPositionRatio = ratio
                    },
                    onValueChangeFinished = {
                        val targetMs = (dragPositionRatio * playerState.durationMs).toLong()
                        onSeekTo(targetMs)
                        isDraggingSlider = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playback_progress_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val displayedPosition = if (isDraggingSlider) {
                        val previewMs = (dragPositionRatio * playerState.durationMs).toLong()
                        val s = (previewMs / 1000) % 60
                        val m = (previewMs / 1000) / 60
                        String.format("%d:%02d", m, s)
                    } else {
                        playerState.formattedPosition
                    }

                    Text(
                        text = displayedPosition,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = playerState.formattedDuration,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Playback Controls Row:
            // [Shuffle] [-10s] [Prev] [Play/Pause] [Next] [+10s] [Repeat]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle Button with active pill state
                val isShuffleOn = playerState.isShuffleEnabled
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isShuffleOn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else Color.Transparent
                        )
                        .testTag("shuffle_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = if (isShuffleOn) "Shuffle Enabled" else "Shuffle Disabled",
                        tint = if (isShuffleOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // -10s Rewind Button
                IconButton(
                    onClick = { onSeekRelative(-10000L) },
                    modifier = Modifier.testTag("rewind_10_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Rewind 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Previous Track Button
                IconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier.testTag("skip_prev_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Main Play/Pause Button
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(68.dp)
                        .testTag("main_play_pause_btn"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Next Track Button
                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier.testTag("skip_next_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // +10s Forward Button
                IconButton(
                    onClick = { onSeekRelative(10000L) },
                    modifier = Modifier.testTag("forward_10_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Forward 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Repeat Button with active pill state
                val isRepeatOn = playerState.repeatMode != RepeatMode.OFF
                val repeatIcon = when (playerState.repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                }
                IconButton(
                    onClick = onCycleRepeatMode,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRepeatOn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else Color.Transparent
                        )
                        .testTag("repeat_btn")
                ) {
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Repeat: ${playerState.repeatMode}",
                        tint = if (isRepeatOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Queue Modal Bottom Sheet
        if (showQueueSheet) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Playback Queue (${playerState.queue.size})",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showQueueSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close queue")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (playerState.queue.isEmpty()) {
                        Text(
                            text = "Queue is empty",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp)
                        ) {
                            itemsIndexed(playerState.queue) { index, itemSong ->
                                val isCurrent = index == playerState.currentIndex
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onJumpToQueueItem(index)
                                            showQueueSheet = false
                                        },
                                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.width(28.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = itemSong.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = itemSong.artist,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = { onRemoveFromQueue(index) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Remove from queue",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
