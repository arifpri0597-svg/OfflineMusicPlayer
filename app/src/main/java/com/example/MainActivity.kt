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

    @Volatile
    private var isPermissionRequestInFlight: Boolean = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap: Map<String, @JvmSuppressWildcards Boolean>? ->
        isPermissionRequestInFlight = false
        if (isFinishing || isDestroyed) return@registerForActivityResult
        val requiredPerm = requiredAudioPermission()
        val grantedFromCallback = permissionsMap?.get(requiredPerm) == true
        val effectiveAudioGranted = grantedFromCallback || checkAudioPermission()
        viewModel.onPermissionResult(effectiveAudioGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighRefreshRateDisplayMode()

        val initialGranted = checkAudioPermission()
        viewModel.onPermissionResult(initialGranted)

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
                else -> true
            }

            PulseMusicTheme(darkTheme = isDarkTheme) {
                PulseMusicAppRoot(
                    viewModel = viewModel,
                    onRequestPermission = { checkAndRequestPermissions() }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incomingIntent: android.content.Intent?) {
        val action = incomingIntent?.action ?: return
        if (action != android.content.Intent.ACTION_VIEW &&
            action != android.content.Intent.ACTION_OPEN_DOCUMENT
        ) {
            return
        }
        // Do not re-trigger an old external File Manager intent when reopening from Recent Apps
        if ((incomingIntent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return
        }

        val audioUri = incomingIntent.data ?: return
        val mimeType = incomingIntent.type

        if (audioUri.scheme?.lowercase() == "content") {
            try {
                val takeFlags = incomingIntent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                val flagsToTake = if (takeFlags != 0) {
                    takeFlags
                } else {
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                contentResolver.takePersistableUriPermission(audioUri, flagsToTake)
            } catch (_: Exception) {
                // Provider does not support persistable permissions; safe to ignore
            }
        }

        // Consume the external intent action/data so activity recreation does not re-fire it
        incomingIntent.action = android.content.Intent.ACTION_MAIN
        incomingIntent.data = null

        viewModel.playExternalAudioUri(audioUri, mimeType)
    }

    override fun onResume() {
        super.onResume()
        requestHighRefreshRateDisplayMode()
        if (isFinishing || isDestroyed || isPermissionRequestInFlight) return
        // Re-check permissions on resume (e.g., if user returned from Settings or permission dialog)
        val hasPermission = checkAudioPermission()
        viewModel.onPermissionResult(hasPermission)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            requestHighRefreshRateDisplayMode()
        }
    }

    /**
     * Explicitly requests the highest supported display refresh rate (e.g. 120Hz / 90Hz)
     * matching the active physical resolution so Android OEM window managers do not cap
     * scrolling at 60Hz.
     */
    private fun requestHighRefreshRateDisplayMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val currentMode = activeDisplay.mode ?: return
            val supportedModes = activeDisplay.supportedModes ?: return
            val bestMode = supportedModes
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: supportedModes.maxByOrNull { it.refreshRate }
                ?: return

            val params = window.attributes
            if (params.preferredDisplayModeId != bestMode.modeId ||
                params.preferredRefreshRate != bestMode.refreshRate
            ) {
                params.preferredDisplayModeId = bestMode.modeId
                params.preferredRefreshRate = bestMode.refreshRate
                window.attributes = params
            }
        } catch (_: Throwable) {
            // Safe no-op on emulators or devices that restrict display mode changes
        }
    }

    override fun onPause() {
        super.onPause()
        if (viewModel.libraryState.value.hasPermission || viewModel.playerUiState.value.currentSong != null) {
            viewModel.savePlaybackStateNow()
        }
    }

    override fun onStop() {
        super.onStop()
        if (viewModel.libraryState.value.hasPermission || viewModel.playerUiState.value.currentSong != null) {
            viewModel.savePlaybackStateNow()
        }
    }

    private fun requiredAudioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun checkAudioPermission(): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                applicationContext,
                requiredAudioPermission()
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun checkAndRequestPermissions() {
        if (isFinishing || isDestroyed) return
        if (checkAudioPermission()) {
            isPermissionRequestInFlight = false
            viewModel.onPermissionResult(true)
            return
        }
        if (isPermissionRequestInFlight) return

        val requiredPerm = requiredAudioPermission()
        isPermissionRequestInFlight = true
        try {
            requestPermissionLauncher.launch(arrayOf(requiredPerm))
        } catch (_: Throwable) {
            isPermissionRequestInFlight = false
            viewModel.onPermissionResult(checkAudioPermission())
        }
    }
}

private val BOTTOM_NAV_ITEMS = listOf(
    NavItem(NavigationTab.HOME, "Home", Icons.Default.Home),
    NavItem(NavigationTab.SONGS, "Songs", Icons.Default.MusicNote),
    NavItem(NavigationTab.ALBUMS, "Albums", Icons.Default.Album),
    NavItem(NavigationTab.FOLDERS, "Folders", Icons.Default.Folder),
    NavItem(NavigationTab.SETTINGS, "Settings", Icons.Default.Settings)
)

private val BottomDividerColor = com.example.ui.theme.NeonPurple.copy(alpha = 0.2f)
private val BottomNavContainerColor = androidx.compose.ui.graphics.Color(0xFF0E071B).copy(alpha = 0.96f)
private val BottomNavIndicatorColor = com.example.ui.theme.NeonPink.copy(alpha = 0.18f)
private val BottomNavUnselectedColor = androidx.compose.ui.graphics.Color(0xFFB3A4CC)

@Composable
fun PulseMusicAppRoot(
    viewModel: MusicViewModel,
    onRequestPermission: () -> Unit
) {
    val libraryState by viewModel.libraryState.collectAsState()
    val initialSummary = remember(viewModel) {
        val s = viewModel.playerUiState.value
        com.example.viewmodel.RootPlaybackSummary(
            currentSongId = s.currentSong?.id,
            isPlaying = s.isPlaying,
            hasCurrentSong = s.currentSong != null,
            errorMessage = s.errorMessage
        )
    }
    val rootPlayback by viewModel.rootPlaybackSummary.collectAsState(initial = initialSummary)
    val snackbarHostState = remember { SnackbarHostState() }

    val currentSongId = rootPlayback.currentSongId
    val isPlaying = rootPlayback.isPlaying
    val hasCurrentSong = rootPlayback.hasCurrentSong
    val errorMessage = rootPlayback.errorMessage

    // Display error message if playback error occurs
    LaunchedEffect(errorMessage) {
        errorMessage?.let { errorMsg ->
            snackbarHostState.showSnackbar(errorMsg)
            viewModel.playerController.clearErrorMessage()
        }
    }

    // Handle back button when sub-screens or expanded Now Playing are active
    BackHandler(
        enabled = (libraryState.hasPermission || hasCurrentSong) &&
            (libraryState.selectedTab != NavigationTab.HOME ||
                libraryState.selectedPlaylist != null ||
                libraryState.selectedAlbum != null ||
                libraryState.selectedArtist != null ||
                libraryState.selectedFolder != null) &&
            !libraryState.isNowPlayingExpanded
    ) {
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

    val favoriteSongs = libraryState.favoriteSongs

    // Stable remembered callbacks to prevent unnecessary screen & row recompositions
    val onSongClick = remember(viewModel) {
        { song: com.example.data.model.Song, queue: List<com.example.data.model.Song> ->
            viewModel.playSongFromList(song, queue)
        }
    }
    val onPlayAllList = remember(viewModel) {
        { list: List<com.example.data.model.Song> -> viewModel.playAll(list, shuffle = false) }
    }
    val onShuffleAllList = remember(viewModel) {
        { list: List<com.example.data.model.Song> -> viewModel.playAll(list, shuffle = true) }
    }
    val onPlayAllLibrary = remember(viewModel) {
        { viewModel.playAll(viewModel.libraryState.value.songs, shuffle = false) }
    }
    val onShuffleAllLibrary = remember(viewModel) {
        { viewModel.playAll(viewModel.libraryState.value.songs, shuffle = true) }
    }
    val onToggleFavorite = remember(viewModel) {
        { songId: Long -> viewModel.toggleFavorite(songId) }
    }
    val onPlayNext = remember(viewModel) {
        { song: com.example.data.model.Song -> viewModel.playerController.playNext(song) }
    }
    val onAddToQueue = remember(viewModel) {
        { song: com.example.data.model.Song -> viewModel.playerController.addToQueue(song) }
    }
    val onAddToPlaylist = remember(viewModel) {
        { song: com.example.data.model.Song -> viewModel.setSongForAddToPlaylist(song) }
    }
    val onRescanStorage = remember(viewModel) { { viewModel.refreshLibrary() } }
    val onHomeAlbumClick = remember(viewModel) {
        { album: com.example.data.model.Album ->
            viewModel.setNavigationTab(NavigationTab.ALBUMS)
            viewModel.selectAlbum(album)
        }
    }
    val onAlbumSelect = remember(viewModel) {
        { album: com.example.data.model.Album? -> viewModel.selectAlbum(album) }
    }
    val onArtistSelect = remember(viewModel) {
        { artist: com.example.data.model.Artist? -> viewModel.selectArtist(artist) }
    }
    val onFolderSelect = remember(viewModel) {
        { folder: com.example.data.model.Folder? -> viewModel.selectFolder(folder) }
    }
    val onPlaylistSelect = remember(viewModel) {
        { playlist: com.example.data.model.Playlist? -> viewModel.selectPlaylist(playlist) }
    }
    val onCreatePlaylist = remember(viewModel) {
        { name: String -> viewModel.createPlaylist(name) }
    }
    val onDeletePlaylist = remember(viewModel) {
        { playlistId: Long -> viewModel.deletePlaylist(playlistId) }
    }
    val onAddSongsToPlaylist = remember(viewModel) {
        { playlistId: Long, songIds: List<Long> -> viewModel.addSongsToPlaylist(playlistId, songIds) }
    }
    val onRemoveSongFromPlaylist = remember(viewModel) {
        { playlistId: Long, songId: Long -> viewModel.removeSongFromPlaylist(playlistId, songId) }
    }
    val onClearFavorites = remember(viewModel) { { viewModel.clearFavorites() } }
    val onThemeChange = remember(viewModel) { { mode: String -> viewModel.setThemeMode(mode) } }
    val onResumeLastChange = remember(viewModel) { { enabled: Boolean -> viewModel.setResumeLastPlayback(enabled) } }
    val onPauseOnHeadsetUnplugChange = remember(viewModel) { { enabled: Boolean -> viewModel.setPauseOnHeadsetUnplug(enabled) } }
    val onNavigateToSongs = remember(viewModel) { { viewModel.setNavigationTab(NavigationTab.SONGS) } }
    val onNavigateToAlbums = remember(viewModel) { { viewModel.setNavigationTab(NavigationTab.ALBUMS) } }
    val onNavigateToPlaylists = remember(viewModel) { { viewModel.setNavigationTab(NavigationTab.PLAYLISTS) } }
    val onNavigateToFavorites = remember(viewModel) { { viewModel.setNavigationTab(NavigationTab.FAVORITES) } }
    val onNavigateToArtists = remember(viewModel) { { viewModel.setNavigationTab(NavigationTab.ARTISTS) } }
    val onNavigateToSettings = remember(viewModel) { { viewModel.setNavigationTab(NavigationTab.SETTINGS) } }
    val onSearchQueryChange = remember(viewModel) { { query: String -> viewModel.setSearchQuery(query) } }
    val onSortOrderChange = remember(viewModel) { { order: com.example.data.model.SortOrder -> viewModel.setSortOrder(order) } }

    if (!libraryState.hasPermission && !hasCurrentSong) {
        PermissionView(
            onRequestPermission = onRequestPermission
        )
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // Isolated MiniPlayer section so progress ticks only update progress bar & 1Hz label
                    MiniPlayerSection(
                        viewModel = viewModel,
                        favoriteIds = libraryState.favoriteIds
                    )

                    // Slight top separation above simplified 5-tab Bottom Navigation Bar
                    androidx.compose.material3.HorizontalDivider(
                        thickness = 1.dp,
                        color = BottomDividerColor
                    )

                    // Simplified 5-Tab Bottom Navigation Bar: [ Home ] [ Songs ] [ Albums ] [ Folders ] [ Settings ]
                    NavigationBar(
                        modifier = Modifier.testTag("bottom_nav_bar"),
                        containerColor = BottomNavContainerColor,
                        tonalElevation = 0.dp
                    ) {
                        val navItemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = com.example.ui.theme.NeonPink,
                            selectedTextColor = com.example.ui.theme.NeonPink,
                            indicatorColor = BottomNavIndicatorColor,
                            unselectedIconColor = BottomNavUnselectedColor,
                            unselectedTextColor = BottomNavUnselectedColor
                        )
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
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = navItemColors,
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
                        fadeIn(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing))
                    },
                    label = "tab_transition"
                ) { tab ->
                    when (tab) {
                        NavigationTab.HOME -> {
                            HomeScreen(
                                songs = libraryState.songs,
                                albums = libraryState.albums,
                                favoriteSongs = favoriteSongs,
                                favoriteIds = libraryState.favoriteIds,
                                precomputedAlbumsPreview = libraryState.homeAlbumsPreview,
                                precomputedFavoritesPreview = libraryState.homeFavoritesPreview,
                                precomputedTracksPreview = libraryState.homeTracksPreview,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onSongClick = onSongClick,
                                onShuffleAll = onShuffleAllLibrary,
                                onPlayAll = onPlayAllLibrary,
                                onAlbumClick = onHomeAlbumClick,
                                onToggleFavorite = onToggleFavorite,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onAddToPlaylist = onAddToPlaylist,
                                onNavigateToSongs = onNavigateToSongs,
                                onNavigateToAlbums = onNavigateToAlbums,
                                onNavigateToPlaylists = onNavigateToPlaylists,
                                onNavigateToFavorites = onNavigateToFavorites,
                                onNavigateToArtists = onNavigateToArtists,
                                onNavigateToSettings = onNavigateToSettings,
                                playlistCount = libraryState.playlists.size,
                                artistCount = libraryState.artists.size
                            )
                        }

                        NavigationTab.SONGS -> {
                            ScannedAudioListScreen(
                                songs = libraryState.songs,
                                isLoading = libraryState.isLoading,
                                favoriteIds = libraryState.favoriteIds,
                                currentPlayingSongId = currentSongId,
                                isPlaying = isPlaying,
                                totalDurationFormatted = libraryState.totalDurationFormatted,
                                totalStorageFormatted = libraryState.totalStorageFormatted,
                                displayedSongs = libraryState.displayedSongs,
                                externalSearchQuery = libraryState.searchQuery,
                                externalSortOrder = libraryState.sortOrder,
                                onSearchQueryChange = onSearchQueryChange,
                                onSortOrderChange = onSortOrderChange,
                                onSongClick = onSongClick,
                                onPlayAll = onPlayAllList,
                                onShuffleAll = onShuffleAllList,
                                onToggleFavorite = onToggleFavorite,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onAddToPlaylist = onAddToPlaylist,
                                onRescanStorage = onRescanStorage
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
                                onPlaylistSelect = onPlaylistSelect,
                                onCreatePlaylist = onCreatePlaylist,
                                onDeletePlaylist = onDeletePlaylist,
                                onAddSongsToPlaylist = onAddSongsToPlaylist,
                                onRemoveSongFromPlaylist = onRemoveSongFromPlaylist,
                                onSongClick = onSongClick,
                                onPlayAll = onPlayAllList,
                                onShuffleAll = onShuffleAllList,
                                onToggleFavorite = onToggleFavorite,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue
                            )
                        }

                        NavigationTab.ALBUMS -> {
                            AlbumsScreen(
                                albums = libraryState.albums,
                                allSongs = libraryState.songs,
                                songsByAlbum = libraryState.songsByAlbum,
                                selectedAlbum = libraryState.selectedAlbum,
                                favoriteIds = libraryState.favoriteIds,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onAlbumSelect = onAlbumSelect,
                                onSongClick = onSongClick,
                                onPlayAll = onPlayAllList,
                                onShuffleAll = onShuffleAllList,
                                onToggleFavorite = onToggleFavorite,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onAddToPlaylist = onAddToPlaylist
                            )
                        }

                        NavigationTab.ARTISTS -> {
                            ArtistsScreen(
                                artists = libraryState.artists,
                                allSongs = libraryState.songs,
                                songsByArtist = libraryState.songsByArtist,
                                selectedArtist = libraryState.selectedArtist,
                                favoriteIds = libraryState.favoriteIds,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onArtistSelect = onArtistSelect,
                                onSongClick = onSongClick,
                                onPlayAll = onPlayAllList,
                                onShuffleAll = onShuffleAllList,
                                onToggleFavorite = onToggleFavorite,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onAddToPlaylist = onAddToPlaylist
                            )
                        }

                        NavigationTab.FOLDERS -> {
                            FoldersScreen(
                                folders = libraryState.folders,
                                allSongs = libraryState.songs,
                                songsByFolder = libraryState.songsByFolder,
                                selectedFolder = libraryState.selectedFolder,
                                favoriteIds = libraryState.favoriteIds,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onFolderSelect = onFolderSelect,
                                onSongClick = onSongClick,
                                onPlayAll = onPlayAllList,
                                onShuffleAll = onShuffleAllList,
                                onToggleFavorite = onToggleFavorite,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onAddToPlaylist = onAddToPlaylist
                            )
                        }

                        NavigationTab.FAVORITES -> {
                            FavoritesScreen(
                                favoriteSongs = favoriteSongs,
                                currentSongId = currentSongId,
                                isPlaying = isPlaying,
                                onSongClick = onSongClick,
                                onPlayAll = onPlayAllList,
                                onShuffleAll = onShuffleAllList,
                                onToggleFavorite = onToggleFavorite,
                                onClearFavorites = onClearFavorites,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onAddToPlaylist = onAddToPlaylist
                            )
                        }

                        NavigationTab.SETTINGS -> {
                            SettingsScreen(
                                currentTheme = libraryState.userThemePreference,
                                onThemeChange = onThemeChange,
                                onClearFavorites = onClearFavorites,
                                resumeLastPlayback = libraryState.resumeLastPlayback,
                                onResumeLastChange = onResumeLastChange,
                                autoPauseOnHeadsetUnplug = libraryState.pauseOnHeadsetUnplug,
                                onPauseOnHeadsetUnplugChange = onPauseOnHeadsetUnplugChange
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

        // Add to Playlist Dialog (rendered above Now Playing so it works from both Library and Now Playing)
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
}

@Composable
private fun MiniPlayerSection(
    viewModel: MusicViewModel,
    favoriteIds: Set<Long>
) {
    val staticPlayerState by viewModel.staticPlayerUiState.collectAsState()
    val currentSongId = staticPlayerState.currentSong?.id
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
        playerState = staticPlayerState,
        progressFlow = viewModel.playerUiState,
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
    val onAddToPlaylist = remember(viewModel) { { song: com.example.data.model.Song -> viewModel.setSongForAddToPlaylist(song) } }
    val onRemoveFromQueue = remember(viewModel) { { idx: Int -> viewModel.playerController.removeFromQueue(idx) } }
    val onJumpToQueueItem = remember(viewModel) { { idx: Int -> viewModel.playerController.jumpToQueueItem(idx) } }
    val onPlaybackSpeedChange = remember(viewModel) { { speed: Float -> viewModel.playerController.setPlaybackSpeed(speed) } }

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
        val staticPlayerState by viewModel.staticPlayerUiState.collectAsState()
        val currentSongId = staticPlayerState.currentSong?.id
        val isCurrentFav = currentSongId != null && favoriteIds.contains(currentSongId)
        val onToggleFavorite = remember(viewModel, currentSongId) {
            { currentSongId?.let { viewModel.toggleFavorite(it) } ?: Unit }
        }
        NowPlayingScreen(
            playerState = staticPlayerState,
            progressFlow = viewModel.playerUiState,
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
            onAddToPlaylist = onAddToPlaylist,
            onRemoveFromQueue = onRemoveFromQueue,
            onJumpToQueueItem = onJumpToQueueItem,
            onPlaybackSpeedChange = onPlaybackSpeedChange
        )
    }
}

private data class NavItem(
    val tab: NavigationTab,
    val label: String,
    val icon: ImageVector
)

