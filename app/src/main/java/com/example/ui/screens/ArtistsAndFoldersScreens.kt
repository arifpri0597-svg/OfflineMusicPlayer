package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Artist
import com.example.data.model.Folder
import com.example.data.model.Song
import com.example.ui.components.ArtistItemRow
import com.example.ui.components.EmptyStateView
import com.example.ui.components.FolderItemRow
import com.example.ui.components.SongItemRow

@Composable
fun ArtistsScreen(
    artists: List<Artist>,
    allSongs: List<Song>,
    songsByArtist: Map<String, List<Song>> = emptyMap(),
    selectedArtist: Artist?,
    favoriteIds: Set<Long>,
    currentSongId: Long?,
    isPlaying: Boolean,
    onArtistSelect: (Artist?) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShuffleAll: (List<Song>) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (selectedArtist != null) {
        BackHandler {
            onArtistSelect(null)
        }

        val artistSongs = remember(selectedArtist, songsByArtist, allSongs) {
            songsByArtist[selectedArtist.name.lowercase(java.util.Locale.ROOT)]
                ?: allSongs.filter { it.artist.equals(selectedArtist.name, ignoreCase = true) }
        }

        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag("artist_detail_screen"),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item(key = "artist_detail_header", contentType = "header") {
                Column(modifier = Modifier.padding(16.dp)) {
                    IconButton(
                        onClick = { onArtistSelect(null) },
                        modifier = Modifier.testTag("artist_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = selectedArtist.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${artistSongs.size} tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onPlayAll(artistSongs) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play All")
                        }
                        OutlinedButton(
                            onClick = { onShuffleAll(artistSongs) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                    }
                }
            }

            items(
                items = artistSongs,
                key = { it.id },
                contentType = { "artist_song" }
            ) { song ->
                SongItemRow(
                    song = song,
                    isPlaying = isPlaying && currentSongId == song.id,
                    isFavorite = favoriteIds.contains(song.id),
                    onSongClick = { onSongClick(song, artistSongs) },
                    onToggleFavorite = { onToggleFavorite(song.id) },
                    onPlayNext = { onPlayNext(song) },
                    onAddToQueue = { onAddToQueue(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) }
                )
            }
        }
    } else {
        if (artists.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.Person,
                title = "No Artists Found",
                description = "No artists discovered in library.",
                modifier = modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .testTag("artists_list"),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(
                    items = artists,
                    key = { it.name },
                    contentType = { "artist_row" }
                ) { artist ->
                    ArtistItemRow(
                        artist = artist,
                        onClick = { onArtistSelect(artist) }
                    )
                }
            }
        }
    }
}

@Composable
fun FoldersScreen(
    folders: List<Folder>,
    allSongs: List<Song>,
    songsByFolder: Map<String, List<Song>> = emptyMap(),
    selectedFolder: Folder?,
    favoriteIds: Set<Long>,
    currentSongId: Long?,
    isPlaying: Boolean,
    onFolderSelect: (Folder?) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShuffleAll: (List<Song>) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (selectedFolder != null) {
        BackHandler {
            onFolderSelect(null)
        }

        val folderSongs = remember(selectedFolder, songsByFolder, allSongs) {
            songsByFolder[selectedFolder.name.lowercase(java.util.Locale.ROOT)]
                ?: allSongs.filter { it.folderName.equals(selectedFolder.name, ignoreCase = true) }
        }

        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag("folder_detail_screen"),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item(key = "folder_detail_header", contentType = "header") {
                Column(modifier = Modifier.padding(16.dp)) {
                    IconButton(
                        onClick = { onFolderSelect(null) },
                        modifier = Modifier.testTag("folder_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = selectedFolder.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${folderSongs.size} audio files",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onPlayAll(folderSongs) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play All")
                        }
                        OutlinedButton(
                            onClick = { onShuffleAll(folderSongs) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                    }
                }
            }

            items(
                items = folderSongs,
                key = { it.id },
                contentType = { "folder_song" }
            ) { song ->
                SongItemRow(
                    song = song,
                    isPlaying = isPlaying && currentSongId == song.id,
                    isFavorite = favoriteIds.contains(song.id),
                    onSongClick = { onSongClick(song, folderSongs) },
                    onToggleFavorite = { onToggleFavorite(song.id) },
                    onPlayNext = { onPlayNext(song) },
                    onAddToQueue = { onAddToQueue(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) }
                )
            }
        }
    } else {
        if (folders.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.Folder,
                title = "No Folders Found",
                description = "No audio folders detected.",
                modifier = modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .testTag("folders_list"),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(
                    items = folders,
                    key = { "${it.name}_${it.path}" },
                    contentType = { "folder_row" }
                ) { folder ->
                    FolderItemRow(
                        folder = folder,
                        onClick = { onFolderSelect(folder) }
                    )
                }
            }
        }
    }
}
