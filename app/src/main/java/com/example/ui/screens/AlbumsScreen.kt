package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.Album
import com.example.data.model.Song
import com.example.ui.components.AlbumCard
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SongItemRow

@Composable
fun AlbumsScreen(
    albums: List<Album>,
    allSongs: List<Song>,
    songsByAlbum: Map<String, List<Song>> = emptyMap(),
    selectedAlbum: Album?,
    favoriteIds: Set<Long>,
    currentSongId: Long?,
    isPlaying: Boolean,
    onAlbumSelect: (Album?) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShuffleAll: (List<Song>) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (selectedAlbum != null) {
        BackHandler {
            onAlbumSelect(null)
        }

        val albumSongs = remember(selectedAlbum, songsByAlbum, allSongs) {
            songsByAlbum[selectedAlbum.title.lowercase(java.util.Locale.ROOT)]
                ?: allSongs.filter { it.album.equals(selectedAlbum.title, ignoreCase = true) }
        }
        val onAlbumSongClick = remember(onSongClick, albumSongs) {
            { song: Song -> onSongClick(song, albumSongs) }
        }
        val detailListState = androidx.compose.foundation.lazy.rememberLazyListState()
        com.example.ui.components.LazyListSongArtworkPreloader(
            listState = detailListState,
            songs = albumSongs,
            headerItemCount = 1
        )

        LazyColumn(
            state = detailListState,
            modifier = modifier
                .fillMaxSize()
                .testTag("album_detail_screen"),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item(key = "album_detail_header", contentType = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    IconButton(
                        onClick = { onAlbumSelect(null) },
                        modifier = Modifier.testTag("album_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to albums"
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.example.ui.components.FastArtworkImage(
                            artworkUri = selectedAlbum.artworkUriString ?: selectedAlbum.fallbackAudioUriString,
                            cacheKeyPrefix = "album",
                            sizePx = 256,
                            contentDescription = selectedAlbum.title,
                            fallbackUri = selectedAlbum.fallbackAudioUriString,
                            fallbackPath = selectedAlbum.fallbackAudioPath,
                            precomputedIdentity = selectedAlbum.artworkSourceIdentity,
                            precomputedCacheKey = selectedAlbum.albumCacheKey,
                            precomputedPrimaryCacheKey = selectedAlbum.primaryAlbumCacheKey,
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedAlbum.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = selectedAlbum.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${albumSongs.size} tracks",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onPlayAll(albumSongs) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play")
                        }

                        OutlinedButton(
                            onClick = { onShuffleAll(albumSongs) },
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
                items = albumSongs,
                key = { it.id },
                contentType = { "album_song" }
            ) { song ->
                SongItemRow(
                    song = song,
                    isPlaying = isPlaying && currentSongId == song.id,
                    isFavorite = favoriteIds.contains(song.id),
                    onSongClick = onAlbumSongClick,
                    onToggleFavorite = onToggleFavorite,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist
                )
            }
        }
    } else {
        if (albums.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.Album,
                title = "No Albums Found",
                description = "No albums discovered in your audio library.",
                modifier = modifier.fillMaxSize()
            )
        } else {
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            com.example.ui.components.LazyGridAlbumArtworkPreloader(
                gridState = gridState,
                albums = albums
            )
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(160.dp),
                modifier = modifier
                    .fillMaxSize()
                    .testTag("albums_grid"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(
                    items = albums,
                    key = { it.stableKey },
                    contentType = { "album_card" }
                ) { album ->
                    AlbumCard(
                        album = album,
                        onAlbumClick = onAlbumSelect
                    )
                }
            }
        }
    }
}
