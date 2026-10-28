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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonPinkLight
import com.example.ui.theme.NeonPurple

private val HomeBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF17092E),
        DarkBackground,
        Color(0xFF06030C)
    )
)

private val ActionGradientBrush = Brush.horizontalGradient(
    colors = listOf(NeonPink, NeonPurple)
)

private val ActionBorderBrush = Brush.horizontalGradient(
    colors = listOf(NeonPurple, NeonPink)
)

private val HeroBorderBrush = Brush.linearGradient(
    colors = listOf(NeonPink.copy(alpha = 0.65f), NeonPurple.copy(alpha = 0.45f))
)

private val HeroBackgroundBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF2B0B3F),
        DeepViolet,
        Color(0xFF5B1049)
    )
)

private val HeroBarBrush = Brush.verticalGradient(
    colors = listOf(NeonPinkLight, NeonPink, NeonPurple)
)

private val HomeAlbumBackdropBrush = Brush.linearGradient(
    colors = listOf(
        DeepViolet,
        NeonPink.copy(alpha = 0.4f)
    )
)

private val ActionButtonShape = RoundedCornerShape(18.dp)
private val HomeAlbumCardShape = RoundedCornerShape(20.dp)
private val QuickAccessPillShape = RoundedCornerShape(16.dp)

@Composable
fun HomeScreen(
    songs: List<Song>,
    albums: List<Album>,
    favoriteSongs: List<Song>,
    favoriteIds: Set<Long> = emptySet(),
    precomputedAlbumsPreview: List<Album> = emptyList(),
    precomputedFavoritesPreview: List<Song> = emptyList(),
    precomputedTracksPreview: List<Song> = emptyList(),
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
    onNavigateToPlaylists: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToArtists: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    playlistCount: Int = 0,
    artistCount: Int = 0,
    modifier: Modifier = Modifier
) {
    var showSearchBar by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val albumsPreview = remember(precomputedAlbumsPreview, albums) {
        if (precomputedAlbumsPreview.isNotEmpty()) {
            precomputedAlbumsPreview
        } else {
            albums.asSequence().distinctBy { it.stableKey }.take(10).toList()
        }
    }
    val favoritesPreview = remember(precomputedFavoritesPreview, favoriteSongs) {
        if (precomputedFavoritesPreview.isNotEmpty()) {
            precomputedFavoritesPreview
        } else {
            favoriteSongs.asSequence().distinctBy { it.id }.take(5).toList()
        }
    }
    val filteredAllTracks = remember(precomputedTracksPreview, songs, searchQuery) {
        if (searchQuery.isBlank()) {
            if (precomputedTracksPreview.isNotEmpty()) {
                precomputedTracksPreview
            } else {
                songs.asSequence().distinctBy { it.id }.take(15).toList()
            }
        } else {
            val q = searchQuery.trim()
            songs.asSequence()
                .filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.artist.contains(q, ignoreCase = true) ||
                        it.album.contains(q, ignoreCase = true)
                }
                .distinctBy { it.id }
                .toList()
        }
    }
    val favoriteIdSet = remember(favoriteIds, favoriteSongs) {
        if (favoriteIds.isNotEmpty() || favoriteSongs.isEmpty()) {
            favoriteIds
        } else {
            favoriteSongs.mapTo(HashSet(favoriteSongs.size)) { it.id }
        }
    }
    val onFavoriteSongClick = remember(onSongClick, favoriteSongs) {
        { song: Song -> onSongClick(song, favoriteSongs) }
    }
    val onAllTrackSongClick = remember(onSongClick, songs) {
        { song: Song -> onSongClick(song, songs) }
    }

    if (songs.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(DarkBackgroundSecondary, DarkBackground)
                    )
                )
                .testTag("home_screen")
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
    } else {
        val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
        com.example.ui.components.LazyListSongArtworkPreloader(
            listState = homeListState,
            songs = filteredAllTracks,
            headerItemCount = 3
        )
        val flingBehavior = com.example.util.rememberPerformanceFlingBehavior()
        LazyColumn(
            state = homeListState,
            flingBehavior = flingBehavior,
            modifier = modifier
                .fillMaxSize()
                .background(HomeBackgroundBrush)
                .testTag("home_screen"),
            contentPadding = PaddingValues(bottom = 104.dp)
        ) {
        // 1. App Title Header + Hero Banner + Shuffle All / Play All + Quick Access Cards
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

                // 2. Hero Banner showing total local tracks & albums
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
                            .height(50.dp)
                            .clip(ActionButtonShape)
                            .background(ActionGradientBrush)
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
                            .height(50.dp)
                            .clip(ActionButtonShape)
                            .clickable(onClick = onPlayAll)
                            .testTag("home_play_all_btn"),
                        shape = ActionButtonShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        border = BorderStroke(1.2.dp, ActionBorderBrush)
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

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Access Horizontal Cards for Playlists, Favorites, and Artists
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HomeQuickAccessCard(
                        icon = Icons.Default.Favorite,
                        title = "Favorites",
                        subtitle = "${favoriteSongs.size} tracks",
                        accentColor = NeonPink,
                        onClick = onNavigateToFavorites,
                        testTag = "home_quick_favorites_btn",
                        modifier = Modifier.weight(1f)
                    )
                    HomeQuickAccessCard(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = "Playlists",
                        subtitle = "$playlistCount lists",
                        accentColor = NeonPurple,
                        onClick = onNavigateToPlaylists,
                        testTag = "home_quick_playlists_btn",
                        modifier = Modifier.weight(1f)
                    )
                    HomeQuickAccessCard(
                        icon = Icons.Default.Person,
                        title = "Artists",
                        subtitle = "$artistCount artists",
                        accentColor = NeonPinkLight,
                        onClick = onNavigateToArtists,
                        testTag = "home_quick_artists_btn",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4. Albums Carousel Section
        if (albumsPreview.isNotEmpty() && searchQuery.isBlank()) {
            item(key = "home_albums_section", contentType = "carousel") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
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
                            key = { it.stableKey },
                            contentType = { "album_card" }
                        ) { album ->
                            HomeAlbumCard(
                                album = album,
                                onAlbumClick = onAlbumClick
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
                        .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Text(
                        text = "See All",
                        style = MaterialTheme.typography.labelLarge,
                        color = NeonPink,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onNavigateToFavorites)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            items(
                items = favoritesPreview,
                key = { it.homeFavKey },
                contentType = { "song_row" }
            ) { song ->
                SongItemRow(
                    song = song,
                    isPlaying = isPlaying && currentSongId == song.id,
                    isFavorite = true,
                    onSongClick = onFavoriteSongClick,
                    onToggleFavorite = onToggleFavorite,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist
                )
            }
        }

        // 5. All Tracks Section
        item(key = "home_all_tracks_header", contentType = "section_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp),
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
                        text = if (searchQuery.isBlank()) "All Tracks (${songs.size})" else "Search Results (${filteredAllTracks.size})",
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
            key = { it.homeAllKey },
            contentType = { "song_row" }
        ) { song ->
            SongItemRow(
                song = song,
                isPlaying = isPlaying && currentSongId == song.id,
                isFavorite = song.isFavorite || favoriteIdSet.contains(song.id),
                onSongClick = onAllTrackSongClick,
                onToggleFavorite = onToggleFavorite,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onAddToPlaylist = onAddToPlaylist
            )
        }
        }
    }
}

@Composable
private fun HomeQuickAccessCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(QuickAccessPillShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = QuickAccessPillShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                    .background(ActionGradientBrush),
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

private val HeroBarFractions = floatArrayOf(0.28f, 0.52f, 0.78f, 0.45f, 0.88f, 0.62f, 0.35f)

@Composable
private fun PureTuneHeroBanner(
    songCount: Int,
    albumCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(144.dp)
            .testTag("home_hero_banner"),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, HeroBorderBrush),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HeroBackgroundBrush)
                .drawWithCache {
                    val width = size.width
                    val height = size.height
                    val glowCenter = Offset(width * 0.82f, height * 0.5f)
                    val glowRadius = (height * 0.9f).coerceAtLeast(1f)
                    val glowBrush = Brush.radialGradient(
                        colors = listOf(
                            NeonPink.copy(alpha = 0.36f),
                            NeonPurple.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = glowCenter,
                        radius = glowRadius
                    )
                    val ringCenter = Offset(width * 0.84f, height * 0.5f)
                    val outerRingStroke = Stroke(width = 2.dp.toPx())
                    val innerRingStroke = Stroke(width = 3.dp.toPx())
                    val barWidth = 5.dp.toPx()
                    val gap = 5.dp.toPx()
                    val startX = width * 0.74f
                    val barCorner = CornerRadius(barWidth / 2f, barWidth / 2f)

                    onDrawBehind {
                        if (width <= 0f || height <= 0f) return@onDrawBehind
                        drawCircle(
                            brush = glowBrush,
                            center = glowCenter,
                            radius = glowRadius
                        )
                        drawCircle(
                            color = NeonPinkLight.copy(alpha = 0.18f),
                            radius = height * 0.5f,
                            center = ringCenter,
                            style = outerRingStroke
                        )
                        drawCircle(
                            color = NeonPurple.copy(alpha = 0.24f),
                            radius = height * 0.34f,
                            center = ringCenter,
                            style = innerRingStroke
                        )
                        for (i in HeroBarFractions.indices) {
                            val barH = height * 0.52f * HeroBarFractions[i]
                            val x = startX + i * (barWidth + gap)
                            val y = (height - barH) / 2f
                            drawRoundRect(
                                brush = HeroBarBrush,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barH),
                                cornerRadius = barCorner
                            )
                        }
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
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
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeAlbumCard(
    album: Album,
    onAlbumClick: (Album) -> Unit
) {
    val handleClick = remember(album, onAlbumClick) { { onAlbumClick(album) } }
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val borderColor = colorScheme.outline.copy(alpha = 0.3f)

    Column(
        modifier = Modifier
            .width(142.dp)
            .background(color = containerColor, shape = HomeAlbumCardShape)
            .border(width = 1.dp, color = borderColor, shape = HomeAlbumCardShape)
            .clip(HomeAlbumCardShape)
            .clickable(onClick = handleClick)
    ) {
        com.example.ui.components.FastArtworkImage(
            artworkUri = album.artworkUriString ?: album.fallbackAudioUriString,
            cacheKeyPrefix = "album",
            sizePx = 256,
            contentDescription = album.title,
            fallbackUri = album.fallbackAudioUriString,
            fallbackPath = album.fallbackAudioPath,
            precomputedIdentity = album.artworkSourceIdentity,
            precomputedCacheKey = album.albumCacheKey,
            precomputedPrimaryCacheKey = album.primaryAlbumCacheKey,
            modifier = Modifier
                .size(142.dp)
                .background(HomeAlbumBackdropBrush)
        ) {
            Icon(
                imageVector = Icons.Default.Album,
                contentDescription = null,
                tint = NeonPinkLight,
                modifier = Modifier.size(44.dp)
            )
        }
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.songCountLabel,
                style = MaterialTheme.typography.bodySmall,
                color = NeonPurple
            )
        }
    }
}


