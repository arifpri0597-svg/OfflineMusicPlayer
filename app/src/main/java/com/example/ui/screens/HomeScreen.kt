package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.Album
import com.example.data.model.Song
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SearchBarField
import com.example.ui.components.SongItemRow
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBackgroundSecondary
import com.example.ui.theme.DeepViolet
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonPinkLight
import com.example.ui.theme.NeonPurple

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
    onNavigateToAlbums: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSearchBar by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    if (songs.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(DarkBackgroundSecondary, DarkBackground)
                    )
                )
        ) {
            PureTuneTopHeader(
                onSearchClick = onNavigateToSongs,
                onSettingsClick = onNavigateToSettings
            )
            EmptyStateView(
                icon = Icons.Default.MusicNote,
                title = "No Music Found",
                description = "PureTune couldn't find any audio files in your local storage. Transfer some music files to your device and rescan.",
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    val displayedRecentSongs = remember(recentSongs, songs) {
        if (recentSongs.isNotEmpty()) recentSongs.take(10) else songs.take(8)
    }
    val albumsPreview = remember(albums) { albums.take(10) }
    val favoritesPreview = remember(favoriteSongs) { favoriteSongs.take(5) }
    val filteredAllTracks = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) {
            songs.take(15)
        } else {
            val q = searchQuery.trim()
            songs.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true) ||
                    it.album.contains(q, ignoreCase = true)
            }
        }
    }
    val favoriteIdSet = remember(favoriteSongs) {
        favoriteSongs.mapTo(HashSet(favoriteSongs.size)) { it.id }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF17092E),
                        DarkBackground,
                        Color(0xFF06030C)
                    )
                )
            )
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 104.dp)
    ) {
        // 1. PureTune / Offline Music Player Header with Search & Settings Icons
        item(key = "home_header", contentType = "header") {
            Column(modifier = Modifier.fillMaxWidth()) {
                PureTuneTopHeader(
                    onSearchClick = {
                        showSearchBar = !showSearchBar
                        if (!showSearchBar) searchQuery = ""
                    },
                    onSettingsClick = onNavigateToSettings
                )

                AnimatedVisibility(
                    visible = showSearchBar,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    SearchBarField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholderText = "Search PureTune tracks, artists, albums…"
                    )
                }

                // 2. Hero Banner Card
                PureTuneHeroBanner(
                    songCount = songs.size,
                    albumCount = albums.size,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 3. Shuffle All & Play All Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Neon Pink-Purple Gradient Shuffle All Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(NeonPink, NeonPurple)
                                )
                            )
                            .clickable(onClick = onShuffleAll)
                            .testTag("home_shuffle_all_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Shuffle All",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // Glassmorphic Neon-Bordered Play All Button
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(onClick = onPlayAll)
                            .testTag("home_play_all_btn"),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        border = BorderStroke(
                            1.2.dp,
                            Brush.horizontalGradient(listOf(NeonPurple, NeonPink))
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = NeonPinkLight,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Play All",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 4. Recently Played Section
        if (displayedRecentSongs.isNotEmpty() && searchQuery.isBlank()) {
            item(key = "home_recent_section", contentType = "carousel") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = NeonPink,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Recently Played",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = "History",
                            style = MaterialTheme.typography.labelLarge,
                            color = NeonPink,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onNavigateToHistory)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("home_view_history_btn")
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = displayedRecentSongs,
                            key = { it.id },
                            contentType = { "recent_card" }
                        ) { song ->
                            RecentSongCard(
                                song = song,
                                isPlaying = isPlaying && currentSongId == song.id,
                                onClick = { onSongClick(song, displayedRecentSongs) }
                            )
                        }
                    }
                }
            }
        }

        // 5. Albums Carousel Section
        if (albumsPreview.isNotEmpty() && searchQuery.isBlank()) {
            item(key = "home_albums_section", contentType = "carousel") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = NeonPurple,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Albums",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = "See All",
                            style = MaterialTheme.typography.labelLarge,
                            color = NeonPurple,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onNavigateToAlbums)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(
                            items = albumsPreview,
                            key = { "${it.id}_${it.title}" },
                            contentType = { "album_card" }
                        ) { album ->
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
        if (favoritesPreview.isNotEmpty() && searchQuery.isBlank()) {
            item(key = "home_favorites_header", contentType = "section_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = NeonPink,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Favorite Tracks (${favoriteSongs.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            items(
                items = favoritesPreview,
                key = { "fav_${it.id}" },
                contentType = { "song_row" }
            ) { song ->
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

        // 6. All Tracks Section
        item(key = "home_all_tracks_header", contentType = "section_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = NeonPink,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (searchQuery.isBlank()) "All Tracks" else "Search Results (${filteredAllTracks.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = "View All",
                    style = MaterialTheme.typography.labelLarge,
                    color = NeonPink,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onNavigateToSongs)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        items(
            items = filteredAllTracks,
            key = { "all_${it.id}" },
            contentType = { "song_row" }
        ) { song ->
            SongItemRow(
                song = song,
                isPlaying = isPlaying && currentSongId == song.id,
                isFavorite = song.isFavorite || favoriteIdSet.contains(song.id),
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
private fun PureTuneTopHeader(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(NeonPink, NeonPurple)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = "PureTune",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Offline Music Player",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonPinkLight,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.35f)),
                modifier = Modifier.size(42.dp)
            ) {
                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier.testTag("home_search_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Music",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.35f)),
                modifier = Modifier.size(42.dp)
            ) {
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.testTag("home_settings_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Open Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PureTuneHeroBanner(
    songCount: Int,
    albumCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(152.dp)
            .testTag("home_hero_banner"),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                colors = listOf(NeonPink.copy(alpha = 0.65f), NeonPurple.copy(alpha = 0.45f))
            )
        ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF2B0B3F),
                            DeepViolet,
                            Color(0xFF5B1049)
                        )
                    )
                )
        ) {
            // Decorative Neon Soundwaves & Glowing Orbs in Hero Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height

                // Ambient neon glow circle on the right
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NeonPink.copy(alpha = 0.38f),
                            NeonPurple.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = Offset(width * 0.82f, height * 0.5f),
                        radius = height * 0.9f
                    ),
                    center = Offset(width * 0.82f, height * 0.5f),
                    radius = height * 0.9f
                )

                // Concentric vinyl/sound rings
                drawCircle(
                    color = NeonPinkLight.copy(alpha = 0.18f),
                    radius = height * 0.52f,
                    center = Offset(width * 0.84f, height * 0.5f),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = NeonPurple.copy(alpha = 0.25f),
                    radius = height * 0.36f,
                    center = Offset(width * 0.84f, height * 0.5f),
                    style = Stroke(width = 3.dp.toPx())
                )

                // Stylised equalizer bars on right side of banner
                val barHeights = floatArrayOf(0.28f, 0.52f, 0.78f, 0.45f, 0.88f, 0.62f, 0.35f)
                val barWidth = 5.dp.toPx()
                val gap = 5.dp.toPx()
                val startX = width * 0.74f
                for (i in barHeights.indices) {
                    val barH = height * 0.55f * barHeights[i]
                    val x = startX + i * (barWidth + gap)
                    val y = (height - barH) / 2f
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(NeonPinkLight, NeonPink, NeonPurple)
                        ),
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barH),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = NeonPink.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, NeonPinkLight.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "HI-FI OFFLINE AUDIO",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        letterSpacing = 1.1.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Column {
                    Text(
                        text = "Feel Every Beat",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$songCount local tracks • $albumCount albums ready offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSongCard(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .width(136.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isPlaying) NeonPink else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                ElectricViolet.copy(alpha = 0.5f),
                                NeonPink.copy(alpha = 0.35f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    val imageRequest = remember(song.albumArtUri) {
                        val key = "recent_${song.albumArtUri}"
                        ImageRequest.Builder(context)
                            .data(song.albumArtUri)
                            .memoryCacheKey(key)
                            .diskCacheKey(key)
                            .crossfade(false)
                            .size(256, 256)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Play / Equalizer badge in bottom-right corner
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(NeonPink, NeonPurple)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) NeonPink else MaterialTheme.colorScheme.onSurface,
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
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .width(146.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(146.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                DeepViolet,
                                NeonPink.copy(alpha = 0.4f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (album.artworkUri != null) {
                    val imageRequest = remember(album.artworkUri) {
                        val key = "album_${album.artworkUri}"
                        ImageRequest.Builder(context)
                            .data(album.artworkUri)
                            .memoryCacheKey(key)
                            .diskCacheKey(key)
                            .crossfade(false)
                            .size(300, 300)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = album.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = NeonPinkLight,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
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
                    color = NeonPurple
                )
            }
        }
    }
}

