package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.Album
import com.example.data.model.Song
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SongItemRow

@Composable
fun HomeScreen(
    songs: List<Song>,
    albums: List<Album>,
    favoriteSongs: List<Song>,
    recentSongs: List<Song>,
    currentSongId: Long?,
    isPlaying: Boolean,
    onSongClick: (Song, List<Song>) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayAll: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    onNavigateToSongs: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) {
        EmptyStateView(
            icon = Icons.Default.MusicNote,
            title = "No Music Found",
            description = "Pulse Music couldn't find any audio files in your local storage. Transfer some music files to your device and pull down to refresh.",
            modifier = modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        // Welcome Header & Quick Actions
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "Pulse Music",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${songs.size} local tracks • ${albums.size} albums",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Quick Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onShuffleAll,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("home_shuffle_all_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Shuffle All", fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onPlayAll,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("home_play_all_btn")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play All", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Recently Played Section (if any)
        if (recentSongs.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Recently Played",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recentSongs.take(10)) { song ->
                            RecentSongCard(
                                song = song,
                                isPlaying = isPlaying && currentSongId == song.id,
                                onClick = { onSongClick(song, recentSongs) }
                            )
                        }
                    }
                }
            }
        }

        // Albums Carousel
        if (albums.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text(
                        text = "Albums",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(albums.take(8)) { album ->
                            HomeAlbumCard(
                                album = album,
                                onClick = { onAlbumClick(album) }
                            )
                        }
                    }
                }
            }
        }

        // Favorites Preview (if any)
        if (favoriteSongs.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Favorite Tracks (${favoriteSongs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            items(favoriteSongs.take(5)) { song ->
                SongItemRow(
                    song = song,
                    isPlaying = isPlaying && currentSongId == song.id,
                    isFavorite = true,
                    onSongClick = { onSongClick(song, favoriteSongs) },
                    onToggleFavorite = { onToggleFavorite(song.id) },
                    onPlayNext = { onPlayNext(song) },
                    onAddToQueue = { onAddToQueue(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) }
                )
            }
        }

        // Quick Songs list preview
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "All Tracks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "View All",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onNavigateToSongs)
                )
            }
        }

        items(songs.take(10)) { song ->
            SongItemRow(
                song = song,
                isPlaying = isPlaying && currentSongId == song.id,
                isFavorite = favoriteSongs.any { it.id == song.id },
                onSongClick = { onSongClick(song, songs) },
                onToggleFavorite = { onToggleFavorite(song.id) },
                onPlayNext = { onPlayNext(song) },
                onAddToQueue = { onAddToQueue(song) },
                onAddToPlaylist = { onAddToPlaylist(song) }
            )
        }
    }
}

@Composable
private fun RecentSongCard(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .size(114.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeAlbumCard(
    album: Album,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (album.artworkUri != null) {
                    AsyncImage(
                        model = album.artworkUri,
                        contentDescription = album.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.songCount} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
