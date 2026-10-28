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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.Song
import com.example.data.model.SortOrder
import com.example.ui.components.EmptyStateView

private val ScannedThumbShape = RoundedCornerShape(10.dp)

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
    totalDurationFormatted: String = "",
    totalStorageFormatted: String = "",
    displayedSongs: List<Song>? = null,
    externalSearchQuery: String? = null,
    externalSortOrder: SortOrder? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    onSortOrderChange: ((SortOrder) -> Unit)? = null,
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
    var localSearchQuery by remember { mutableStateOf("") }
    var localSortOrder by remember { mutableStateOf(SortOrder.TITLE_ASC) }

    val searchQuery = externalSearchQuery ?: localSearchQuery
    val selectedSortOrder = externalSortOrder ?: localSortOrder
    val updateSearchQuery: (String) -> Unit = remember(onSearchQueryChange) {
        onSearchQueryChange ?: { localSearchQuery = it }
    }
    val updateSortOrder: (SortOrder) -> Unit = remember(onSortOrderChange) {
        onSortOrderChange ?: { localSortOrder = it }
    }

    val filteredAndSortedSongs by remember(displayedSongs, songs, searchQuery, selectedSortOrder) {
        derivedStateOf {
            if (displayedSongs != null) {
                displayedSongs
            } else {
                val query = searchQuery.trim()
                val filtered = if (query.isEmpty()) {
                    songs
                } else {
                    songs.filter { song ->
                        song.title.contains(query, ignoreCase = true) ||
                            song.artist.contains(query, ignoreCase = true) ||
                            song.album.contains(query, ignoreCase = true) ||
                            song.folderName.contains(query, ignoreCase = true)
                    }
                }

                when (selectedSortOrder) {
                    SortOrder.TITLE_ASC -> filtered
                    SortOrder.ARTIST_ASC -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
                    SortOrder.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
                    SortOrder.DATE_ADDED_DESC -> filtered.sortedByDescending { it.dateAdded }
                }
            }
        }
    }

    val effectiveDurationFormatted = remember(songs, totalDurationFormatted) {
        if (totalDurationFormatted.isNotBlank()) {
            totalDurationFormatted
        } else {
            val totalSeconds = songs.sumOf { it.durationMs } / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }
    }

    val effectiveStorageFormatted = remember(songs, totalStorageFormatted) {
        if (totalStorageFormatted.isNotBlank()) {
            totalStorageFormatted
        } else {
            val totalBytes = songs.sumOf { it.sizeBytes }
            val mb = totalBytes.toDouble() / (1024 * 1024)
            if (mb >= 1024) {
                String.format(java.util.Locale.US, "%.2f GB", mb / 1024)
            } else {
                String.format(java.util.Locale.US, "%.1f MB", mb)
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
                        text = "${songs.size} tracks from database • $effectiveDurationFormatted • $effectiveStorageFormatted",
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
                onValueChange = updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("scanned_audio_search_field"),
                placeholder = { Text("Search title, artist, album, folder…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { updateSearchQuery("") }) {
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
                    onClick = { updateSortOrder(SortOrder.TITLE_ASC) },
                    label = { Text("Title A-Z") }
                )
                FilterChip(
                    selected = selectedSortOrder == SortOrder.ARTIST_ASC,
                    onClick = { updateSortOrder(SortOrder.ARTIST_ASC) },
                    label = { Text("Artist") }
                )
                FilterChip(
                    selected = selectedSortOrder == SortOrder.DURATION_DESC,
                    onClick = { updateSortOrder(SortOrder.DURATION_DESC) },
                    label = { Text("Duration") }
                )
                FilterChip(
                    selected = selectedSortOrder == SortOrder.DATE_ADDED_DESC,
                    onClick = { updateSortOrder(SortOrder.DATE_ADDED_DESC) },
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
                        onActionClick = { updateSearchQuery("") },
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
                val onScannedSongClick = remember(onSongClick, filteredAndSortedSongs) {
                    { song: Song -> onSongClick(song, filteredAndSortedSongs) }
                }
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                com.example.ui.components.LazyListSongArtworkPreloader(
                    listState = listState,
                    songs = filteredAndSortedSongs,
                    headerItemCount = 1
                )
                // Main LazyColumn
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("scanned_audio_lazy_column"),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    // Quick Action Row: Play All & Shuffle All
                    item(key = "scanned_quick_actions", contentType = "quick_actions") {
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
                        key = { it.id },
                        contentType = { "scanned_song" }
                    ) { song ->
                        val isCurrentPlaying = isPlaying && currentPlayingSongId == song.id
                        val isFavorite = favoriteIds.contains(song.id)

                        ScannedAudioItemRow(
                            song = song,
                            isPlaying = isCurrentPlaying,
                            isFavorite = isFavorite,
                            onSongClick = onScannedSongClick,
                            onToggleFavorite = onToggleFavorite,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onAddToPlaylist = onAddToPlaylist
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
    onSongClick: (Song) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val handleSongClick = remember(song, onSongClick) { { onSongClick(song) } }
    val handleToggleFavorite = remember(song.id, onToggleFavorite) { { onToggleFavorite(song.id) } }

    val colorScheme = MaterialTheme.colorScheme
    val rowBgColor = if (isPlaying) {
        colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .clickable(onClick = handleSongClick)
            .testTag("scanned_audio_row_${song.id}")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Artwork / Thumbnail with lock-free LruCache & negative URI cache
        com.example.ui.components.FastArtworkImage(
            artworkUri = song.albumArtUriString ?: song.contentUriString,
            cacheKeyPrefix = "thumb",
            sizePx = 144,
            contentDescription = song.title,
            fallbackUri = song.contentUriString,
            fallbackPath = song.path,
            precomputedIdentity = song.artworkSourceIdentity,
            precomputedCacheKey = song.thumbCacheKey,
            modifier = Modifier
                .size(50.dp)
                .background(colorScheme.surfaceVariant, ScannedThumbShape)
                .clip(ScannedThumbShape)
        ) {
            Icon(
                imageVector = Icons.Default.Audiotrack,
                contentDescription = null,
                tint = if (isPlaying) colorScheme.primary else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Metadata Column
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isPlaying) colorScheme.primary else colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artistAlbumSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.durationFolderSubtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Favorite Button
        IconButton(
            onClick = handleToggleFavorite,
            modifier = Modifier.testTag("scanned_audio_fav_${song.id}")
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (isFavorite) colorScheme.tertiary else colorScheme.onSurfaceVariant
            )
        }

        // Overflow Options Menu (only compose DropdownMenu popup when open)
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.testTag("scanned_audio_menu_${song.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More actions",
                    tint = colorScheme.onSurfaceVariant
                )
            }

            if (menuOpen) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onPlayNext(song)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAddToQueue(song)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Playlist") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist(song)
                        }
                    )
                }
            }
        }
    }
}
