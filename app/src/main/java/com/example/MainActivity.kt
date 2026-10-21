package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ui.components.MiniPlayer
import com.example.ui.screens.AddToPlaylistDialog
import com.example.ui.screens.AlbumsScreen
import com.example.ui.screens.ArtistsScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.FoldersScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NowPlayingScreen
import com.example.ui.screens.PermissionView
import com.example.ui.screens.PlaylistsScreen
import com.example.ui.screens.ScannedAudioListScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.PulseMusicTheme
import com.example.viewmodel.MusicViewModel
import com.example.viewmodel.NavigationTab

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureHighRefreshRateDisplay()

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val libraryStateRef = viewModel.libraryState.collectAsState()
            val userThemePreference by remember {
                androidx.compose.runtime.derivedStateOf { libraryStateRef.value.userThemePreference }
            }

            val isDarkTheme = when (userThemePreference) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            PulseMusicTheme(darkTheme = isDarkTheme) {
                PulseMusicAppRoot(
                    viewModel = viewModel,
                    onRequestPermission = { checkAndRequestPermissions() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureHighRefreshRateDisplay()
        }
    }

    /**
     * Negotiates the highest available display refresh rate (60Hz / 90Hz / 120Hz+)
     * matching the active physical resolution so animations and scrolling run at peak smoothness.
     */
    internal fun configureHighRefreshRateDisplay() {
        try {
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val supportedModes = currentDisplay.supportedModes
            if (supportedModes.isNullOrEmpty()) return

            val currentMode = currentDisplay.mode
            val bestMode = supportedModes
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: supportedModes.maxByOrNull { it.refreshRate }
                ?: return

            val layoutParams = window.attributes
            if (layoutParams.preferredDisplayModeId != bestMode.modeId ||
                layoutParams.preferredRefreshRate != bestMode.refreshRate
            ) {
                layoutParams.preferredDisplayModeId = bestMode.modeId
                layoutParams.preferredRefreshRate = bestMode.refreshRate
                window.attributes = layoutParams
            }
        } catch (_: Exception) {
            // Safely ignore on environments/tests without WindowManager display modes
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incomingIntent: android.content.Intent?) {
        if (incomingIntent?.action == android.content.Intent.ACTION_VIEW) {
            val audioUri = incomingIntent.data ?: return
            viewModel.playExternalAudioUri(audioUri)
        }
    }

    override fun onResume() {
        super.onResume()
        configureHighRefreshRateDisplay()
        // Re-check permissions on resume (e.g., if user returned from Settings)
        val hasPermission = checkAudioPermission()
        viewModel.onPermissionResult(hasPermission)
    }

    private fun checkAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.onPermissionResult(true)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val audioGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsMap[Manifest.permission.READ_MEDIA_AUDIO] == true
        } else {
            permissionsMap[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        viewModel.onPermissionResult(audioGranted)
    }
}

private val BOTTOM_NAV_ITEMS = listOf(
    NavItem(NavigationTab.HOME, "Home", Icons.Default.Home),
    NavItem(NavigationTab.SONGS, "Songs", Icons.Default.MusicNote),
    NavItem(NavigationTab.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    NavItem(NavigationTab.ALBUMS, "Albums", Icons.Default.Album),
    NavItem(NavigationTab.ARTISTS, "Artists", Icons.Default.Person),
    NavItem(NavigationTab.FOLDERS, "Folders", Icons.Default.Folder),
    NavItem(NavigationTab.FAVORITES, "Favorites", Icons.Default.Favorite),
    NavItem(NavigationTab.SETTINGS, "Settings", Icons.Default.Settings)
)

@Composable
fun PulseMusicAppRoot(
    viewModel: MusicViewModel,
    onRequestPermission: () -> Unit
) {
    val libraryState by viewModel.libraryState.collectAsState()
    val playerStateRef = viewModel.playerUiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val currentSongId by remember { androidx.compose.runtime.derivedStateOf { playerStateRef.value.currentSong?.id } }
    val isPlaying by remember { androidx.compose.runtime.derivedStateOf { playerStateRef.value.isPlaying } }
    val hasCurrentSong by remember { androidx.compose.runtime.derivedStateOf { playerStateRef.value.currentSong != null } }
    val errorMessage by remember { androidx.compose.runtime.derivedStateOf { playerStateRef.value.errorMessage } }

    // Display error message if playback error occurs
    LaunchedEffect(errorMessage) {
        errorMessage?.let { errorMsg ->
            snackbarHostState.showSnackbar(errorMsg)
            viewModel.playerController.clearErrorMessage()
        }
    }

    if (!libraryState.hasPermission && !hasCurrentSong) {
        PermissionView(
            onRequestPermission = onRequestPermission
        )
        return
    }

    // Handle back button when sub-screens or expanded Now Playing are active
    BackHandler(enabled = (libraryState.selectedTab != NavigationTab.HOME || libraryState.selectedPlaylist != null || libraryState.selectedAlbum != null || libraryState.selectedArtist != null || libraryState.selectedFolder != null) && !libraryState.isNowPlayingExpanded) {
        if (libraryState.selectedPlaylist != null) {
            viewModel.selectPlaylist(null)
        } else if (libraryState.selectedAlbum != null) {
            viewModel.selectAlbum(null)
        } else if (libraryState.selectedArtist != null) {
            viewModel.selectArtist(null)
        } else if (libraryState.selectedFolder != null) {
            viewModel.selectFolder(null)
        } else {
            viewModel.setNavigationTab(NavigationTab.HOME)
        }
    }

    val favoriteSongs = remember(libraryState.songs, libraryState.favoriteIds) {
        libraryState.songs.filter { libraryState.favoriteIds.contains(it.id) }
    }

    val recentSongs = remember(libraryState.songs, libraryState.historyItems) {
        val songMap = libraryState.songs.associateBy { it.id }
        libraryState.historyItems.mapNotNull { songMap[it.songId] }.distinctBy { it.id }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // Isolated MiniPlayer section so 500ms progress ticks only recompose MiniPlayer
                    MiniPlayerSection(
                        viewModel = viewModel,
                        favoriteIds = libraryState.favoriteIds
                    )

                    // Navigation Bar
                    NavigationBar(
                        modifier = Modifier.testTag("bottom_nav_bar"),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        BOTTOM_NAV_ITEMS.forEach { item ->
                            val isSelected = libraryState.selectedTab == item.tab
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { viewModel.setNavigationTab(item.tab) },
                                icon = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = item.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier.testTag("nav_item_${item.label.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                AnimatedContent(
                    targetState = libraryState.selectedTab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))
                    },
                    label = "tab_transition"
                ) { tab ->
                    when (tab) {
                        NavigationTab.HOME -> {
                            HomeScreen(
                                songs = libraryState.songs,
                                albums = libraryState.albums,
                                favoriteSongs = favoriteSongs,
                                recentSongs = recentSongs,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onSongClick = { song, queue -> viewModel.playSongFromList(song, queue) },
                                onShuffleAll = { viewModel.playAll(libraryState.songs, shuffle = true) },
                                onPlayAll = { viewModel.playAll(libraryState.songs, shuffle = false) },
                                onAlbumClick = { album ->
                                    viewModel.setNavigationTab(NavigationTab.ALBUMS)
                                    viewModel.selectAlbum(album)
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onPlayNext = { viewModel.playerController.playNext(it) },
                                onAddToQueue = { viewModel.playerController.addToQueue(it) },
                                onAddToPlaylist = { viewModel.setSongForAddToPlaylist(it) },
                                onNavigateToSongs = { viewModel.setNavigationTab(NavigationTab.SONGS) },
                                onNavigateToHistory = { viewModel.setNavigationTab(NavigationTab.HISTORY) }
                            )
                        }

                        NavigationTab.SONGS -> {
                            ScannedAudioListScreen(
                                songs = libraryState.songs,
                                isLoading = libraryState.isLoading,
                                favoriteIds = libraryState.favoriteIds,
                                currentPlayingSongId = currentSongId,
                                isPlaying = isPlaying,
                                onSongClick = { song, queue -> viewModel.playSongFromList(song, queue) },
                                onPlayAll = { viewModel.playAll(it, shuffle = false) },
                                onShuffleAll = { viewModel.playAll(it, shuffle = true) },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onPlayNext = { viewModel.playerController.playNext(it) },
                                onAddToQueue = { viewModel.playerController.addToQueue(it) },
                                onAddToPlaylist = { viewModel.setSongForAddToPlaylist(it) },
                                onRescanStorage = { viewModel.refreshLibrary() }
                            )
                        }

                        NavigationTab.PLAYLISTS -> {
                            PlaylistsScreen(
                                playlists = libraryState.playlists,
                                selectedPlaylist = libraryState.selectedPlaylist,
                                playlistSongs = libraryState.selectedPlaylistSongs,
                                allSongs = libraryState.songs,
                                favoriteIds = libraryState.favoriteIds,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onPlaylistSelect = { viewModel.selectPlaylist(it) },
                                onCreatePlaylist = { viewModel.createPlaylist(it) },
                                onDeletePlaylist = { viewModel.deletePlaylist(it) },
                                onAddSongsToPlaylist = { playlistId, songIds -> viewModel.addSongsToPlaylist(playlistId, songIds) },
                                onRemoveSongFromPlaylist = { playlistId, songId -> viewModel.removeSongFromPlaylist(playlistId, songId) },
                                onSongClick = { song, queue -> viewModel.playSongFromList(song, queue) },
                                onPlayAll = { viewModel.playAll(it, shuffle = false) },
                                onShuffleAll = { viewModel.playAll(it, shuffle = true) },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onPlayNext = { viewModel.playerController.playNext(it) },
                                onAddToQueue = { viewModel.playerController.addToQueue(it) }
                            )
                        }

                        NavigationTab.ALBUMS -> {
                            AlbumsScreen(
                                albums = libraryState.albums,
                                allSongs = libraryState.songs,
                                selectedAlbum = libraryState.selectedAlbum,
                                favoriteIds = libraryState.favoriteIds,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onAlbumSelect = { viewModel.selectAlbum(it) },
                                onSongClick = { song, queue -> viewModel.playSongFromList(song, queue) },
                                onPlayAll = { viewModel.playAll(it, shuffle = false) },
                                onShuffleAll = { viewModel.playAll(it, shuffle = true) },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onPlayNext = { viewModel.playerController.playNext(it) },
                                onAddToQueue = { viewModel.playerController.addToQueue(it) },
                                onAddToPlaylist = { viewModel.setSongForAddToPlaylist(it) }
                            )
                        }

                        NavigationTab.ARTISTS -> {
                            ArtistsScreen(
                                artists = libraryState.artists,
                                allSongs = libraryState.songs,
                                selectedArtist = libraryState.selectedArtist,
                                favoriteIds = libraryState.favoriteIds,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onArtistSelect = { viewModel.selectArtist(it) },
                                onSongClick = { song, queue -> viewModel.playSongFromList(song, queue) },
                                onPlayAll = { viewModel.playAll(it, shuffle = false) },
                                onShuffleAll = { viewModel.playAll(it, shuffle = true) },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onPlayNext = { viewModel.playerController.playNext(it) },
                                onAddToQueue = { viewModel.playerController.addToQueue(it) },
                                onAddToPlaylist = { viewModel.setSongForAddToPlaylist(it) }
                            )
                        }

                        NavigationTab.FOLDERS -> {
                            FoldersScreen(
                                folders = libraryState.folders,
                                allSongs = libraryState.songs,
                                selectedFolder = libraryState.selectedFolder,
                                favoriteIds = libraryState.favoriteIds,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onFolderSelect = { viewModel.selectFolder(it) },
                                onSongClick = { song, queue -> viewModel.playSongFromList(song, queue) },
                                onPlayAll = { viewModel.playAll(it, shuffle = false) },
                                onShuffleAll = { viewModel.playAll(it, shuffle = true) },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onPlayNext = { viewModel.playerController.playNext(it) },
                                onAddToQueue = { viewModel.playerController.addToQueue(it) },
                                onAddToPlaylist = { viewModel.setSongForAddToPlaylist(it) }
                            )
                        }

                        NavigationTab.FAVORITES -> {
                            FavoritesScreen(
                                favoriteSongs = favoriteSongs,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onSongClick = { song, queue -> viewModel.playSongFromList(song, queue) },
                                onPlayAll = { viewModel.playAll(it, shuffle = false) },
                                onShuffleAll = { viewModel.playAll(it, shuffle = true) },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onClearFavorites = { viewModel.clearFavorites() },
                                onPlayNext = { viewModel.playerController.playNext(it) },
                                onAddToQueue = { viewModel.playerController.addToQueue(it) },
                                onAddToPlaylist = { viewModel.setSongForAddToPlaylist(it) }
                            )
                        }

                        NavigationTab.HISTORY -> {
                            HistoryScreen(
                                recentSongs = recentSongs,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                favoriteIds = libraryState.favoriteIds,
                                onSongClick = { song, queue -> viewModel.playSongFromList(song, queue) },
                                onClearHistory = { viewModel.clearHistory() },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onPlayNext = { viewModel.playerController.playNext(it) },
                                onAddToQueue = { viewModel.playerController.addToQueue(it) },
                                onAddToPlaylist = { viewModel.setSongForAddToPlaylist(it) }
                            )
                        }

                        NavigationTab.SETTINGS -> {
                            SettingsScreen(
                                currentTheme = libraryState.userThemePreference,
                                onThemeChange = { viewModel.setThemeMode(it) },
                                onClearHistory = { viewModel.clearHistory() },
                                onClearFavorites = { viewModel.clearFavorites() },
                                resumeLastPlayback = libraryState.resumeLastPlayback,
                                onResumeLastChange = { viewModel.setResumeLastPlayback(it) },
                                autoPauseOnHeadsetUnplug = libraryState.pauseOnHeadsetUnplug,
                                onPauseOnHeadsetUnplugChange = { viewModel.setPauseOnHeadsetUnplug(it) }
                            )
                        }
                    }
                }
            }
        }

        // Full Screen Now Playing overlay with slide-up transition
        NowPlayingOverlaySection(
            viewModel = viewModel,
            isExpanded = libraryState.isNowPlayingExpanded && hasCurrentSong,
            favoriteIds = libraryState.favoriteIds
        )

        // Add to Playlist Dialog
        libraryState.songForAddToPlaylist?.let { songToAdd ->
            AddToPlaylistDialog(
                song = songToAdd,
                playlists = libraryState.playlists,
                onDismiss = { viewModel.setSongForAddToPlaylist(null) },
                onSelectPlaylist = { playlist ->
                    viewModel.addSongToPlaylist(playlist.id, songToAdd)
                },
                onCreatePlaylistWithSong = { name ->
                    viewModel.createPlaylistAndAddSong(name, songToAdd)
                }
            )
        }
    }
}

@Composable
private fun MiniPlayerSection(
    viewModel: MusicViewModel,
    favoriteIds: Set<Long>
) {
    val playerState by viewModel.playerUiState.collectAsState()
    val currentSongId = playerState.currentSong?.id
    val isCurrentFavorite = currentSongId != null && favoriteIds.contains(currentSongId)

    val onTogglePlayPause = remember(viewModel) { { viewModel.playerController.togglePlayPause() } }
    val onSkipNext = remember(viewModel) { { viewModel.playerController.skipToNext() } }
    val onSkipPrevious = remember(viewModel) { { viewModel.playerController.skipToPrevious() } }
    val onToggleShuffle = remember(viewModel) { { viewModel.playerController.toggleShuffle() } }
    val onCycleRepeatMode = remember(viewModel) { { viewModel.playerController.cycleRepeatMode() } }
    val onToggleFavorite = remember(viewModel, currentSongId) {
        { currentSongId?.let { viewModel.toggleFavorite(it) } ?: Unit }
    }
    val onExpandNowPlaying = remember(viewModel) { { viewModel.setNowPlayingExpanded(true) } }

    MiniPlayer(
        playerState = playerState,
        isFavorite = isCurrentFavorite,
        onTogglePlayPause = onTogglePlayPause,
        onSkipNext = onSkipNext,
        onSkipPrevious = onSkipPrevious,
        onToggleShuffle = onToggleShuffle,
        onCycleRepeatMode = onCycleRepeatMode,
        onToggleFavorite = onToggleFavorite,
        onExpandNowPlaying = onExpandNowPlaying
    )
}

@Composable
private fun NowPlayingOverlaySection(
    viewModel: MusicViewModel,
    isExpanded: Boolean,
    favoriteIds: Set<Long>
) {
    val onCollapse = remember(viewModel) { { viewModel.setNowPlayingExpanded(false) } }
    val onTogglePlayPause = remember(viewModel) { { viewModel.playerController.togglePlayPause() } }
    val onSkipNext = remember(viewModel) { { viewModel.playerController.skipToNext() } }
    val onSkipPrevious = remember(viewModel) { { viewModel.playerController.skipToPrevious() } }
    val onSeekTo = remember(viewModel) { { pos: Long -> viewModel.playerController.seekTo(pos) } }
    val onSeekRelative = remember(viewModel) { { delta: Long -> viewModel.playerController.seekRelative(delta) } }
    val onToggleShuffle = remember(viewModel) { { viewModel.playerController.toggleShuffle() } }
    val onCycleRepeatMode = remember(viewModel) { { viewModel.playerController.cycleRepeatMode() } }
    val onRemoveFromQueue = remember(viewModel) { { idx: Int -> viewModel.playerController.removeFromQueue(idx) } }
    val onJumpToQueueItem = remember(viewModel) { { idx: Int -> viewModel.playerController.jumpToQueueItem(idx) } }

    AnimatedVisibility(
        visible = isExpanded,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            initialOffsetY = { it }
        ),
        exit = slideOutVertically(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            targetOffsetY = { it }
        )
    ) {
        val playerState by viewModel.playerUiState.collectAsState()
        val currentSongId = playerState.currentSong?.id
        val isCurrentFav = currentSongId != null && favoriteIds.contains(currentSongId)
        val onToggleFavorite = remember(viewModel, currentSongId) {
            { currentSongId?.let { viewModel.toggleFavorite(it) } ?: Unit }
        }
        NowPlayingScreen(
            playerState = playerState,
            isFavorite = isCurrentFav,
            onCollapse = onCollapse,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onSeekTo = onSeekTo,
            onSeekRelative = onSeekRelative,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeatMode = onCycleRepeatMode,
            onToggleFavorite = onToggleFavorite,
            onRemoveFromQueue = onRemoveFromQueue,
            onJumpToQueueItem = onJumpToQueueItem
        )
    }
}

private data class NavItem(
    val tab: NavigationTab,
    val label: String,
    val icon: ImageVector
)
