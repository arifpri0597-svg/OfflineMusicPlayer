package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.Album
import com.example.data.model.Artist
import com.example.data.model.Folder
import com.example.data.model.Song
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonPurple

private val SongRowShape = RoundedCornerShape(18.dp)
private val SongThumbShape = RoundedCornerShape(14.dp)
private val AlbumCardShape = RoundedCornerShape(20.dp)
private val PlayingOverlayColor = Color.Black.copy(alpha = 0.45f)
private val PlayingBorderColor = NeonPink.copy(alpha = 0.7f)

private val SongThumbBrush = Brush.linearGradient(
    colors = listOf(
        ElectricViolet.copy(alpha = 0.45f),
        NeonPink.copy(alpha = 0.35f)
    )
)

private val AlbumPlaceholderBrush = Brush.linearGradient(
    colors = listOf(
        ElectricViolet.copy(alpha = 0.35f),
        NeonPink.copy(alpha = 0.25f)
    )
)

private val ArtistAvatarBrush = Brush.linearGradient(
    colors = listOf(ElectricViolet, NeonPink)
)

private val FolderIconBrush = Brush.linearGradient(
    colors = listOf(NeonPurple, NeonPink)
)

@Composable
fun SongItemRow(
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
    val handleSongClick = remember(song, onSongClick) { { onSongClick(song) } }
    val handleToggleFavorite = remember(song.id, onToggleFavorite) { { onToggleFavorite(song.id) } }
    val handlePlayNext = remember(song, onPlayNext) { { onPlayNext(song) } }
    val handleAddToQueue = remember(song, onAddToQueue) { { onAddToQueue(song) } }
    val handleAddToPlaylist = remember(song, onAddToPlaylist) { { onAddToPlaylist(song) } }

    SongItemRow(
        song = song,
        isPlaying = isPlaying,
        isFavorite = isFavorite,
        onSongClick = handleSongClick,
        onToggleFavorite = handleToggleFavorite,
        onPlayNext = handlePlayNext,
        onAddToQueue = handleAddToQueue,
        onAddToPlaylist = handleAddToPlaylist,
        modifier = modifier
    )
}

@Composable
fun SongItemRow(
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
    var menuExpanded by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    val cardBgColor = if (isPlaying) {
        colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    val borderColor = if (isPlaying) {
        PlayingBorderColor
    } else {
        colorScheme.outline.copy(alpha = 0.25f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(color = cardBgColor, shape = SongRowShape)
            .border(width = 1.dp, color = borderColor, shape = SongRowShape)
            .clip(SongRowShape)
            .clickable(onClick = onSongClick)
            .testTag("song_item_${song.id}")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album art thumbnail with neon gradient backdrop & lock-free LruCache
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(SongThumbBrush, SongThumbShape)
                .clip(SongThumbShape),
            contentAlignment = Alignment.Center
        ) {
            FastArtworkImage(
                artworkUri = song.albumArtUriString ?: song.contentUriString,
                cacheKeyPrefix = "thumb",
                sizePx = 144,
                contentDescription = "Album art",
                fallbackUri = song.contentUriString,
                fallbackPath = song.path,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (isPlaying) NeonPink else NeonPurple,
                    modifier = Modifier.size(24.dp)
                )
            }

            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PlayingOverlayColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = "Playing",
                        tint = NeonPink,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title & Artist
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isPlaying) NeonPink else colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = song.durationSuffix,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPlaying) NeonPurple else colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        // Favorite Button
        val favColor = if (isFavorite) NeonPink else colorScheme.onSurfaceVariant
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .size(40.dp)
                .testTag("fav_btn_${song.id}")
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = favColor,
                modifier = Modifier.size(20.dp)
            )
        }

        // More Options Menu (only compose DropdownMenu popup when expanded)
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("more_btn_${song.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Song options",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (menuExpanded) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = NeonPink) },
                        onClick = {
                            menuExpanded = false
                            onPlayNext()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = NeonPurple) },
                        onClick = {
                            menuExpanded = false
                            onAddToQueue()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Playlist") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = NeonPink) },
                        onClick = {
                            menuExpanded = false
                            onAddToPlaylist()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumCard(
    album: Album,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    val handleClick = remember(album, onAlbumClick) { { onAlbumClick(album) } }
    AlbumCard(
        album = album,
        onClick = handleClick,
        modifier = modifier
    )
}

@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val borderColor = colorScheme.outline.copy(alpha = 0.3f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = containerColor, shape = AlbumCardShape)
            .border(width = 1.dp, color = borderColor, shape = AlbumCardShape)
            .clip(AlbumCardShape)
            .clickable(onClick = onClick)
            .testTag("album_card_${album.id}")
    ) {
        FastArtworkImage(
            artworkUri = album.artworkUriString ?: album.fallbackAudioUriString,
            cacheKeyPrefix = "album",
            sizePx = 256,
            contentDescription = album.title,
            fallbackUri = album.fallbackAudioUriString,
            fallbackPath = album.fallbackAudioPath,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(AlbumPlaceholderBrush)
        ) {
            Icon(
                imageVector = Icons.Default.Album,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = NeonPink
            )
        }
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = album.tracksSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ArtistItemRow(
    artist: Artist,
    onArtistClick: (Artist) -> Unit,
    modifier: Modifier = Modifier
) {
    val handleClick = remember(artist, onArtistClick) { { onArtistClick(artist) } }
    ArtistItemRow(
        artist = artist,
        onClick = handleClick,
        modifier = modifier
    )
}

@Composable
fun ArtistItemRow(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val borderColor = colorScheme.outline.copy(alpha = 0.25f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(color = bgColor, shape = SongRowShape)
            .border(width = 1.dp, color = borderColor, shape = SongRowShape)
            .clip(SongRowShape)
            .clickable(onClick = onClick)
            .testTag("artist_item_${artist.name}")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(ArtistAvatarBrush, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = artist.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FolderItemRow(
    folder: Folder,
    onFolderClick: (Folder) -> Unit,
    modifier: Modifier = Modifier
) {
    val handleClick = remember(folder, onFolderClick) { { onFolderClick(folder) } }
    FolderItemRow(
        folder = folder,
        onClick = handleClick,
        modifier = modifier
    )
}

@Composable
fun FolderItemRow(
    folder: Folder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val borderColor = colorScheme.outline.copy(alpha = 0.25f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(color = bgColor, shape = SongRowShape)
            .border(width = 1.dp, color = borderColor, shape = SongRowShape)
            .clip(SongRowShape)
            .clickable(onClick = onClick)
            .testTag("folder_item_${folder.name}")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(FolderIconBrush, SongThumbShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = folder.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SearchBarField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholderText: String = "Search songs, artists, albums…"
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("search_field"),
        placeholder = {
            Text(
                text = placeholderText,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search icon",
                tint = NeonPink
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeonPink,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    )
}

@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionButtonText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            NeonPink.copy(alpha = 0.25f),
                            NeonPurple.copy(alpha = 0.25f)
                        )
                    )
                )
                .border(1.dp, NeonPink.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = NeonPink
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionButtonText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onActionClick,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                modifier = Modifier.testTag("empty_state_action_btn")
            ) {
                Text(actionButtonText, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

