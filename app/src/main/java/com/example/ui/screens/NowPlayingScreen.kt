package com.example.ui.screens

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.RepeatMode
import com.example.data.model.Song
import com.example.player.PlayerUiState
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonPinkLight
import com.example.ui.theme.NeonPurple
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// Pre-allocated static brushes & shapes to avoid object allocations during recompositions
private val NowPlayingBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF210A3E),
        Color(0xFF100521),
        DarkBackground
    )
)

private val ArtworkBorderBrush = Brush.linearGradient(
    colors = listOf(NeonPink.copy(alpha = 0.65f), NeonPurple.copy(alpha = 0.45f))
)

private val ArtworkBackdropBrush = Brush.linearGradient(
    colors = listOf(Color(0xFF290F4B), Color(0xFF15072A))
)

private val PlayPauseGradientBrush = Brush.linearGradient(
    colors = listOf(NeonPink, Color(0xFFD91B82), NeonPurple)
)

private val VisualizerBarBrush = Brush.verticalGradient(
    colors = listOf(
        NeonPinkLight,
        NeonPink,
        NeonPurple.copy(alpha = 0.6f)
    )
)

private val ArtworkCornerShape = RoundedCornerShape(30.dp)
private val BottomSheetTopCornerShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val PillShape = RoundedCornerShape(50)

@Immutable
data class TrackMetadataRow(
    val label: String,
    val value: String
)

/**
 * Reusable menu item model for the top-right Now Playing menu so future features
 * can be added cleanly without redesigning the Now Playing screen.
 */
@Immutable
data class NowPlayingMenuAction(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val isHighlighted: Boolean = false,
    val testTag: String,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerState: PlayerUiState,
    progressFlow: kotlinx.coroutines.flow.StateFlow<PlayerUiState>? = null,
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
    onAddToPlaylist: (Song) -> Unit = {},
    onRemoveFromQueue: (Int) -> Unit,
    onJumpToQueueItem: (Int) -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit = {},
    onSetSleepTimer: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler {
        onCollapse()
    }

    var showQueueSheet by remember { mutableStateOf(false) }
    var showPlaybackSpeedSheet by remember { mutableStateOf(false) }
    var showTrackInfoSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    val song = playerState.currentSong ?: return

    val artworkScale by animateFloatAsState(
        targetValue = if (playerState.isPlaying) 1f else 0.96f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "artwork_scale"
    )

    val onOpenQueue = remember { { showQueueSheet = true } }
    val onOpenPlaybackSpeed = remember { { showPlaybackSpeedSheet = true } }
    val onShowTrackInfo = remember { { showTrackInfoSheet = true } }
    val onOpenSleepTimer = remember { { showSleepTimerSheet = true } }
    val onRewind10s = remember(onSeekRelative) { { onSeekRelative(-10_000L) } }
    val onForward10s = remember(onSeekRelative) { { onSeekRelative(10_000L) } }
    val onAddCurrentToPlaylist = remember(song, onAddToPlaylist) { { onAddToPlaylist(song) } }

    val sleepTimerBadge = remember(playerState.sleepTimerMinutes, playerState.sleepTimerEndTimeMs) {
        playerState.formatSleepTimerRemainingBadge()
    }
    val sleepTimerActiveDescription = remember(playerState.sleepTimerMinutes, playerState.sleepTimerEndTimeMs) {
        playerState.formatSleepTimerActiveDescription()
    }

    val menuActions = remember(
        playerState.sleepTimerMinutes,
        playerState.sleepTimerEndTimeMs,
        playerState.playbackSpeed,
        sleepTimerBadge,
        onShowTrackInfo,
        onOpenSleepTimer,
        onOpenPlaybackSpeed
    ) {
        val isCustomSpeed = abs(playerState.playbackSpeed - 1.0f) >= 0.01f
        listOf(
            NowPlayingMenuAction(
                id = "track_info",
                title = "Track Info",
                subtitle = null,
                icon = Icons.Outlined.Info,
                isHighlighted = false,
                testTag = "now_playing_menu_track_info",
                onClick = onShowTrackInfo
            ),
            NowPlayingMenuAction(
                id = "sleep_timer",
                title = "Sleep Timer",
                subtitle = sleepTimerBadge,
                icon = Icons.Default.Timer,
                isHighlighted = playerState.isSleepTimerActive,
                testTag = "now_playing_menu_sleep_timer",
                onClick = onOpenSleepTimer
            ),
            NowPlayingMenuAction(
                id = "playback_speed",
                title = "Playback Speed",
                subtitle = playerState.formattedPlaybackSpeed,
                icon = Icons.Default.Speed,
                isHighlighted = isCustomSpeed,
                testTag = "now_playing_menu_playback_speed",
                onClick = onOpenPlaybackSpeed
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NowPlayingBackgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("now_playing_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Bar: Back/Collapse button on left, "NOW PLAYING" in center, reusable 3-dot Menu button on right
            NowPlayingTopBar(
                songTitle = song.title,
                onCollapse = onCollapse,
                menuActions = menuActions
            )

            // 2. Large Centered Square Album Artwork (rounded corners, neon border & glow)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 268.dp)
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = artworkScale
                        scaleY = artworkScale
                    }
                    .shadow(
                        elevation = 18.dp,
                        shape = ArtworkCornerShape,
                        ambientColor = NeonPink.copy(alpha = 0.65f),
                        spotColor = NeonPurple.copy(alpha = 0.65f)
                    )
                    .clip(ArtworkCornerShape)
                    .border(
                        width = 1.5.dp,
                        brush = ArtworkBorderBrush,
                        shape = ArtworkCornerShape
                    )
                    .background(ArtworkBackdropBrush),
                contentAlignment = Alignment.Center
            ) {
                NowPlayingHeroArtwork(
                    albumArtUriString = song.albumArtUriString ?: playerState.mediaArtworkUriString,
                    fallbackUriString = song.contentUriString,
                    fallbackPath = song.path,
                    artworkBytes = playerState.artworkBytes
                )
            }

            // 3. Song Information (Centered title & Artist underneath)
            NowPlayingTrackMetadata(
                title = song.title,
                artist = song.artist
            )

            // 4. Compact Action Row: [ Sleep Timer Clock Sticker ]  [ ❤️ ]  [ Playlist + ]  [ Queue ]
            NowPlayingActionBar(
                isFavorite = isFavorite,
                isSleepTimerActive = playerState.isSleepTimerActive,
                sleepTimerBadge = sleepTimerBadge,
                onOpenSleepTimer = onOpenSleepTimer,
                onToggleFavorite = onToggleFavorite,
                onAddToPlaylist = onAddCurrentToPlaylist,
                onOpenQueue = onOpenQueue
            )

            // 5. Progress Bar (Seek bar, Current time left, Total duration right)
            NowPlayingLiveProgressSection(
                fallbackState = playerState,
                progressFlow = progressFlow,
                onSeekTo = onSeekTo
            )

            // 6. Main Playback Controls (Shuffle, Previous, Play/Pause, Next, Repeat)
            NowPlayingControlsRow(
                isPlaying = playerState.isPlaying,
                isShuffleEnabled = playerState.isShuffleEnabled,
                repeatMode = playerState.repeatMode,
                onToggleShuffle = onToggleShuffle,
                onSkipPrevious = onSkipPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onCycleRepeatMode = onCycleRepeatMode
            )

            // 7. Secondary Controls (10-second rewind, Playback Speed [ 1.0X ], 10-second forward)
            NowPlayingSecondaryControlsRow(
                playbackSpeed = playerState.playbackSpeed,
                formattedSpeedButton = playerState.formattedPlaybackSpeedButton,
                onRewind10s = onRewind10s,
                onOpenPlaybackSpeed = onOpenPlaybackSpeed,
                onForward10s = onForward10s
            )

            // 8. Subtle Audio Visualizer near the bottom (pauses when music is paused)
            NowPlayingWaveformVisualizer(
                isPlaying = playerState.isPlaying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .padding(horizontal = 20.dp)
                    .testTag("now_playing_waveform")
            )
        }

        // Track Info Modal Bottom Sheet
        if (showTrackInfoSheet) {
            val trackInfoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showTrackInfoSheet = false },
                sheetState = trackInfoSheetState,
                shape = BottomSheetTopCornerShape,
                containerColor = Color(0xFF140826),
                modifier = Modifier.testTag("track_info_bottom_sheet")
            ) {
                TrackInfoBottomSheetContent(
                    song = song,
                    onDismiss = { showTrackInfoSheet = false }
                )
            }
        }

        // Queue Modal Bottom Sheet
        if (showQueueSheet) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                sheetState = sheetState,
                shape = BottomSheetTopCornerShape,
                containerColor = Color(0xFF140826)
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
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(onClick = { showQueueSheet = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close queue",
                                tint = Color.White
                            )
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
                            itemsIndexed(
                                items = playerState.queue,
                                key = { index, itemSong -> "${itemSong.id}_$index" },
                                contentType = { _, _ -> "queue_item" }
                            ) { index, itemSong ->
                                val isCurrent = index == playerState.currentIndex
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onJumpToQueueItem(index)
                                            showQueueSheet = false
                                        },
                                    color = if (isCurrent) NeonPink.copy(alpha = 0.18f) else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isCurrent) NeonPink else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.width(28.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = itemSong.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) NeonPink else MaterialTheme.colorScheme.onSurface,
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

        // Premium Playback Speed Modal Bottom Sheet
        if (showPlaybackSpeedSheet) {
            val speedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showPlaybackSpeedSheet = false },
                sheetState = speedSheetState,
                shape = BottomSheetTopCornerShape,
                containerColor = Color(0xFF140826),
                modifier = Modifier.testTag("playback_speed_bottom_sheet")
            ) {
                PlaybackSpeedBottomSheetContent(
                    currentSpeed = playerState.playbackSpeed,
                    onSelectSpeed = { selectedSpeed ->
                        onPlaybackSpeedChange(selectedSpeed)
                    },
                    onDismiss = { showPlaybackSpeedSheet = false }
                )
            }
        }

        // Sleep Timer Modal Bottom Sheet
        if (showSleepTimerSheet) {
            val sleepSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showSleepTimerSheet = false },
                sheetState = sleepSheetState,
                shape = BottomSheetTopCornerShape,
                containerColor = Color(0xFF140826),
                modifier = Modifier.testTag("sleep_timer_bottom_sheet")
            ) {
                SleepTimerBottomSheetContent(
                    currentMinutes = playerState.sleepTimerMinutes,
                    activeStatusText = sleepTimerActiveDescription,
                    onSelectSleepTimer = { selectedMinutes ->
                        onSetSleepTimer(selectedMinutes)
                    },
                    onDismiss = { showSleepTimerSheet = false }
                )
            }
        }
    }
}

private data class SleepTimerPresetItem(
    val minutes: Int,
    val label: String,
    val subtitle: String,
    val testTag: String
)

private val SLEEP_TIMER_PRESETS = listOf(
    SleepTimerPresetItem(
        minutes = PlayerUiState.SLEEP_TIMER_OFF,
        label = "Off",
        subtitle = "Disable sleep timer",
        testTag = "sleep_timer_option_off"
    ),
    SleepTimerPresetItem(
        minutes = 5,
        label = "5 minutes",
        subtitle = "Stop playback after 5 min",
        testTag = "sleep_timer_option_5"
    ),
    SleepTimerPresetItem(
        minutes = 10,
        label = "10 minutes",
        subtitle = "Stop playback after 10 min",
        testTag = "sleep_timer_option_10"
    ),
    SleepTimerPresetItem(
        minutes = 15,
        label = "15 minutes",
        subtitle = "Stop playback after 15 min",
        testTag = "sleep_timer_option_15"
    ),
    SleepTimerPresetItem(
        minutes = 30,
        label = "30 minutes",
        subtitle = "Stop playback after 30 min",
        testTag = "sleep_timer_option_30"
    ),
    SleepTimerPresetItem(
        minutes = 45,
        label = "45 minutes",
        subtitle = "Stop playback after 45 min",
        testTag = "sleep_timer_option_45"
    ),
    SleepTimerPresetItem(
        minutes = 60,
        label = "60 minutes",
        subtitle = "Stop playback after 60 min",
        testTag = "sleep_timer_option_60"
    ),
    SleepTimerPresetItem(
        minutes = PlayerUiState.SLEEP_TIMER_END_OF_TRACK,
        label = "End of current track",
        subtitle = "Pause playback when current song finishes",
        testTag = "sleep_timer_option_end_of_track"
    )
)

@Composable
private fun SleepTimerBottomSheetContent(
    currentMinutes: Int,
    activeStatusText: String,
    onSelectSleepTimer: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val isPresetMatch = SLEEP_TIMER_PRESETS.any { it.minutes == currentMinutes }
    var isCustomExpanded by remember { mutableStateOf(currentMinutes > 0 && !isPresetMatch) }
    var customMinutesText by remember {
        mutableStateOf(if (currentMinutes > 0 && !isPresetMatch) currentMinutes.toString() else "20")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = NeonPink.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.45f)),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = NeonPinkLight,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "Sleep Timer",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = activeStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonPinkLight,
                        modifier = Modifier.testTag("sleep_timer_active_status")
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("sleep_timer_close_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close sleep timer",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (preset in SLEEP_TIMER_PRESETS) {
                val isSelected = !isCustomExpanded && currentMinutes == preset.minutes
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            isCustomExpanded = false
                            onSelectSleepTimer(preset.minutes)
                            onDismiss()
                        }
                        .testTag(preset.testTag),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) {
                        NeonPink.copy(alpha = 0.20f)
                    } else {
                        Color(0xFF211038).copy(alpha = 0.72f)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) NeonPink else NeonPurple.copy(alpha = 0.28f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) NeonPinkLight else Color.White
                            )
                            Text(
                                text = preset.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isSelected) {
                            Surface(
                                shape = CircleShape,
                                color = NeonPink,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected sleep timer",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Custom Option
            val isCustomSelected = isCustomExpanded || (currentMinutes > 0 && !isPresetMatch)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        isCustomExpanded = true
                    }
                    .testTag("sleep_timer_option_custom"),
                shape = RoundedCornerShape(16.dp),
                color = if (isCustomSelected) {
                    NeonPink.copy(alpha = 0.20f)
                } else {
                    Color(0xFF211038).copy(alpha = 0.72f)
                },
                border = BorderStroke(
                    width = if (isCustomSelected) 1.5.dp else 1.dp,
                    color = if (isCustomSelected) NeonPink else NeonPurple.copy(alpha = 0.28f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Custom",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isCustomSelected) NeonPinkLight else Color.White
                            )
                            Text(
                                text = if (currentMinutes > 0 && !isPresetMatch) {
                                    "Custom timer: $currentMinutes minutes"
                                } else {
                                    "Set custom duration in minutes"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCustomSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (currentMinutes > 0 && !isPresetMatch) {
                            Surface(
                                shape = CircleShape,
                                color = NeonPink,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected custom sleep timer",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (isCustomExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = customMinutesText,
                                onValueChange = { input ->
                                    val filtered = input.filter { it.isDigit() }.take(3)
                                    customMinutesText = filtered
                                },
                                label = { Text("Minutes (1–720)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonPink,
                                    unfocusedBorderColor = NeonPurple.copy(alpha = 0.5f),
                                    focusedLabelColor = NeonPinkLight,
                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sleep_timer_custom_input")
                            )

                            Surface(
                                modifier = Modifier
                                    .clip(PillShape)
                                    .clickable {
                                        val parsed = customMinutesText.toIntOrNull()?.coerceIn(1, 720) ?: 0
                                        if (parsed > 0) {
                                            onSelectSleepTimer(parsed)
                                            onDismiss()
                                        }
                                    }
                                    .testTag("sleep_timer_custom_confirm_btn"),
                                shape = PillShape,
                                color = NeonPink
                            ) {
                                Text(
                                    text = "Start",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private data class SpeedPresetItem(
    val speed: Float,
    val label: String,
    val subtitle: String,
    val testTag: String
)

private val PLAYBACK_SPEED_PRESETS = listOf(
    SpeedPresetItem(0.5f, "0.5×", "Slowest", "speed_option_0_5x"),
    SpeedPresetItem(0.75f, "0.75×", "Slower", "speed_option_0_75x"),
    SpeedPresetItem(1.0f, "1×", "Normal", "speed_option_1x"),
    SpeedPresetItem(1.25f, "1.25×", "Slightly Faster", "speed_option_1_25x"),
    SpeedPresetItem(1.5f, "1.5×", "Fast", "speed_option_1_5x"),
    SpeedPresetItem(2.0f, "2×", "Double Speed", "speed_option_2x")
)

@Composable
private fun PlaybackSpeedBottomSheetContent(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = NeonPink.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.45f)),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = NeonPinkLight,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "Playback Speed",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Current: ${PlayerUiState.formatSpeed(currentSpeed)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonPinkLight
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (abs(currentSpeed - 1.0f) >= 0.01f) {
                    Surface(
                        modifier = Modifier
                            .clip(PillShape)
                            .clickable { onSelectSpeed(1.0f) }
                            .testTag("speed_reset_btn"),
                        shape = PillShape,
                        color = Color(0xFF24113B),
                        border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonPinkLight,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("speed_sheet_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close playback speed",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (preset in PLAYBACK_SPEED_PRESETS) {
                val isSelected = abs(currentSpeed - preset.speed) < 0.01f
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            onSelectSpeed(preset.speed)
                            onDismiss()
                        }
                        .testTag(preset.testTag),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) {
                        NeonPink.copy(alpha = 0.20f)
                    } else {
                        Color(0xFF211038).copy(alpha = 0.72f)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) NeonPink else NeonPurple.copy(alpha = 0.28f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) NeonPinkLight else Color.White,
                                modifier = Modifier.width(56.dp)
                            )
                            Text(
                                text = preset.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isSelected) {
                            Surface(
                                shape = CircleShape,
                                color = NeonPink,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected speed",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun TrackInfoBottomSheetContent(
    song: Song,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var metadataRows by remember(song.id, song.contentUri, song.path, song.sizeBytes, song.durationMs) {
        mutableStateOf(buildImmediateTrackMetadataRows(song))
    }

    LaunchedEffect(song.id, song.contentUri, song.path, song.sizeBytes, song.durationMs) {
        val enriched = withContext(Dispatchers.IO) {
            resolveAvailableTrackMetadata(context, song)
        }
        if (enriched.isNotEmpty()) {
            metadataRows = enriched
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = NeonPink.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.45f)),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = NeonPinkLight,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "Track Info",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Audio file details",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonPinkLight
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("track_info_close_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close track info",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF211038).copy(alpha = 0.76f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.35f))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                itemsIndexed(
                    items = metadataRows,
                    key = { _, item -> item.label }
                ) { index, row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonPinkLight,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = row.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (index < metadataRows.lastIndex) {
                        HorizontalDivider(
                            color = NeonPurple.copy(alpha = 0.2f),
                            thickness = 0.75.dp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Builds the immediate, zero-IO metadata rows from the in-memory [Song] object.
 * Only fields that are genuinely available and non-empty are returned.
 */
internal fun buildImmediateTrackMetadataRows(song: Song): List<TrackMetadataRow> {
    val rows = mutableListOf<TrackMetadataRow>()

    val cleanTitle = song.title.trim()
    if (cleanTitle.isNotEmpty() && !cleanTitle.equals("<unknown>", ignoreCase = true)) {
        rows.add(TrackMetadataRow("Title", cleanTitle))
    }

    val cleanArtist = song.artist.trim()
    if (cleanArtist.isNotEmpty() &&
        !cleanArtist.equals("<unknown>", ignoreCase = true) &&
        !cleanArtist.equals("Unknown Artist", ignoreCase = true)
    ) {
        rows.add(TrackMetadataRow("Artist", cleanArtist))
    }

    val cleanAlbum = song.album.trim()
    if (cleanAlbum.isNotEmpty() &&
        !cleanAlbum.equals("<unknown>", ignoreCase = true) &&
        !cleanAlbum.equals("Unknown Album", ignoreCase = true)
    ) {
        rows.add(TrackMetadataRow("Album", cleanAlbum))
    }

    if (song.durationMs > 0L) {
        rows.add(TrackMetadataRow("Duration", Song.formatDurationMs(song.durationMs)))
    }

    val cleanPath = song.path.trim()
    val fileFromPath = if (cleanPath.isNotEmpty()) File(cleanPath) else null
    val fileName = fileFromPath?.name?.takeIf { it.isNotBlank() }
    if (!fileName.isNullOrBlank()) {
        rows.add(TrackMetadataRow("File Name", fileName))
    }

    if (cleanPath.isNotEmpty()) {
        rows.add(TrackMetadataRow("File Location", cleanPath))
    }

    if (song.sizeBytes > 0L) {
        rows.add(TrackMetadataRow("File Size", formatFileSizeBytes(song.sizeBytes)))
    }

    val ext = fileName?.substringAfterLast('.', "")?.trim()?.uppercase(Locale.US)
    if (!ext.isNullOrEmpty() && ext.length in 2..5) {
        rows.add(TrackMetadataRow("Format", ext))
    }

    return rows
}

/**
 * Inspects the song and underlying media stream on a background thread to extract
 * real available metadata without any fake or hardcoded values.
 */
internal fun resolveAvailableTrackMetadata(context: Context, song: Song): List<TrackMetadataRow> {
    val rows = mutableListOf<TrackMetadataRow>()

    val cleanTitle = song.title.trim()
    if (cleanTitle.isNotEmpty() && !cleanTitle.equals("<unknown>", ignoreCase = true)) {
        rows.add(TrackMetadataRow("Title", cleanTitle))
    }

    val cleanArtist = song.artist.trim()
    if (cleanArtist.isNotEmpty() &&
        !cleanArtist.equals("<unknown>", ignoreCase = true) &&
        !cleanArtist.equals("Unknown Artist", ignoreCase = true)
    ) {
        rows.add(TrackMetadataRow("Artist", cleanArtist))
    }

    val cleanAlbum = song.album.trim()
    if (cleanAlbum.isNotEmpty() &&
        !cleanAlbum.equals("<unknown>", ignoreCase = true) &&
        !cleanAlbum.equals("Unknown Album", ignoreCase = true)
    ) {
        rows.add(TrackMetadataRow("Album", cleanAlbum))
    }

    var resolvedDurationMs = song.durationMs
    var resolvedFileName: String? = null
    var resolvedLocation: String? = song.path.trim().takeIf { it.isNotEmpty() }
    var resolvedSizeBytes = song.sizeBytes
    var resolvedMimeType: String? = null
    var resolvedBitrateBps: Int = 0
    var resolvedSampleRateHz: Int = 0

    if (!resolvedLocation.isNullOrBlank()) {
        val f = File(resolvedLocation)
        resolvedFileName = f.name.takeIf { it.isNotBlank() }
        if (resolvedSizeBytes <= 0L && f.exists()) {
            resolvedSizeBytes = f.length()
        }
    }

    // Query ContentResolver for display name, size, and MIME type if content:// URI
    if (song.contentUri.scheme.equals("content", ignoreCase = true)) {
        try {
            resolvedMimeType = context.contentResolver.getType(song.contentUri)
        } catch (_: Exception) {
        }
        if (resolvedFileName.isNullOrBlank() || resolvedSizeBytes <= 0L) {
            try {
                context.contentResolver.query(
                    song.contentUri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        if (resolvedFileName.isNullOrBlank()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                                resolvedFileName = cursor.getString(nameIdx)?.trim()?.takeIf { it.isNotEmpty() }
                            }
                        }
                        if (resolvedSizeBytes <= 0L) {
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                                resolvedSizeBytes = cursor.getLong(sizeIdx)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    } else if (song.contentUri.scheme.equals("file", ignoreCase = true)) {
        val uriPath = song.contentUri.path
        if (!uriPath.isNullOrBlank()) {
            val f = File(uriPath)
            if (resolvedLocation.isNullOrBlank()) {
                resolvedLocation = f.absolutePath
            }
            if (resolvedFileName.isNullOrBlank()) {
                resolvedFileName = f.name.takeIf { it.isNotBlank() }
            }
            if (resolvedSizeBytes <= 0L && f.exists()) {
                resolvedSizeBytes = f.length()
            }
        }
    }

    // Probe real audio header with MediaMetadataRetriever for Bitrate / Duration / MimeType
    val retriever = MediaMetadataRetriever()
    try {
        var dataSourceSet = false
        if (!resolvedLocation.isNullOrBlank() && File(resolvedLocation).exists()) {
            retriever.setDataSource(resolvedLocation)
            dataSourceSet = true
        } else {
            retriever.setDataSource(context, song.contentUri)
            dataSourceSet = true
        }
        if (dataSourceSet) {
            if (resolvedDurationMs <= 0L) {
                resolvedDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
            if (resolvedMimeType.isNullOrBlank()) {
                resolvedMimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.trim()?.takeIf { it.isNotEmpty() }
            }
            resolvedBitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                resolvedSampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    ?.toIntOrNull() ?: 0
            }
        }
    } catch (_: Exception) {
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }

    // Probe MediaExtractor if Sample Rate, Bitrate, or MIME is still needed
    if (resolvedSampleRateHz <= 0 || resolvedBitrateBps <= 0 || resolvedMimeType.isNullOrBlank()) {
        val extractor = MediaExtractor()
        try {
            if (!resolvedLocation.isNullOrBlank() && File(resolvedLocation).exists()) {
                extractor.setDataSource(resolvedLocation)
            } else {
                extractor.setDataSource(context, song.contentUri, null)
            }
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = if (format.containsKey(MediaFormat.KEY_MIME)) {
                    format.getString(MediaFormat.KEY_MIME)
                } else {
                    null
                }
                if (mime != null && mime.startsWith("audio/")) {
                    if (resolvedMimeType.isNullOrBlank()) {
                        resolvedMimeType = mime
                    }
                    if (resolvedSampleRateHz <= 0 && format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        resolvedSampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (resolvedBitrateBps <= 0 && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        resolvedBitrateBps = format.getInteger(MediaFormat.KEY_BIT_RATE)
                    }
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    if (resolvedDurationMs > 0L) {
        rows.add(TrackMetadataRow("Duration", Song.formatDurationMs(resolvedDurationMs)))
    }

    if (!resolvedFileName.isNullOrBlank()) {
        rows.add(TrackMetadataRow("File Name", resolvedFileName!!))
    }

    if (!resolvedLocation.isNullOrBlank()) {
        rows.add(TrackMetadataRow("File Location", resolvedLocation!!))
    }

    if (resolvedSizeBytes > 0L) {
        rows.add(TrackMetadataRow("File Size", formatFileSizeBytes(resolvedSizeBytes)))
    }

    val formatDisplay = formatAudioFormatLabel(resolvedFileName, resolvedMimeType)
    if (!formatDisplay.isNullOrBlank()) {
        rows.add(TrackMetadataRow("Format", formatDisplay))
    }

    if (resolvedBitrateBps > 0) {
        val kbps = (resolvedBitrateBps / 1000).coerceAtLeast(1)
        rows.add(TrackMetadataRow("Bitrate", "$kbps kbps"))
    }

    if (resolvedSampleRateHz > 0) {
        val sampleRateLabel = if (resolvedSampleRateHz % 1000 == 0) {
            "${resolvedSampleRateHz / 1000} kHz"
        } else {
            String.format(Locale.US, "%.1f kHz", resolvedSampleRateHz / 1000.0)
        }
        rows.add(TrackMetadataRow("Sample Rate", sampleRateLabel))
    }

    return rows
}

internal fun formatFileSizeBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

private fun formatAudioFormatLabel(fileName: String?, mimeType: String?): String? {
    val ext = fileName
        ?.substringAfterLast('.', "")
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { it.length in 2..5 }

    val cleanMime = mimeType?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
    val mimeLabel = when (cleanMime) {
        "audio/mpeg", "audio/mp3" -> "MP3"
        "audio/flac", "audio/x-flac", "application/flac" -> "FLAC"
        "audio/wav", "audio/x-wav", "audio/wave" -> "WAV"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "M4A"
        "audio/aac", "audio/aacp" -> "AAC"
        "audio/ogg", "application/ogg" -> "OGG"
        "audio/opus" -> "OPUS"
        "audio/webm" -> "WEBM"
        else -> null
    }

    return when {
        ext != null && cleanMime != null -> "$ext ($cleanMime)"
        ext != null -> ext
        mimeLabel != null && cleanMime != null -> "$mimeLabel ($cleanMime)"
        cleanMime != null -> cleanMime
        else -> null
    }
}

@Composable
private fun NowPlayingTopBar(
    songTitle: String,
    onCollapse: () -> Unit,
    menuActions: List<NowPlayingMenuAction>
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFF221238).copy(alpha = 0.75f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.3f)),
            modifier = Modifier.size(44.dp)
        ) {
            IconButton(
                onClick = onCollapse,
                modifier = Modifier.testTag("now_playing_collapse_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Minimize Now Playing",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = "NOW PLAYING",
                style = MaterialTheme.typography.labelSmall,
                color = NeonPinkLight,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = songTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            Surface(
                shape = CircleShape,
                color = Color(0xFF221238).copy(alpha = 0.75f),
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.3f)),
                modifier = Modifier.size(44.dp)
            ) {
                IconButton(
                    onClick = { isMenuExpanded = true },
                    modifier = Modifier.testTag("now_playing_menu_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = { isMenuExpanded = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = Color(0xFF1A0B30),
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.42f)),
                modifier = Modifier.testTag("now_playing_top_menu")
            ) {
                for (action in menuActions) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = action.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (action.isHighlighted) NeonPinkLight else Color.White
                                )
                                if (!action.subtitle.isNullOrBlank()) {
                                    Text(
                                        text = action.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonPinkLight
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = if (action.isHighlighted) NeonPink else NeonPinkLight,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            action.onClick()
                        },
                        modifier = Modifier.testTag(action.testTag)
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingHeroArtwork(
    albumArtUriString: String?,
    fallbackUriString: String? = null,
    fallbackPath: String? = null,
    artworkBytes: ByteArray? = null
) {
    com.example.ui.components.FastArtworkImage(
        artworkUri = albumArtUriString ?: fallbackUriString,
        cacheKeyPrefix = "hero",
        sizePx = 512,
        contentDescription = "Album Artwork",
        fallbackUri = fallbackUriString,
        fallbackPath = fallbackPath,
        artworkBytes = artworkBytes,
        modifier = Modifier
            .fillMaxSize()
            .testTag("now_playing_artwork")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxR = size.minDimension * 0.42f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NeonPink.copy(alpha = 0.32f),
                            NeonPurple.copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxR * 1.2f
                    ),
                    center = center,
                    radius = maxR * 1.2f
                )
                drawCircle(
                    color = NeonPurple.copy(alpha = 0.28f),
                    radius = maxR,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = NeonPink.copy(alpha = 0.38f),
                    radius = maxR * 0.68f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "Default Artwork",
                tint = Color.White,
                modifier = Modifier.size(84.dp)
            )
        }
    }
}

@Composable
private fun NowPlayingTrackMetadata(
    title: String,
    artist: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("now_playing_title")
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.titleMedium,
            color = NeonPinkLight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("now_playing_artist")
        )
    }
}

/**
 * Compact horizontal action row below Artist name matching the reference image:
 * - Left: Small compact Sleep Timer clock icon button/sticker (indicates remaining timer when active)
 * - Icon-only compact buttons (no text labels):
 *   [ 🕒 Sleep Timer ]   [ ❤️ Favorite ]   [ Playlist + ]   [ Playback Queue ]
 */
@Composable
private fun NowPlayingActionBar(
    isFavorite: Boolean,
    isSleepTimerActive: Boolean,
    sleepTimerBadge: String?,
    onOpenSleepTimer: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenQueue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Compact Sleep Timer Clock Icon Button / Sticker on the left side of the action row
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Surface(
                modifier = Modifier
                    .height(38.dp)
                    .clip(PillShape)
                    .clickable(onClick = onOpenSleepTimer)
                    .testTag("now_playing_sleep_timer_btn"),
                shape = PillShape,
                color = if (isSleepTimerActive) {
                    NeonPink.copy(alpha = 0.22f)
                } else {
                    Color(0xFF211037).copy(alpha = 0.72f)
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSleepTimerActive) NeonPink else NeonPurple.copy(alpha = 0.42f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = if (isSleepTimerActive && !sleepTimerBadge.isNullOrBlank()) 11.dp else 10.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = if (isSleepTimerActive && !sleepTimerBadge.isNullOrBlank()) {
                            "Sleep Timer ($sleepTimerBadge)"
                        } else {
                            "Sleep Timer"
                        },
                        tint = if (isSleepTimerActive) NeonPink else NeonPinkLight,
                        modifier = Modifier.size(18.dp)
                    )
                    if (isSleepTimerActive && !sleepTimerBadge.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = sleepTimerBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonPinkLight,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("now_playing_sleep_timer_badge")
                        )
                    }
                }
            }
        }

        // 2. Favorite Icon-Only Compact Button (❤️)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Surface(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleFavorite)
                    .testTag("now_playing_favorite_btn"),
                shape = CircleShape,
                color = if (isFavorite) {
                    NeonPink.copy(alpha = 0.22f)
                } else {
                    Color(0xFF211037).copy(alpha = 0.72f)
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isFavorite) NeonPink else NeonPurple.copy(alpha = 0.42f)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) NeonPink else NeonPinkLight,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 3. Add to Playlist Icon-Only Compact Button (Playlist +)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Surface(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAddToPlaylist)
                    .testTag("now_playing_add_playlist_btn"),
                shape = CircleShape,
                color = Color(0xFF211037).copy(alpha = 0.72f),
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.42f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "Add to Playlist",
                        tint = NeonPinkLight,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        // 4. Playback Queue Icon-Only Compact Button (Queue / music-list icon)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Surface(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenQueue)
                    .testTag("now_playing_queue_btn"),
                shape = CircleShape,
                color = Color(0xFF211037).copy(alpha = 0.72f),
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.42f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Playback Queue",
                        tint = NeonPinkLight,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingLiveProgressSection(
    fallbackState: PlayerUiState,
    progressFlow: kotlinx.coroutines.flow.StateFlow<PlayerUiState>?,
    onSeekTo: (Long) -> Unit
) {
    val activeState = if (progressFlow != null) {
        val live by progressFlow.collectAsState()
        live
    } else {
        fallbackState
    }
    NowPlayingProgressSection(
        progress = activeState.progress,
        durationMs = activeState.durationMs,
        formattedPosition = activeState.formattedPosition,
        formattedDuration = activeState.formattedDuration,
        onSeekTo = onSeekTo
    )
}

@Composable
private fun NowPlayingProgressSection(
    progress: Float,
    durationMs: Long,
    formattedPosition: String,
    formattedDuration: String,
    onSeekTo: (Long) -> Unit
) {
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderDragPosition by remember { mutableFloatStateOf(0f) }

    val currentSliderValue = if (isDraggingSlider) {
        sliderDragPosition
    } else {
        progress
    }

    val displayedPositionText = if (isDraggingSlider && durationMs > 0L) {
        Song.formatDurationMs((sliderDragPosition * durationMs).toLong())
    } else {
        formattedPosition
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = currentSliderValue,
            onValueChange = { newValue ->
                isDraggingSlider = true
                sliderDragPosition = newValue.coerceIn(0f, 1f)
            },
            onValueChangeFinished = {
                val targetPositionMs = (sliderDragPosition * durationMs).toLong()
                onSeekTo(targetPositionMs)
                isDraggingSlider = false
            },
            colors = SliderDefaults.colors(
                thumbColor = NeonPink,
                activeTrackColor = NeonPink,
                inactiveTrackColor = Color(0xFF2B1746)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("now_playing_slider")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayedPositionText,
                style = MaterialTheme.typography.labelMedium,
                color = NeonPinkLight,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("now_playing_position_text")
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("now_playing_duration_text")
            )
        }
    }
}

@Composable
private fun NowPlayingControlsRow(
    isPlaying: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onToggleShuffle: () -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onCycleRepeatMode: () -> Unit
) {
    val playPauseInteractionSource = remember { MutableInteractionSource() }
    val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()
    val playPauseScale by animateFloatAsState(
        targetValue = if (isPlayPausePressed) 0.91f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "play_pause_press_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Shuffle
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier
                .size(48.dp)
                .testTag("now_playing_shuffle_btn")
        ) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (isShuffleEnabled) NeonPink else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        // 2. Previous
        Surface(
            shape = CircleShape,
            color = Color(0xFF211037).copy(alpha = 0.78f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.35f)),
            modifier = Modifier.size(52.dp)
        ) {
            IconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.testTag("now_playing_prev_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // 3. Large Play/Pause (Central Visual Focus)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(74.dp)
                .graphicsLayer {
                    scaleX = playPauseScale
                    scaleY = playPauseScale
                }
                .shadow(
                    elevation = 18.dp,
                    shape = CircleShape,
                    ambientColor = NeonPink,
                    spotColor = NeonPink
                )
                .clip(CircleShape)
                .background(PlayPauseGradientBrush)
                .clickable(
                    interactionSource = playPauseInteractionSource,
                    indication = null,
                    onClick = onTogglePlayPause
                )
                .testTag("now_playing_play_pause_btn")
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        // 4. Next
        Surface(
            shape = CircleShape,
            color = Color(0xFF211037).copy(alpha = 0.78f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.35f)),
            modifier = Modifier.size(52.dp)
        ) {
            IconButton(
                onClick = onSkipNext,
                modifier = Modifier.testTag("now_playing_next_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // 5. Repeat
        IconButton(
            onClick = onCycleRepeatMode,
            modifier = Modifier
                .size(48.dp)
                .testTag("now_playing_repeat_btn")
        ) {
            val repeatIcon = when (repeatMode) {
                RepeatMode.ONE -> Icons.Default.RepeatOne
                else -> Icons.Default.Repeat
            }
            val isRepeatActive = repeatMode != RepeatMode.OFF
            Icon(
                imageVector = repeatIcon,
                contentDescription = "Repeat Mode",
                tint = if (isRepeatActive) NeonPink else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Secondary playback controls row in the lower playback-control area:
 * - 10-second rewind (circular-arrow icon with "10" inside)
 * - Playback Speed button ([ 1.0X ])
 * - 10-second forward (circular-arrow icon with "10" inside)
 */
@Composable
private fun NowPlayingSecondaryControlsRow(
    playbackSpeed: Float,
    formattedSpeedButton: String,
    onRewind10s: () -> Unit,
    onOpenPlaybackSpeed: () -> Unit,
    onForward10s: () -> Unit
) {
    val isCustomSpeed = abs(playbackSpeed - 1.0f) >= 0.01f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 10-second Rewind
        Surface(
            shape = CircleShape,
            color = Color(0xFF211037).copy(alpha = 0.68f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.32f)),
            modifier = Modifier.size(48.dp)
        ) {
            IconButton(
                onClick = onRewind10s,
                modifier = Modifier.testTag("now_playing_rewind_10s_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = "Rewind 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 2. Playback Speed Button (e.g. [ 1.0X ])
        Surface(
            modifier = Modifier
                .heightIn(min = 38.dp)
                .clip(PillShape)
                .clickable(onClick = onOpenPlaybackSpeed)
                .testTag("now_playing_speed_btn"),
            shape = PillShape,
            color = if (isCustomSpeed) {
                NeonPink.copy(alpha = 0.22f)
            } else {
                Color(0xFF211037).copy(alpha = 0.72f)
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (isCustomSpeed) NeonPink else NeonPurple.copy(alpha = 0.42f)
            )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp)
            ) {
                Text(
                    text = formattedSpeedButton,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    color = if (isCustomSpeed) NeonPinkLight else Color.White
                )
            }
        }

        // 3. 10-second Forward
        Surface(
            shape = CircleShape,
            color = Color(0xFF211037).copy(alpha = 0.68f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.32f)),
            modifier = Modifier.size(48.dp)
        ) {
            IconButton(
                onClick = onForward10s,
                modifier = Modifier.testTag("now_playing_forward_10s_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = "Forward 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Small subtle audio visualizer / equalizer near the bottom of Now Playing.
 * - Uses pink/magenta bars.
 * - Reads phaseState inside the Canvas draw lambda ONLY so 24fps updates invalidate
 *   only the draw layer with ZERO Compose recompositions.
 * - Completely disables animation when music is paused (zero background ticks when paused).
 */
@Composable
private fun NowPlayingWaveformVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val phaseState = remember { mutableFloatStateOf(0f) }

    // Only run the visualizer loop while music is actively playing
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                phaseState.floatValue = (phaseState.floatValue + 0.22f) % (2f * Math.PI.toFloat())
                delay(42L) // ~24fps capped refresh rate for low CPU/battery consumption
            }
        }
    }

    val amplitudeFactorState = animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.18f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "waveform_amplitude"
    )

    Canvas(
        modifier = modifier.graphicsLayer()
    ) {
        val currentPhase = phaseState.floatValue
        val amplitudeFactor = amplitudeFactorState.value
        val barCount = 28
        val totalWidth = size.width
        val maxHeight = size.height
        val slotWidth = totalWidth / barCount
        val barWidth = (slotWidth * 0.48f).coerceAtLeast(2.5.dp.toPx())
        val cornerRad = CornerRadius(barWidth / 2f, barWidth / 2f)

        for (i in 0 until barCount) {
            val normalizedCenterDist = 1f - abs((i - barCount / 2f) / (barCount / 2f))
            val wave1 = sin(currentPhase + i * 0.48f) * 0.5f + 0.5f
            val wave2 = sin(currentPhase * 1.4f - i * 0.32f) * 0.5f + 0.5f
            val combinedWave = (wave1 * 0.65f + wave2 * 0.35f) * normalizedCenterDist
            val fraction = (0.16f + combinedWave * 0.84f * amplitudeFactor).coerceIn(0.14f, 1f)

            val barHeight = maxHeight * fraction
            val x = i * slotWidth + (slotWidth - barWidth) / 2f
            val y = (maxHeight - barHeight) / 2f

            drawRoundRect(
                brush = VisualizerBarBrush,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRad
            )
        }
    }
}
