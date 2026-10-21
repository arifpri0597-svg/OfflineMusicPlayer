package com.example.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
private val PillShape = RoundedCornerShape(50)

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
    onAddToPlaylist: (Song) -> Unit = {},
    onRemoveFromQueue: (Int) -> Unit,
    onJumpToQueueItem: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onCollapse()
    }

    var showQueueSheet by remember { mutableStateOf(false) }
    var showTrackDetailsDialog by remember { mutableStateOf(false) }
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
    val onShowTrackInfo = remember { { showTrackDetailsDialog = true } }
    val onRewind10s = remember(onSeekRelative) { { onSeekRelative(-10_000L) } }
    val onForward10s = remember(onSeekRelative) { { onSeekRelative(10_000L) } }
    val onAddCurrentToPlaylist = remember(song, onAddToPlaylist) { { onAddToPlaylist(song) } }

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
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Bar: Minimize button on left, "NOW PLAYING" + Song title in center, Queue button on right
            NowPlayingTopBar(
                songTitle = song.title,
                onCollapse = onCollapse,
                onOpenQueue = onOpenQueue
            )

            // 2. Large Centered Square Album Artwork (28-32dp rounded corners, subtle neon border & glow)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 284.dp)
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
                NowPlayingHeroArtwork(albumArtUri = song.albumArtUri)
            }

            // 3. Song Information (Centered title max 1-2 lines with ellipsis, Artist underneath)
            NowPlayingTrackMetadata(
                title = song.title,
                artist = song.artist
            )

            // 4. Essential Action Buttons (Compact Pill-Shaped: 1. Favorite, 2. Add to Playlist, 3. More)
            NowPlayingActionBar(
                isFavorite = isFavorite,
                queueSize = playerState.queue.size,
                onToggleFavorite = onToggleFavorite,
                onAddToPlaylist = onAddCurrentToPlaylist,
                onOpenQueue = onOpenQueue,
                onRewind10s = onRewind10s,
                onForward10s = onForward10s,
                onShowTrackInfo = onShowTrackInfo
            )

            // 5. Progress Bar (Pink/magenta indicator, current time left, total duration right)
            NowPlayingProgressSection(
                progress = playerState.progress,
                durationMs = playerState.durationMs,
                formattedPosition = playerState.formattedPosition,
                formattedDuration = playerState.formattedDuration,
                onSeekTo = onSeekTo
            )

            // 6. Playback Controls (Shuffle, Previous, Large Play/Pause, Next, Repeat)
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

            // 7. Small Subtle Audio Visualizer / Equalizer near the bottom (pauses when music is paused)
            NowPlayingWaveformVisualizer(
                isPlaying = playerState.isPlaying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .padding(horizontal = 20.dp)
                    .testTag("now_playing_waveform")
            )
        }

        // Track Details Dialog
        if (showTrackDetailsDialog) {
            AlertDialog(
                onDismissRequest = { showTrackDetailsDialog = false },
                title = {
                    Text(
                        text = "Track Information",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Title: ${song.title}", style = MaterialTheme.typography.bodyMedium)
                        Text("Artist: ${song.artist}", style = MaterialTheme.typography.bodyMedium)
                        Text("Album: ${song.album}", style = MaterialTheme.typography.bodyMedium)
                        Text("Duration: ${song.formattedDuration}", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTrackDetailsDialog = false }) {
                        Text("Close", color = NeonPink)
                    }
                }
            )
        }

        // Queue Modal Bottom Sheet
        if (showQueueSheet) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface
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
    }
}

@Composable
private fun NowPlayingTopBar(
    songTitle: String,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFF221238).copy(alpha = 0.75f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.3f)),
            modifier = Modifier.size(42.dp)
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

        Surface(
            shape = CircleShape,
            color = Color(0xFF221238).copy(alpha = 0.75f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.3f)),
            modifier = Modifier.size(42.dp)
        ) {
            IconButton(
                onClick = onOpenQueue,
                modifier = Modifier.testTag("now_playing_queue_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Open Queue",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun NowPlayingHeroArtwork(
    albumArtUri: android.net.Uri?
) {
    val context = LocalContext.current
    if (albumArtUri != null) {
        val heroImageRequest = remember(albumArtUri) {
            val key = "hero_$albumArtUri"
            ImageRequest.Builder(context)
                .data(albumArtUri)
                .memoryCacheKey(key)
                .diskCacheKey(key)
                .crossfade(false)
                .size(512, 512)
                .build()
        }
        AsyncImage(
            model = heroImageRequest,
            contentDescription = "Album Artwork",
            modifier = Modifier
                .fillMaxSize()
                .testTag("now_playing_artwork"),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("now_playing_artwork"),
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

@Composable
private fun NowPlayingActionBar(
    isFavorite: Boolean,
    queueSize: Int,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenQueue: () -> Unit,
    onRewind10s: () -> Unit,
    onForward10s: () -> Unit,
    onShowTrackInfo: () -> Unit
) {
    var moreMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Favorite Pill Button
        Surface(
            modifier = Modifier
                .clip(PillShape)
                .clickable(onClick = onToggleFavorite)
                .testTag("now_playing_favorite_btn"),
            shape = PillShape,
            color = if (isFavorite) {
                NeonPink.copy(alpha = 0.22f)
            } else {
                Color(0xFF221238).copy(alpha = 0.7f)
            },
            border = BorderStroke(
                1.dp,
                if (isFavorite) NeonPink else NeonPurple.copy(alpha = 0.35f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) NeonPink else Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Favorite",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isFavorite) NeonPinkLight else Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 2. Add to Playlist Pill Button
        Surface(
            modifier = Modifier
                .clip(PillShape)
                .clickable(onClick = onAddToPlaylist)
                .testTag("now_playing_add_playlist_btn"),
            shape = PillShape,
            color = Color(0xFF221238).copy(alpha = 0.7f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "Add to Playlist",
                    tint = NeonPinkLight,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Add to Playlist",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 3. More Pill Button with Dropdown
        Box {
            Surface(
                modifier = Modifier
                    .clip(PillShape)
                    .clickable { moreMenuExpanded = true }
                    .testTag("now_playing_more_btn"),
                shape = PillShape,
                color = Color(0xFF221238).copy(alpha = 0.7f),
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "More",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            DropdownMenu(
                expanded = moreMenuExpanded,
                onDismissRequest = { moreMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Playback Queue ($queueSize)") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = NeonPurple) },
                    onClick = {
                        moreMenuExpanded = false
                        onOpenQueue()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Rewind 10s") },
                    leadingIcon = { Icon(Icons.Default.Replay10, contentDescription = null) },
                    onClick = {
                        moreMenuExpanded = false
                        onRewind10s()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Fast-Forward 10s") },
                    leadingIcon = { Icon(Icons.Default.Forward10, contentDescription = null) },
                    onClick = {
                        moreMenuExpanded = false
                        onForward10s()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Track Info") },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = NeonPinkLight) },
                    onClick = {
                        moreMenuExpanded = false
                        onShowTrackInfo()
                    }
                )
            }
        }
    }
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = currentSliderValue,
            onValueChange = { newValue ->
                isDraggingSlider = true
                sliderDragPosition = newValue
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
                text = formattedPosition,
                style = MaterialTheme.typography.labelMedium,
                color = NeonPinkLight,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        // 1. Shuffle (Smaller icon, clean and minimal)
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier
                .size(44.dp)
                .testTag("now_playing_shuffle_btn")
        ) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (isShuffleEnabled) NeonPink else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        // 2. Previous (Medium circular button, dark transparent purple background, subtle border)
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

        // 3. Large Play/Pause (Main visual focus: Large circular button, neon pink/magenta gradient, soft glow, smooth press animation)
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

        // 4. Next (Medium circular button, dark transparent purple background, subtle border)
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

        // 5. Repeat (Smaller icon, clean and minimal)
        IconButton(
            onClick = onCycleRepeatMode,
            modifier = Modifier
                .size(44.dp)
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
 * Small subtle audio visualizer / equalizer near the bottom of Now Playing.
 * - Uses pink/magenta bars.
 * - Reacts smoothly to playback at a capped ~24fps refresh rate to save CPU & battery.
 * - Completely disables animation when music is paused (zero background ticks when paused).
 */
@Composable
private fun NowPlayingWaveformVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    var phase by remember { mutableFloatStateOf(0f) }

    // Only run the visualizer loop while music is actively playing
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                phase = (phase + 0.22f) % (2f * Math.PI.toFloat())
                delay(42L) // ~24fps capped refresh rate for low CPU/battery consumption
            }
        }
    }

    val amplitudeFactor by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.18f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "waveform_amplitude"
    )

    Canvas(
        modifier = modifier.graphicsLayer()
    ) {
        val barCount = 28
        val totalWidth = size.width
        val maxHeight = size.height
        val slotWidth = totalWidth / barCount
        val barWidth = (slotWidth * 0.48f).coerceAtLeast(2.5.dp.toPx())

        for (i in 0 until barCount) {
            val normalizedCenterDist = 1f - kotlin.math.abs((i - barCount / 2f) / (barCount / 2f))
            val wave1 = sin(phase + i * 0.48f) * 0.5f + 0.5f
            val wave2 = sin(phase * 1.4f - i * 0.32f) * 0.5f + 0.5f
            val combinedWave = (wave1 * 0.65f + wave2 * 0.35f) * normalizedCenterDist
            val fraction = (0.16f + combinedWave * 0.84f * amplitudeFactor).coerceIn(0.14f, 1f)

            val barHeight = maxHeight * fraction
            val x = i * slotWidth + (slotWidth - barWidth) / 2f
            val y = (maxHeight - barHeight) / 2f

            drawRoundRect(
                brush = VisualizerBarBrush,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}


