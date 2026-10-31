package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.ui.components.EmptyStateView
import com.example.ui.components.FastArtworkImage
import com.example.ui.components.FastIconActionButton
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonPinkLight
import com.example.ui.theme.NeonPurple

private val TextSecondaryDark = Color(0xFFB3A4CC)

// Pre-allocated static shapes & brushes for 120Hz zero-allocation scrolling
private val PlaylistScreenBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF1D0936),
        Color(0xFF100520),
        DarkBackground
    )
)

private val PlaylistHeaderArtworkShape = RoundedCornerShape(22.dp)
private val PlaylistHeaderArtworkBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF3A1266),
        Color(0xFF240A45),
        Color(0xFF16062B)
    )
)
private val PlaylistHeaderArtworkBorderBrush = Brush.linearGradient(
    colors = listOf(
        NeonPink.copy(alpha = 0.75f),
        NeonPurple.copy(alpha = 0.55f)
    )
)

private val PlaylistActionButtonShape = RoundedCornerShape(16.dp)
private val PlaylistActionOutlineBrush = Brush.horizontalGradient(
    colors = listOf(
        NeonPink.copy(alpha = 0.72f),
        NeonPurple.copy(alpha = 0.72f)
    )
)
private val PlaylistActionContainerColor = Color(0xFF1B0B31).copy(alpha = 0.85f)

private val PlaylistSongCardShape = RoundedCornerShape(18.dp)
private val PlaylistSongThumbShape = RoundedCornerShape(12.dp)
private val PlaylistSongNormalBgColor = Color(0xFF160A28).copy(alpha = 0.82f)
private val PlaylistSongNormalBorderColor = NeonPurple.copy(alpha = 0.26f)

private val PlaylistSongPlayingBgBrush = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF2D0E4B).copy(alpha = 0.94f),
        Color(0xFF1F0A37).copy(alpha = 0.90f),
        Color(0xFF280C44).copy(alpha = 0.94f)
    )
)
private val PlaylistSongPlayingBorderBrush = Brush.horizontalGradient(
    colors = listOf(
        NeonPink,
        NeonPurple,
        NeonPinkLight.copy(alpha = 0.9f)
    )
)
private val PlaylistSongProgressFillBrush = Brush.horizontalGradient(
    colors = listOf(
        NeonPink,
        NeonPinkLight,
        NeonPurple
    )
)
private val PlaylistSongProgressTrackColor = Color(0xFF38195E).copy(alpha = 0.65f)
private val PlaylistThumbBackdropBrush = Brush.linearGradient(
    colors = listOf(
        ElectricViolet.copy(alpha = 0.45f),
        NeonPink.copy(alpha = 0.35f)
    )
)
private val PlaylistPlayingThumbOverlayColor = Color(0xFF0E041A).copy(alpha = 0.58f)
private val HeaderIconButtonBgColor = Color(0xFF1E0C36).copy(alpha = 0.85f)
private val HeaderIconButtonBorderColor = NeonPurple.copy(alpha = 0.32f)
private val PopupMenuContainerColor = Color(0xFF190A2E)

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    selectedPlaylist: Playlist?,
    playlistSongs: List<Song>,
    allSongs: List<Song> = emptyList(),
    favoriteIds: Set<Long>,
    currentSongId: Long?,
    isPlaying: Boolean,
    onPlaylistSelect: (Playlist?) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onAddSongsToPlaylist: (Long, List<Long>) -> Unit = { _, _ -> },
    onRemoveSongFromPlaylist: (Long, Long) -> Unit,
    onReorderPlaylistSongs: (Long, Int, Int) -> Unit = { _, _, _ -> },
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShuffleAll: (List<Song>) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var showAddTracksDialog by remember { mutableStateOf(false) }

    // Playlist Details Screen
    if (selectedPlaylist != null) {
        BackHandler {
            onPlaylistSelect(null)
        }
        val onPlaylistSongClick = remember(onSongClick, playlistSongs) {
            { song: Song -> onSongClick(song, playlistSongs) }
        }
        val leadSong = playlistSongs.firstOrNull()

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(PlaylistScreenBackgroundBrush)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("playlist_detail_screen"),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                item(key = "playlist_detail_header", contentType = "header") {
                    PlaylistDetailHeader(
                        playlist = selectedPlaylist,
                        trackCount = playlistSongs.size,
                        leadSong = leadSong,
                        onBack = { onPlaylistSelect(null) },
                        onOpenAddTracks = { showAddTracksDialog = true },
                        onDeletePlaylist = { playlistToDelete = selectedPlaylist },
                        onShuffle = {
                            if (playlistSongs.isNotEmpty()) {
                                onShuffleAll(playlistSongs)
                            }
                        },
                        onPlayAll = {
                            if (playlistSongs.isNotEmpty()) {
                                onPlayAll(playlistSongs)
                            }
                        }
                    )
                }

                if (playlistSongs.isEmpty()) {
                    item(key = "playlist_empty_state", contentType = "empty") {
                        EmptyStateView(
                            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                            title = "Playlist is Empty",
                            description = "Add songs to \"${selectedPlaylist.name}\" from your library.",
                            actionButtonText = "Add Tracks Now",
                            onActionClick = { showAddTracksDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 36.dp)
                        )
                    }
                } else {
                    itemsIndexed(
                        items = playlistSongs,
                        key = { _, song -> song.id },
                        contentType = { _, _ -> "playlist_song" }
                    ) { index, song ->
                        val isCurrentTrack = currentSongId == song.id
                        val isTrackPlaying = isCurrentTrack && isPlaying
                        val isFavorite = favoriteIds.contains(song.id)

                        PlaylistSongCard(
                            song = song,
                            index = index,
                            totalCount = playlistSongs.size,
                            playlistId = selectedPlaylist.id,
                            isCurrentTrack = isCurrentTrack,
                            isPlaying = isTrackPlaying,
                            isFavorite = isFavorite,
                            onSongClick = onPlaylistSongClick,
                            onToggleFavorite = onToggleFavorite,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onAddToPlaylist = onAddToPlaylist,
                            onRemoveFromPlaylist = onRemoveSongFromPlaylist,
                            onReorderPlaylistSongs = onReorderPlaylistSongs
                        )
                    }
                }
            }
        }

        // Add tracks to current playlist dialog
        if (showAddTracksDialog) {
            val existingIds = remember(playlistSongs) { playlistSongs.map { it.id }.toSet() }
            val availableSongs = remember(allSongs, existingIds) {
                allSongs.filter { !existingIds.contains(it.id) }
            }

            AddTracksToPlaylistDialog(
                playlistName = selectedPlaylist.name,
                availableSongs = availableSongs,
                onDismiss = { showAddTracksDialog = false },
                onAddTracks = { selectedIds ->
                    onAddSongsToPlaylist(selectedPlaylist.id, selectedIds)
                    showAddTracksDialog = false
                }
            )
        }
    } else {
        // Main Playlists Overview Screen
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .testTag("playlists_screen"),
            containerColor = Color.Transparent,
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = NeonPink,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.testTag("create_playlist_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create New Playlist")
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PlaylistScreenBackgroundBrush)
                    .padding(paddingValues)
            ) {
                if (playlists.isEmpty()) {
                    EmptyStateView(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = "No Custom Playlists",
                        description = "Create your first custom playlist to group your favorite offline audio tracks.",
                        actionButtonText = "Create Playlist",
                        onActionClick = { showCreateDialog = true },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 88.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "playlists_overview_header", contentType = "overview_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Custom Playlists",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${playlists.size} playlists",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NeonPinkLight.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }

                        items(
                            items = playlists,
                            key = { it.id },
                            contentType = { "playlist_card" }
                        ) { playlist ->
                            PlaylistItemCard(
                                playlist = playlist,
                                onClick = { onPlaylistSelect(playlist) },
                                onDelete = { playlistToDelete = playlist }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Playlist Dialog
    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                showCreateDialog = false
            }
        )
    }

    // Delete Confirmation Dialog
    if (playlistToDelete != null) {
        val target = playlistToDelete!!
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = PopupMenuContainerColor,
            title = {
                Text(
                    text = "Delete Playlist?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${target.name}\"? The underlying audio files on your device will NOT be deleted.",
                    color = TextSecondaryDark
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist(target.id)
                        playlistToDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete_playlist_btn")
                ) {
                    Text("Delete", color = NeonPink, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel", color = TextSecondaryDark)
                }
            }
        )
    }
}

@Composable
private fun PlaylistDetailHeader(
    playlist: Playlist,
    trackCount: Int,
    leadSong: Song?,
    onBack: () -> Unit,
    onOpenAddTracks: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onShuffle: () -> Unit,
    onPlayAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 1. Top Navigation & Management Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(42.dp)
                    .background(HeaderIconButtonBgColor, CircleShape)
                    .border(1.dp, HeaderIconButtonBorderColor, CircleShape)
                    .testTag("playlist_back_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onOpenAddTracks,
                    modifier = Modifier
                        .size(42.dp)
                        .background(HeaderIconButtonBgColor, CircleShape)
                        .border(1.dp, HeaderIconButtonBorderColor, CircleShape)
                        .testTag("playlist_add_tracks_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "Add tracks",
                        tint = NeonPinkLight,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = onDeletePlaylist,
                    modifier = Modifier
                        .size(42.dp)
                        .background(HeaderIconButtonBgColor, CircleShape)
                        .border(1.dp, NeonPink.copy(alpha = 0.35f), CircleShape)
                        .testTag("playlist_delete_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Playlist",
                        tint = Color(0xFFFF6E9C),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Large Playlist Artwork Card + Playlist Name & Track Count
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .background(PlaylistHeaderArtworkBrush, PlaylistHeaderArtworkShape)
                    .border(
                        width = 1.5.dp,
                        brush = PlaylistHeaderArtworkBorderBrush,
                        shape = PlaylistHeaderArtworkShape
                    )
                    .clickable(enabled = trackCount > 0, onClick = onPlayAll)
                    .testTag("playlist_header_artwork"),
                contentAlignment = Alignment.Center
            ) {
                if (leadSong != null) {
                    FastArtworkImage(
                        artworkUri = leadSong.albumArtUriString ?: leadSong.contentUriString,
                        cacheKeyPrefix = "album",
                        sizePx = 256,
                        contentDescription = playlist.name,
                        fallbackUri = leadSong.contentUriString,
                        fallbackPath = leadSong.path,
                        precomputedIdentity = leadSong.artworkSourceIdentity,
                        imageClipShape = PlaylistHeaderArtworkShape,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = null,
                            tint = NeonPinkLight,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = NeonPinkLight,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(18.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 24.sp,
                        letterSpacing = (-0.3).sp
                    ),
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("playlist_header_title")
                )
                Text(
                    text = "$trackCount tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeonPink,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("playlist_header_track_count")
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 3. Two Clean Horizontal Action Buttons: [ Shuffle ] and [ + Add Tracks ]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Shuffle Button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .background(PlaylistActionContainerColor, PlaylistActionButtonShape)
                    .border(
                        width = 1.2.dp,
                        brush = PlaylistActionOutlineBrush,
                        shape = PlaylistActionButtonShape
                    )
                    .clip(PlaylistActionButtonShape)
                    .clickable(onClick = onShuffle)
                    .testTag("playlist_shuffle_btn"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = null,
                    tint = NeonPinkLight,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Shuffle",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1
                )
            }

            // 2. Add Tracks Button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .background(PlaylistActionContainerColor, PlaylistActionButtonShape)
                    .border(
                        width = 1.2.dp,
                        brush = PlaylistActionOutlineBrush,
                        shape = PlaylistActionButtonShape
                    )
                    .clip(PlaylistActionButtonShape)
                    .clickable(onClick = onOpenAddTracks)
                    .testTag("playlist_action_add_tracks_btn"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = NeonPinkLight,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add Tracks",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

/**
 * Clean, 120Hz-optimized horizontal card for a single song inside a Playlist.
 * Contains:
 * - Square album artwork on the left (with clean visual playing indicator when active)
 * - Song title + Artist/album info below (plus sleek pink playback/progress bar when currently playing)
 * - Favorite heart icon
 * - Three-dot More menu (Play Next, Add to Queue, Add to Playlist, Move Up/Down, Remove from Playlist)
 * - Drag/reorder handle on the far right
 */
@Composable
private fun PlaylistSongCard(
    song: Song,
    index: Int,
    totalCount: Int,
    playlistId: Long,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onSongClick: (Song) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onRemoveFromPlaylist: (Long, Long) -> Unit,
    onReorderPlaylistSongs: (Long, Int, Int) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragAccumulatedPx by remember { mutableFloatStateOf(0f) }

    val currentIndexState by rememberUpdatedState(index)
    val currentTotalCountState by rememberUpdatedState(totalCount)
    val density = LocalDensity.current
    val stepThresholdPx = remember(density) { with(density) { 62.dp.toPx() } }

    val handleRowClick = remember(song, onSongClick) { { onSongClick(song) } }
    val handleToggleFavorite = remember(song.id, onToggleFavorite) { { onToggleFavorite(song.id) } }
    val handleOpenMenu = remember { { menuExpanded = true } }

    val artistAlbumSubtitle = remember(song.artist, song.album, song.formattedDuration) {
        val cleanAlbum = song.album.trim()
        if (cleanAlbum.isNotEmpty() &&
            !cleanAlbum.equals("Unknown Album", ignoreCase = true) &&
            !cleanAlbum.equals("<unknown>", ignoreCase = true) &&
            !cleanAlbum.equals(song.artist, ignoreCase = true)
        ) {
            "${song.artist} • $cleanAlbum"
        } else {
            song.artistDurationSubtitle
        }
    }

    val cardModifier = if (isCurrentTrack) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .background(brush = PlaylistSongPlayingBgBrush, shape = PlaylistSongCardShape)
            .border(
                width = 1.5.dp,
                brush = PlaylistSongPlayingBorderBrush,
                shape = PlaylistSongCardShape
            )
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .background(color = PlaylistSongNormalBgColor, shape = PlaylistSongCardShape)
            .border(
                width = if (isDragging) 1.5.dp else 1.dp,
                color = if (isDragging) NeonPink else PlaylistSongNormalBorderColor,
                shape = PlaylistSongCardShape
            )
    }

    Column(
        modifier = cardModifier
            .clickable(onClick = handleRowClick)
            .testTag("song_item_${song.id}")
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Small square album artwork on the left
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(PlaylistThumbBackdropBrush, PlaylistSongThumbShape),
                contentAlignment = Alignment.Center
            ) {
                FastArtworkImage(
                    artworkUri = song.albumArtUriString ?: song.contentUriString,
                    cacheKeyPrefix = "thumb",
                    sizePx = 144,
                    contentDescription = "Album art",
                    fallbackUri = song.contentUriString,
                    fallbackPath = song.path,
                    precomputedIdentity = song.artworkSourceIdentity,
                    precomputedCacheKey = song.thumbCacheKey,
                    precomputedPrimaryCacheKey = song.primaryThumbCacheKey,
                    imageClipShape = PlaylistSongThumbShape,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = if (isCurrentTrack) NeonPink else NeonPurple,
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (isCurrentTrack) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PlaylistPlayingThumbOverlayColor, PlaylistSongThumbShape)
                            .testTag("playlist_playing_indicator_${song.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Equalizer,
                            contentDescription = if (isPlaying) "Playing" else "Current track",
                            tint = NeonPink,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // 2. Song title & Artist/album information below the title
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCurrentTrack) NeonPinkLight else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artistAlbumSubtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = if (isCurrentTrack) NeonPink.copy(alpha = 0.9f) else TextSecondaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 3. Favorite heart icon
            FastIconActionButton(
                onClick = handleToggleFavorite,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("fav_btn_${song.id}")
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) NeonPink else TextSecondaryDark,
                    modifier = Modifier.size(19.dp)
                )
            }

            // 4. Three-dot More menu
            Box {
                FastIconActionButton(
                    onClick = handleOpenMenu,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("more_btn_${song.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Song options",
                        tint = TextSecondaryDark,
                        modifier = Modifier.size(19.dp)
                    )
                }

                if (menuExpanded) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = PopupMenuContainerColor
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play Next", color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = NeonPink
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onPlayNext(song)
                            },
                            modifier = Modifier.testTag("playlist_menu_play_next_${song.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue", color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = null,
                                    tint = NeonPurple
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onAddToQueue(song)
                            },
                            modifier = Modifier.testTag("playlist_menu_add_queue_${song.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Playlist", color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = null,
                                    tint = NeonPinkLight
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onAddToPlaylist(song)
                            },
                            modifier = Modifier.testTag("playlist_menu_add_playlist_${song.id}")
                        )
                        if (index > 0) {
                            DropdownMenuItem(
                                text = { Text("Move Up", color = Color.White) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = null,
                                        tint = NeonPinkLight
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onReorderPlaylistSongs(playlistId, index, index - 1)
                                },
                                modifier = Modifier.testTag("playlist_menu_move_up_${song.id}")
                            )
                        }
                        if (index < totalCount - 1) {
                            DropdownMenuItem(
                                text = { Text("Move Down", color = Color.White) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = NeonPinkLight
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onReorderPlaylistSongs(playlistId, index, index + 1)
                                },
                                modifier = Modifier.testTag("playlist_menu_move_down_${song.id}")
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Remove from Playlist", color = Color(0xFFFF6E9C)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = Color(0xFFFF6E9C)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onRemoveFromPlaylist(playlistId, song.id)
                            },
                            modifier = Modifier.testTag("playlist_menu_remove_${song.id}")
                        )
                    }
                }
            }

            // 5. Drag/reorder handle on the far right
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pointerInput(playlistId, song.id) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragAccumulatedPx = 0f
                            },
                            onDragEnd = {
                                isDragging = false
                                dragAccumulatedPx = 0f
                            },
                            onDragCancel = {
                                isDragging = false
                                dragAccumulatedPx = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragAccumulatedPx += dragAmount
                                val curIdx = currentIndexState
                                val count = currentTotalCountState
                                if (dragAccumulatedPx >= stepThresholdPx && curIdx < count - 1) {
                                    dragAccumulatedPx -= stepThresholdPx
                                    onReorderPlaylistSongs(playlistId, curIdx, curIdx + 1)
                                } else if (dragAccumulatedPx <= -stepThresholdPx && curIdx > 0) {
                                    dragAccumulatedPx += stepThresholdPx
                                    onReorderPlaylistSongs(playlistId, curIdx, curIdx - 1)
                                }
                            }
                        )
                    }
                    .testTag("playlist_drag_handle_${song.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Reorder track",
                    tint = if (isDragging || isCurrentTrack) NeonPinkLight else TextSecondaryDark.copy(alpha = 0.78f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Clean pink playback/progress indicator bar for the currently playing track
        if (isCurrentTrack) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(PlaylistSongProgressTrackColor, CircleShape)
                    .testTag("playlist_playing_progress_${song.id}")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (isPlaying) 0.62f else 0.38f)
                        .height(3.dp)
                        .background(PlaylistSongProgressFillBrush, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun PlaylistItemCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PlaylistSongNormalBgColor, shape = PlaylistSongCardShape)
            .border(width = 1.dp, color = PlaylistSongNormalBorderColor, shape = PlaylistSongCardShape)
            .clip(PlaylistSongCardShape)
            .clickable(onClick = onClick)
            .testTag("playlist_item_${playlist.id}")
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(PlaylistHeaderArtworkBrush, PlaylistSongThumbShape)
                .border(1.dp, PlaylistHeaderArtworkBorderBrush, PlaylistSongThumbShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = NeonPinkLight,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${playlist.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = NeonPink.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag("delete_playlist_item_btn_${playlist.id}")
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete playlist",
                tint = Color(0xFFFF6E9C).copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var playlistName by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PopupMenuContainerColor,
        title = {
            Text(
                text = "New Playlist",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter a name for your custom playlist:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryDark
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = {
                        playlistName = it
                        if (isError && it.isNotBlank()) isError = false
                    },
                    placeholder = { Text("e.g. my music, Gym Beats…") },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text("Playlist name cannot be empty") }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_playlist_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (playlistName.trim().isEmpty()) {
                        isError = true
                    } else {
                        onConfirm(playlistName.trim())
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                modifier = Modifier.testTag("create_playlist_confirm_btn")
            ) {
                Text("Create", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )
}

/**
 * Dialog to add a single song to an existing or new playlist.
 */
@Composable
fun AddToPlaylistDialog(
    song: Song,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreatePlaylistWithSong: (String) -> Unit
) {
    var showCreateSubDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PopupMenuContainerColor,
        title = {
            Text(
                text = "Add to Playlist",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NeonPinkLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = NeonPurple.copy(alpha = 0.25f))
                Spacer(modifier = Modifier.height(10.dp))

                // Create new playlist button
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCreateSubDialog = true }
                        .testTag("dialog_create_new_playlist_btn"),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF281046),
                    border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = NeonPinkLight,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "New Playlist…",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No custom playlists yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondaryDark
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectPlaylist(playlist) }
                                    .testTag("dialog_select_playlist_${playlist.id}"),
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                        contentDescription = null,
                                        tint = NeonPink,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${playlist.trackCount} tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondaryDark
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )

    if (showCreateSubDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateSubDialog = false },
            onConfirm = { name ->
                showCreateSubDialog = false
                onCreatePlaylistWithSong(name)
            }
        )
    }
}

/**
 * Dialog to select and add multiple tracks into a playlist.
 */
@Composable
fun AddTracksToPlaylistDialog(
    playlistName: String,
    availableSongs: List<Song>,
    onDismiss: () -> Unit,
    onAddTracks: (List<Long>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<Long>() }

    val filteredSongs = remember(availableSongs, searchQuery) {
        if (searchQuery.isBlank()) availableSongs
        else availableSongs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PopupMenuContainerColor,
        title = {
            Text(
                text = "Add to \"$playlistName\"",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search songs…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_tracks_search_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (filteredSongs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (availableSongs.isEmpty()) {
                                "All tracks are already in this playlist"
                            } else {
                                "No matching songs found"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondaryDark
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .testTag("add_tracks_dialog_list")
                    ) {
                        items(
                            items = filteredSongs,
                            key = { it.id },
                            contentType = { "selectable_song" }
                        ) { song ->
                            val isChecked = selectedIds.contains(song.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isChecked) selectedIds.remove(song.id)
                                        else selectedIds.add(song.id)
                                    }
                                    .testTag("add_tracks_item_${song.id}")
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedIds.add(song.id)
                                        else selectedIds.remove(song.id)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = NeonPink,
                                        uncheckedColor = TextSecondaryDark
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${song.artist} • ${song.formattedDuration}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondaryDark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAddTracks(selectedIds.toList()) },
                enabled = selectedIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                modifier = Modifier.testTag("add_tracks_confirm_btn")
            ) {
                Text("Add (${selectedIds.size})", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )
}
