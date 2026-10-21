package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as ComposeRepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.ui.theme.DeepViolet
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonPinkLight
import com.example.ui.theme.NeonPurple
import kotlin.math.sin

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
        targetValue = if (playerState.isPlaying) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "artwork_scale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF240B42),
                        Color(0xFF120626),
                        DarkBackground
                    )
                )
            )
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
            // 1. Top Bar: Collapse, NOW PLAYING header, Queue
            NowPlayingTopBar(
                album = song.album,
                onCollapse = onCollapse,
                onOpenQueue = { showQueueSheet = true }
            )

            // 2. Large Glowing Rounded Album Artwork
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 330.dp)
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = artworkScale
                        scaleY = artworkScale
                    }
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(28.dp),
                        ambientColor = NeonPink,
                        spotColor = NeonPurple
                    )
                    .clip(RoundedCornerShape(28.dp))
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(NeonPink.copy(alpha = 0.7f), NeonPurple.copy(alpha = 0.5f))
                        ),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF2B104E),
                                Color(0xFF17082E)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                NowPlayingHeroArtwork(albumArtUri = song.albumArtUri)
            }

            // 3. Song Title & Artist Metadata
            NowPlayingTrackMetadata(
                title = song.title,
                artist = song.artist
            )

            // 4. Secondary Action Row: Favorite, Add to Playlist, and More Options
            NowPlayingActionBar(
                isFavorite = isFavorite,
                queueSize = playerState.queue.size,
                onToggleFavorite = onToggleFavorite,
                onAddToPlaylist = { onAddToPlaylist(song) },
                onOpenQueue = { showQueueSheet = true },
                onRewind10s = { onSeekRelative(-10_000L) },
                onForward10s = { onSeekRelative(10_000L) },
                onShowTrackInfo = { showTrackDetailsDialog = true }
            )

            // 5. Isolated Progress Bar & Timestamps (only section that recomposes on 200ms position ticks)
            NowPlayingProgressSection(
                progress = playerState.progress,
                durationMs = playerState.durationMs,
                formattedPosition = playerState.formattedPosition,
                formattedDuration = playerState.formattedDuration,
                onSeekTo = onSeekTo
            )

            // 6. Primary Playback Controls: Shuffle, Previous, Play/Pause, Next, Repeat
            NowPlayingControlsRow(
                isPlaying = playerState.isPlaying,
                isShuffleEnabled = playerState.isShuffleEnabled,
                repeatMode = playerState.repeatMode,
                onToggleShuffle = onToggleShuffle,
                onSeekRelative = onSeekRelative,
                onSkipPrevious = onSkipPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onCycleRepeatMode = onCycleRepeatMode
            )

            // 7. Subtle Animated Music Waveform / Equalizer at the bottom
            NowPlayingWaveformVisualizer(
                isPlaying = playerState.isPlaying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(horizontal = 8.dp)
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
    album: String,
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
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier.size(42.dp)
        ) {
            IconButton(
                onClick = onCollapse,
                modifier = Modifier.testTag("now_playing_collapse_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse Player",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
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
            Text(
                text = album,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
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
                    modifier = Modifier.size(22.dp)
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
                .size(600, 600)
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
                            NeonPink.copy(alpha = 0.35f),
                            NeonPurple.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxR * 1.2f
                    ),
                    center = center,
                    radius = maxR * 1.2f
                )
                drawCircle(
                    color = NeonPurple.copy(alpha = 0.3f),
                    radius = maxR,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = NeonPink.copy(alpha = 0.4f),
                    radius = maxR * 0.7f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "Default Artwork",
                tint = Color.White,
                modifier = Modifier.size(88.dp)
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
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("now_playing_title")
        )
        Spacer(modifier = Modifier.height(4.dp))
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
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onToggleFavorite)
                .testTag("now_playing_favorite_btn"),
            shape = RoundedCornerShape(50),
            color = if (isFavorite) {
                NeonPink.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            },
            border = BorderStroke(
                1.dp,
                if (isFavorite) NeonPink else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) NeonPink else Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isFavorite) "Favorited" else "Favorite",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isFavorite) NeonPinkLight else Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 2. Add to Playlist Pill Button
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onAddToPlaylist)
                .testTag("now_playing_add_playlist_btn"),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "Add to Playlist",
                    tint = NeonPurple,
                    modifier = Modifier.size(18.dp)
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

        // 3. More Options Pill Button with Dropdown
        Box {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { moreMenuExpanded = true }
                    .testTag("now_playing_more_btn"),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
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
                    text = { Text("Add to Playlist") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = NeonPink) },
                    onClick = {
                        moreMenuExpanded = false
                        onAddToPlaylist()
                    }
                )
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
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("now_playing_slider")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
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
    onSeekRelative: (Long) -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onCycleRepeatMode: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier.testTag("now_playing_shuffle_btn")
        ) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (isShuffleEnabled) NeonPink else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = { onSeekRelative(-10_000L) },
            modifier = Modifier
                .size(40.dp)
                .testTag("now_playing_rewind_10s_btn")
        ) {
            Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = "Rewind 10 seconds",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.35f)),
            modifier = Modifier.size(50.dp)
        ) {
            IconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.testTag("now_playing_prev_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Glowing Neon Pink-Purple Play/Pause Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = NeonPink,
                    spotColor = NeonPink
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(NeonPink, NeonPurple)
                    )
                )
                .clickable(onClick = onTogglePlayPause)
                .testTag("now_playing_play_pause_btn")
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.35f)),
            modifier = Modifier.size(50.dp)
        ) {
            IconButton(
                onClick = onSkipNext,
                modifier = Modifier.testTag("now_playing_next_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        IconButton(
            onClick = { onSeekRelative(10_000L) },
            modifier = Modifier
                .size(40.dp)
                .testTag("now_playing_forward_10s_btn")
        ) {
            Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = "Fast-forward 10 seconds",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        IconButton(
            onClick = onCycleRepeatMode,
            modifier = Modifier.testTag("now_playing_repeat_btn")
        ) {
            val repeatIcon = when (repeatMode) {
                RepeatMode.ONE -> Icons.Default.RepeatOne
                else -> Icons.Default.Repeat
            }
            val isRepeatActive = repeatMode != RepeatMode.OFF
            Icon(
                imageVector = repeatIcon,
                contentDescription = "Repeat Mode",
                tint = if (isRepeatActive) NeonPink else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Hardware-accelerated subtle animated music waveform / equalizer at the bottom
 * of the Now Playing screen. Executes purely in the Canvas draw phase for zero-recomposition
 * 60/90/120Hz smoothness.
 */
@Composable
private fun NowPlayingWaveformVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_transition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = ComposeRepeatMode.Restart
        ),
        label = "waveform_phase"
    )

    val amplitudeFactor by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.22f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "waveform_amplitude"
    )

    val barBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                NeonPinkLight,
                NeonPink,
                NeonPurple.copy(alpha = 0.65f)
            )
        )
    }

    Canvas(
        modifier = modifier.graphicsLayer()
    ) {
        val barCount = 32
        val totalWidth = size.width
        val maxHeight = size.height
        val spacing = totalWidth / (barCount * 2f)
        val barWidth = spacing.coerceAtLeast(3.dp.toPx())

        for (i in 0 until barCount) {
            val normalizedCenterDist = 1f - kotlin.math.abs((i - barCount / 2f) / (barCount / 2f))
            val wave1 = sin(phase + i * 0.45f) * 0.5f + 0.5f
            val wave2 = sin(phase * 1.5f - i * 0.3f) * 0.5f + 0.5f
            val combinedWave = (wave1 * 0.65f + wave2 * 0.35f) * normalizedCenterDist
            val fraction = (0.18f + combinedWave * 0.82f * amplitudeFactor).coerceIn(0.15f, 1f)

            val barHeight = maxHeight * fraction
            val x = i * (totalWidth / barCount) + (totalWidth / barCount - barWidth) / 2f
            val y = (maxHeight - barHeight) / 2f

            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

