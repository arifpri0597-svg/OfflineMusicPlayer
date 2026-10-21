package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Song
import com.example.data.model.SortOrder
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SearchBarField
import com.example.ui.components.SongItemRow

@Composable
fun SongsScreen(
    songs: List<Song>,
    favoriteIds: Set<Long>,
    currentSongId: Long?,
    isPlaying: Boolean,
    searchQuery: String,
    sortOrder: SortOrder,
    onSearchQueryChange: (String) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayAll: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredSongs by remember(songs, searchQuery, sortOrder) {
        derivedStateOf {
            val q = searchQuery.trim()
            val queried = if (q.isEmpty()) {
                songs
            } else {
                songs.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.artist.contains(q, ignoreCase = true) ||
                        it.album.contains(q, ignoreCase = true)
                }
            }

            when (sortOrder) {
                SortOrder.TITLE_ASC -> queried.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                SortOrder.ARTIST_ASC -> queried.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
                SortOrder.DURATION_DESC -> queried.sortedByDescending { it.durationMs }
                SortOrder.DATE_ADDED_DESC -> queried.sortedByDescending { it.dateAdded }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("songs_screen")
    ) {
        // Search Bar
        SearchBarField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            placeholderText = "Search in all ${songs.size} tracks…"
        )

        // Sort Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Sort,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )

            FilterChip(
                selected = sortOrder == SortOrder.TITLE_ASC,
                onClick = { onSortOrderChange(SortOrder.TITLE_ASC) },
                label = { Text("Title") },
                colors = FilterChipDefaults.filterChipColors()
            )
            FilterChip(
                selected = sortOrder == SortOrder.ARTIST_ASC,
                onClick = { onSortOrderChange(SortOrder.ARTIST_ASC) },
                label = { Text("Artist") }
            )
            FilterChip(
                selected = sortOrder == SortOrder.DURATION_DESC,
                onClick = { onSortOrderChange(SortOrder.DURATION_DESC) },
                label = { Text("Duration") }
            )
            FilterChip(
                selected = sortOrder == SortOrder.DATE_ADDED_DESC,
                onClick = { onSortOrderChange(SortOrder.DATE_ADDED_DESC) },
                label = { Text("Recently Added") }
            )
        }

        if (filteredSongs.isEmpty()) {
            if (searchQuery.isNotBlank()) {
                EmptyStateView(
                    icon = Icons.Default.SearchOff,
                    title = "No Tracks Found",
                    description = "No songs matching \"$searchQuery\" were found in your library.",
                    actionButtonText = "Clear Search",
                    onActionClick = { onSearchQueryChange("") },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                EmptyStateView(
                    icon = Icons.Default.MusicNote,
                    title = "No Music Files",
                    description = "No audio tracks discovered on device storage.",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                // Actions Bar: Track count & Play/Shuffle buttons
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredSongs.size} Tracks",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onPlayAll,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play")
                            }

                            Button(
                                onClick = onShuffleAll,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Shuffle")
                            }
                        }
                    }
                }

                items(
                    items = filteredSongs,
                    key = { it.id },
                    contentType = { "song_row" }
                ) { song ->
                    SongItemRow(
                        song = song,
                        isPlaying = isPlaying && currentSongId == song.id,
                        isFavorite = favoriteIds.contains(song.id),
                        onSongClick = { onSongClick(song, filteredSongs) },
                        onToggleFavorite = { onToggleFavorite(song.id) },
                        onPlayNext = { onPlayNext(song) },
                        onAddToQueue = { onAddToQueue(song) }
                    )
                }
            }
        }
    }
}
