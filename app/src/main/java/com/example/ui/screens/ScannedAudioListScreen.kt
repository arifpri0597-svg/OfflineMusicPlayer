package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.Song
import com.example.data.model.SortOrder
import com.example.ui.components.EmptyStateView

/**
 * Jetpack Compose screen that displays the list of scanned audio files
 * queried from the local Room database using a LazyColumn.
 */
@Composable
fun ScannedAudioListScreen(
    songs: List<Song>,
    isLoading: Boolean,
    favoriteIds: Set<Long>,
    currentPlayingSongId: Long?,
    isPlaying: Boolean,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShuffleAll: (List<Song>) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    onRescanStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedSortOrder by remember { mutableStateOf(SortOrder.TITLE_ASC) }

    val filteredAndSortedSongs by remember(songs, searchQuery, selectedSortOrder) {
        derivedStateOf {
            val query = searchQuery.trim().lowercase()
            val filtered = if (query.isEmpty()) {
                songs
            } else {
                songs.filter { song ->
                    song.title.lowercase().contains(query) ||
                    song.artist.lowercase().contains(query) ||
                    song.album.lowercase().contains(query) ||
                    song.folderName.lowercase().contains(query)
                }
            }

            when (selectedSortOrder) {
                SortOrder.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
                SortOrder.ARTIST_ASC -> filtered.sortedBy { it.artist.lowercase() }
                SortOrder.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
                SortOrder.DATE_ADDED_DESC -> filtered.sortedByDescending { it.dateAdded }
            }
        }
    }

    val totalDurationFormatted by remember(songs) {
        derivedStateOf {
            val totalSeconds = songs.sumOf { it.durationMs } / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }
    }

    val totalStorageFormatted by remember(songs) {
        derivedStateOf {
            val totalBytes = songs.sumOf { it.sizeBytes }
            val mb = totalBytes.toDouble() / (1024 * 1024)
            if (mb >= 1024) {
                String.format("%.2f GB", mb / 1024)
            } else {
                String.format("%.1f MB", mb)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("scanned_audio_list_screen"),
        floatingActionButton = {
            if (filteredAndSortedSongs.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { onShuffleAll(filteredAndSortedSongs) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier.testTag("fab_shuffle_scanned")
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle All Scanned Tracks"
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Top Bar & Rescan Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Scanned Audio Library",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${songs.size} tracks from database • $totalDurationFormatted • $totalStorageFormatted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onRescanStorage,
                    enabled = !isLoading,
                    modifier = Modifier.testTag("rescan_storage_btn")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Rescan MediaStore storage",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("scanned_audio_search_field"),
                placeholder = { Text("Search title, artist, album, folder…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            // Sorting Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedSortOrder == SortOrder.TITLE_ASC,
                    onClick = { selectedSortOrder = SortOrder.TITLE_ASC },
                    label = { Text("Title A-Z") }
                )
                FilterChip(
                    selected = selectedSortOrder == SortOrder.ARTIST_ASC,
                    onClick = { selectedSortOrder = SortOrder.ARTIST_ASC },
                    label = { Text("Artist") }
                )
                FilterChip(
                    selected = selectedSortOrder == SortOrder.DURATION_DESC,
                    onClick = { selectedSortOrder = SortOrder.DURATION_DESC },
                    label = { Text("Duration") }
                )
                FilterChip(
                    selected = selectedSortOrder == SortOrder.DATE_ADDED_DESC,
                    onClick = { selectedSortOrder = SortOrder.DATE_ADDED_DESC },
                    label = { Text("Recent") }
                )
            }

            // Body: Empty State or LazyColumn
            if (filteredAndSortedSongs.isEmpty()) {
                if (searchQuery.isNotBlank()) {
                    EmptyStateView(
                        icon = Icons.Default.SearchOff,
                        title = "No Matches Found",
                        description = "No audio files matching \"$searchQuery\" in the database.",
                        actionButtonText = "Clear Filter",
                        onActionClick = { searchQuery = "" },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    EmptyStateView(
                        icon = Icons.Default.Storage,
                        title = "No Audio Files Found",
                        description = "The local database has no scanned audio files yet. Tap below to scan your device storage using the MediaStore API.",
                        actionButtonText = "Scan Storage Now",
                        onActionClick = onRescanStorage,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                // Main LazyColumn
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("scanned_audio_lazy_column"),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    // Quick Action Row: Play All & Shuffle All
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { onPlayAll(filteredAndSortedSongs) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play All (${filteredAndSortedSongs.size})")
                            }

                            OutlinedButton(
                                onClick = { onShuffleAll(filteredAndSortedSongs) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Shuffle All")
                            }
                        }
                    }

                    // Scanned Audio Song Items in LazyColumn
                    items(
                        items = filteredAndSortedSongs,
                        key = { it.id }
                    ) { song ->
                        val isCurrentPlaying = isPlaying && currentPlayingSongId == song.id
                        val isFavorite = favoriteIds.contains(song.id)

                        ScannedAudioItemRow(
                            song = song,
                            isPlaying = isCurrentPlaying,
                            isFavorite = isFavorite,
                            onSongClick = { onSongClick(song, filteredAndSortedSongs) },
                            onToggleFavorite = { onToggleFavorite(song.id) },
                            onPlayNext = { onPlayNext(song) },
                            onAddToQueue = { onAddToQueue(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual item row for each audio file in the LazyColumn.
 */
@Composable
private fun ScannedAudioItemRow(
    song: Song,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onSongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSongClick)
            .testTag("scanned_audio_row_${song.id}"),
        color = if (isPlaying) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork / Thumbnail with Coil
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = "Cover for ${song.title}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Metadata Column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${song.artist} • ${song.album}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.formattedDuration,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (song.folderName.isNotBlank()) {
                        Text(
                            text = " • ${song.folderName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Favorite Button
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.testTag("scanned_audio_fav_${song.id}")
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Overflow Options Menu
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("scanned_audio_menu_${song.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More actions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onPlayNext()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAddToQueue()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Playlist") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist()
                        }
                    )
                }
            }
        }
    }
}
