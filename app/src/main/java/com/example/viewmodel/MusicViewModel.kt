package com.example.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.PulseMusicApp
import com.example.data.local.FavoriteEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistSongCrossRef
import com.example.data.model.Album
import com.example.data.model.Artist
import com.example.data.model.Folder
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.data.model.SortOrder
import com.example.player.MusicPlayerController
import com.example.player.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NavigationTab {
    HOME,
    SONGS,
    PLAYLISTS,
    ALBUMS,
    ARTISTS,
    FOLDERS,
    FAVORITES,
    SETTINGS
}

@Immutable
data class RootPlaybackSummary(
    val currentSongId: Long? = null,
    val isPlaying: Boolean = false,
    val hasCurrentSong: Boolean = false,
    val errorMessage: String? = null
)

@Immutable
data class LibraryUiState(
    val isLoading: Boolean = false,
    val songs: List<Song> = emptyList(),
    val displayedSongs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylist: Playlist? = null,
    val selectedPlaylistSongs: List<Song> = emptyList(),
    val songForAddToPlaylist: Song? = null,
    val favoriteIds: Set<Long> = emptySet(),
    val favoriteSongs: List<Song> = emptyList(),
    val homeAlbumsPreview: List<Album> = emptyList(),
    val homeFavoritesPreview: List<Song> = emptyList(),
    val homeTracksPreview: List<Song> = emptyList(),
    val songsByAlbum: Map<String, List<Song>> = emptyMap(),
    val songsByArtist: Map<String, List<Song>> = emptyMap(),
    val songsByFolder: Map<String, List<Song>> = emptyMap(),
    val totalDurationFormatted: String = "0m",
    val totalStorageFormatted: String = "0.0 MB",
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.TITLE_ASC,
    val selectedTab: NavigationTab = NavigationTab.HOME,
    val selectedAlbum: Album? = null,
    val selectedArtist: Artist? = null,
    val selectedFolder: Folder? = null,
    val isNowPlayingExpanded: Boolean = false,
    val isQueueVisible: Boolean = false,
    val hasPermission: Boolean = false,
    val userThemePreference: String = "system",
    val resumeLastPlayback: Boolean = true,
    val pauseOnHeadsetUnplug: Boolean = true
)

@Stable
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PulseMusicApp
    private val audioScanRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { app.audioScanRepository }
    private val playbackStateRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { app.playbackStateRepository }
    private val musicDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { app.database.musicDao() }
    private val userPrefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { app.userPreferences }

    val playerController: MusicPlayerController = MusicPlayerController(
        context = application,
        scope = viewModelScope,
        playbackStateRepository = playbackStateRepository
    )

    val playerUiState: StateFlow<PlayerUiState> = playerController.uiState

    val staticPlayerUiState = playerUiState
        .distinctUntilChanged { old, new ->
            old.currentSong == new.currentSong &&
                old.currentIndex == new.currentIndex &&
                old.isPlaying == new.isPlaying &&
                old.isBuffering == new.isBuffering &&
                old.durationMs == new.durationMs &&
                old.isShuffleEnabled == new.isShuffleEnabled &&
                old.repeatMode == new.repeatMode &&
                old.playbackSpeed == new.playbackSpeed &&
                old.queue === new.queue &&
                old.errorMessage == new.errorMessage
        }

    val rootPlaybackSummary = playerUiState
        .map { state ->
            RootPlaybackSummary(
                currentSongId = state.currentSong?.id,
                isPlaying = state.isPlaying,
                hasCurrentSong = state.currentSong != null,
                errorMessage = state.errorMessage
            )
        }
        .distinctUntilChanged()

    private val _libraryState = MutableStateFlow(LibraryUiState())
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()

    private var playlistSongsJob: Job? = null
    private var scanJob: Job? = null
    private var filterSortJob: Job? = null
    private val hasStartedDatabaseObservers = java.util.concurrent.atomic.AtomicBoolean(false)
    private val hasPerformedInitialScan = java.util.concurrent.atomic.AtomicBoolean(false)
    private val hasAttemptedInitialRestore = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isScanInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastGroupedSongIds: List<Long> = emptyList()

    init {
        observePreferences()
    }

    /**
     * Starts Room database flow observers idempotently on background dispatchers.
     * Calling this multiple times is a safe no-op.
     */
    fun ensureDatabaseObserversStarted() {
        if (!hasStartedDatabaseObservers.compareAndSet(false, true)) return
        observeSongsFromDatabase()
        observeFavorites()
        observePlaylists()
    }

    private fun computeDisplayedSongs(
        songs: List<Song>,
        rawQuery: String,
        sortOrder: SortOrder
    ): List<Song> {
        val query = rawQuery.trim()
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
        return when (sortOrder) {
            SortOrder.TITLE_ASC -> filtered
            SortOrder.ARTIST_ASC -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
            SortOrder.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
            SortOrder.DATE_ADDED_DESC -> filtered.sortedByDescending { it.dateAdded }
        }
    }

    private fun observeSongsFromDatabase() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                audioScanRepository.getBaseSongsFlow().collect { songsList ->
                    try {
                        val currentSongIds = songsList.map { it.id }
                        val currentState = _libraryState.value
                        val favIds = currentState.favoriteIds
                        val favSongs = if (favIds.isEmpty()) emptyList() else songsList.filter { favIds.contains(it.id) }
                        val displayed = computeDisplayedSongs(songsList, currentState.searchQuery, currentState.sortOrder)
                        val homeTracks = songsList.take(15)
                        val homeFavs = favSongs.take(5)

                        if (currentSongIds != lastGroupedSongIds || (songsList.isEmpty() && currentState.albums.isNotEmpty())) {
                            lastGroupedSongIds = currentSongIds
                            coroutineScope {
                                val albumsDeferred = async { audioScanRepository.getAlbums(songsList) }
                                val artistsDeferred = async { audioScanRepository.getArtists(songsList) }
                                val foldersDeferred = async { audioScanRepository.getFolders(songsList) }
                                val byAlbumDeferred = async {
                                    songsList.groupBy { it.album.lowercase(java.util.Locale.ROOT) }
                                }
                                val byArtistDeferred = async {
                                    songsList.groupBy { it.artist.lowercase(java.util.Locale.ROOT) }
                                }
                                val byFolderDeferred = async {
                                    songsList.groupBy { it.folderName.lowercase(java.util.Locale.ROOT) }
                                }
                                val statsDeferred = async {
                                    val totalSeconds = songsList.sumOf { it.durationMs } / 1000L
                                    val hours = totalSeconds / 3600L
                                    val minutes = (totalSeconds % 3600L) / 60L
                                    val durStr = if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"

                                    val totalBytes = songsList.sumOf { it.sizeBytes }
                                    val mb = totalBytes.toDouble() / (1024.0 * 1024.0)
                                    val sizeStr = if (mb >= 1024.0) {
                                        String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
                                    } else {
                                        String.format(java.util.Locale.US, "%.1f MB", mb)
                                    }
                                    durStr to sizeStr
                                }

                                val albums = albumsDeferred.await()
                                val artists = artistsDeferred.await()
                                val folders = foldersDeferred.await()
                                val byAlbum = byAlbumDeferred.await()
                                val byArtist = byArtistDeferred.await()
                                val byFolder = byFolderDeferred.await()
                                val (durStr, sizeStr) = statsDeferred.await()
                                val homeAlbums = albums.take(10)

                                _libraryState.update { state ->
                                    val latestFavIds = state.favoriteIds
                                    val latestFavSongs = if (latestFavIds === favIds) {
                                        favSongs
                                    } else if (latestFavIds.isEmpty()) {
                                        emptyList()
                                    } else {
                                        songsList.filter { latestFavIds.contains(it.id) }
                                    }
                                    val latestDisplayed = if (state.searchQuery == currentState.searchQuery &&
                                        state.sortOrder == currentState.sortOrder
                                    ) {
                                        displayed
                                    } else {
                                        computeDisplayedSongs(songsList, state.searchQuery, state.sortOrder)
                                    }
                                    state.copy(
                                        songs = songsList,
                                        displayedSongs = latestDisplayed,
                                        albums = albums,
                                        artists = artists,
                                        folders = folders,
                                        favoriteSongs = latestFavSongs,
                                        homeAlbumsPreview = homeAlbums,
                                        homeFavoritesPreview = latestFavSongs.take(5),
                                        homeTracksPreview = homeTracks,
                                        songsByAlbum = byAlbum,
                                        songsByArtist = byArtist,
                                        songsByFolder = byFolder,
                                        totalDurationFormatted = durStr,
                                        totalStorageFormatted = sizeStr
                                    )
                                }
                            }
                        } else {
                            _libraryState.update { state ->
                                state.copy(
                                    songs = songsList,
                                    displayedSongs = displayed,
                                    favoriteSongs = favSongs,
                                    homeFavoritesPreview = homeFavs,
                                    homeTracksPreview = homeTracks
                                )
                            }
                        }
                        if (songsList.isNotEmpty() &&
                            _libraryState.value.resumeLastPlayback &&
                            hasAttemptedInitialRestore.compareAndSet(false, true)
                        ) {
                            try {
                                playerController.restorePlaybackState(songsList)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                musicDao.getAllFavoriteIds()
                    .distinctUntilChanged()
                    .map { it.toHashSet() }
                    .collect { favSet ->
                        val currentSongs = _libraryState.value.songs
                        val favSongs = if (favSet.isEmpty() || currentSongs.isEmpty()) {
                            emptyList()
                        } else {
                            currentSongs.filter { favSet.contains(it.id) }
                        }
                        val favPreview = favSongs.take(5)
                        _libraryState.update {
                            it.copy(
                                favoriteIds = favSet,
                                favoriteSongs = favSongs,
                                homeFavoritesPreview = favPreview
                            )
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun observePlaylists() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                musicDao.getPlaylistsWithTrackCount()
                    .distinctUntilChanged()
                    .map { list -> list.map { it.toPlaylist() } }
                    .collect { playlists ->
                        _libraryState.update { it.copy(playlists = playlists) }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                userPrefs.themeMode
                    .distinctUntilChanged()
                    .collect { mode ->
                        _libraryState.update { it.copy(userThemePreference = mode) }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                userPrefs.resumeLast
                    .distinctUntilChanged()
                    .collect { resume ->
                        _libraryState.update { it.copy(resumeLastPlayback = resume) }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                userPrefs.pauseOnHeadsetUnplug
                    .distinctUntilChanged()
                    .collect { pauseOnUnplug ->
                        _libraryState.update { it.copy(pauseOnHeadsetUnplug = pauseOnUnplug) }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun onPermissionResult(isGranted: Boolean) {
        val wasGranted = _libraryState.value.hasPermission
        if (wasGranted != isGranted) {
            _libraryState.update { it.copy(hasPermission = isGranted) }
        }
        if (!isGranted) {
            return
        }
        ensureDatabaseObserversStarted()
        playerController.connectIfServiceActive()
        if (!wasGranted || hasPerformedInitialScan.compareAndSet(false, true)) {
            hasPerformedInitialScan.set(true)
            refreshLibrary()
        }
    }

    fun refreshLibrary() {
        if (!_libraryState.value.hasPermission) return
        if (!isScanInFlight.compareAndSet(false, true)) return
        com.example.ui.components.ArtworkCacheManager.clear()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _libraryState.update { it.copy(isLoading = true) }
            try {
                audioScanRepository.scanAndSyncDeviceAudio()
                val currentSongs = _libraryState.value.songs
                if (_libraryState.value.resumeLastPlayback && currentSongs.isNotEmpty()) {
                    hasAttemptedInitialRestore.set(true)
                    playerController.restorePlaybackState(currentSongs)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isScanInFlight.set(false)
                _libraryState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun setNavigationTab(tab: NavigationTab) {
        _libraryState.update {
            if (it.selectedTab == tab &&
                it.selectedAlbum == null &&
                it.selectedArtist == null &&
                it.selectedFolder == null &&
                it.selectedPlaylist == null
            ) {
                it
            } else {
                it.copy(
                    selectedTab = tab,
                    selectedAlbum = null,
                    selectedArtist = null,
                    selectedFolder = null,
                    selectedPlaylist = null
                )
            }
        }
    }

    fun selectAlbum(album: Album?) {
        _libraryState.update { it.copy(selectedAlbum = album) }
    }

    fun selectArtist(artist: Artist?) {
        _libraryState.update { it.copy(selectedArtist = artist) }
    }

    fun selectFolder(folder: Folder?) {
        _libraryState.update { it.copy(selectedFolder = folder) }
    }

    fun selectPlaylist(playlist: Playlist?) {
        _libraryState.update { it.copy(selectedPlaylist = playlist) }
        if (playlist != null) {
            observePlaylistSongs(playlist.id)
        } else {
            playlistSongsJob?.cancel()
            _libraryState.update { it.copy(selectedPlaylistSongs = emptyList()) }
        }
    }

    private fun observePlaylistSongs(playlistId: Long) {
        playlistSongsJob?.cancel()
        playlistSongsJob = viewModelScope.launch {
            combine(
                musicDao.getSongsForPlaylist(playlistId).distinctUntilChanged(),
                musicDao.getAllFavoriteIds().distinctUntilChanged()
            ) { entities, favIds ->
                val favSet = favIds.toHashSet()
                entities.map { it.toDomainSong(isFavorite = favSet.contains(it.id)) }
            }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { domainSongs ->
                    _libraryState.update { it.copy(selectedPlaylistSongs = domainSongs) }
                }
        }
    }

    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.insertPlaylist(PlaylistEntity(name = trimmed))
        }
    }

    fun createPlaylistAndAddSong(name: String, song: Song) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val playlistId = musicDao.insertPlaylist(PlaylistEntity(name = trimmed))
            musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlistId, songId = song.id, orderIndex = 0))
            _libraryState.update { it.copy(songForAddToPlaylist = null) }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.deletePlaylistWithSongs(playlistId)
            if (_libraryState.value.selectedPlaylist?.id == playlistId) {
                _libraryState.update { it.copy(selectedPlaylist = null, selectedPlaylistSongs = emptyList()) }
            }
        }
    }

    fun setSongForAddToPlaylist(song: Song?) {
        _libraryState.update { it.copy(songForAddToPlaylist = song) }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.appendSongsToPlaylist(playlistId, listOf(song.id))
            _libraryState.update { it.copy(songForAddToPlaylist = null) }
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.appendSongsToPlaylist(playlistId, songIds)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.removeSongFromPlaylist(playlistId, songId)
        }
    }

    fun setNowPlayingExpanded(expanded: Boolean) {
        _libraryState.update { it.copy(isNowPlayingExpanded = expanded) }
    }

    fun setQueueVisible(visible: Boolean) {
        _libraryState.update { it.copy(isQueueVisible = visible) }
    }

    fun setSearchQuery(query: String) {
        _libraryState.update { it.copy(searchQuery = query) }
        recomputeDisplayedSongsAsync()
    }

    fun setSortOrder(order: SortOrder) {
        _libraryState.update { it.copy(sortOrder = order) }
        recomputeDisplayedSongsAsync()
    }

    private fun recomputeDisplayedSongsAsync() {
        filterSortJob?.cancel()
        val snapshot = _libraryState.value
        val songs = snapshot.songs
        val query = snapshot.searchQuery
        val order = snapshot.sortOrder
        filterSortJob = viewModelScope.launch(Dispatchers.Default) {
            val result = computeDisplayedSongs(songs, query, order)
            _libraryState.update { current ->
                if (current.songs === songs && current.searchQuery == query && current.sortOrder == order) {
                    current.copy(displayedSongs = result)
                } else {
                    current
                }
            }
        }
    }

    fun toggleFavorite(songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val isFav = _libraryState.value.favoriteIds.contains(songId)
            if (isFav) {
                musicDao.deleteFavorite(songId)
            } else {
                musicDao.insertFavorite(FavoriteEntity(songId = songId))
            }
        }
    }

    fun clearFavorites() {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.clearFavorites()
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            userPrefs.setThemeMode(mode)
        }
    }

    fun setResumeLastPlayback(enabled: Boolean) {
        _libraryState.update { it.copy(resumeLastPlayback = enabled) }
        viewModelScope.launch {
            userPrefs.setResumeLast(enabled)
        }
    }

    fun setPauseOnHeadsetUnplug(enabled: Boolean) {
        _libraryState.update { it.copy(pauseOnHeadsetUnplug = enabled) }
        viewModelScope.launch {
            userPrefs.setPauseOnHeadsetUnplug(enabled)
        }
    }

    fun playSongFromList(song: Song, list: List<Song>) {
        val currentState = playerController.uiState.value
        val index = list.indexOfFirst { it.id == song.id }
        if (currentState.currentSong?.id == song.id && playerController.isInRestoredPausedState()) {
            // Re-opening the same song after app restart: preserve the saved position (e.g., 0:42),
            // do not auto-play until Play is pressed, and expand the Now Playing screen.
            val targetQueue = if (index != -1) list else listOf(song)
            val targetIndex = if (index != -1) index else 0
            playerController.playQueue(
                songs = targetQueue,
                startIndex = targetIndex,
                startPositionMs = currentState.positionMs,
                autoPlay = false
            )
            _libraryState.update { it.copy(isNowPlayingExpanded = true) }
            return
        }
        if (index != -1) {
            playerController.playQueue(list, index)
        } else {
            playerController.playQueue(listOf(song), 0)
        }
    }

    fun playAll(songs: List<Song>, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val list = if (shuffle) songs.shuffled() else songs
        playerController.playQueue(list, 0)
    }

    fun savePlaybackStateNow() {
        playerController.saveCurrentPlaybackState(immediate = true)
    }

    /**
     * Validates that an incoming external URI uses a permitted scheme (content/file)
     * and does not traverse into the application's private internal data directories.
     */
    internal fun isSafeExternalAudioUri(uri: android.net.Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "content" && scheme != "file") return false

        if (scheme == "file") {
            val rawPath = uri.path ?: return false
            return try {
                val canonicalTarget = java.io.File(rawPath).canonicalPath
                val privateDataDir = app.applicationInfo?.dataDir?.let { java.io.File(it).canonicalPath }
                val privateFilesDir = app.filesDir?.parentFile?.canonicalPath
                val isInsidePrivateDir = (privateDataDir != null && canonicalTarget.startsWith(privateDataDir)) ||
                    (privateFilesDir != null && canonicalTarget.startsWith(privateFilesDir))
                !isInsidePrivateDir
            } catch (_: Exception) {
                false
            }
        }
        return true
    }

    fun playExternalAudioUri(uri: android.net.Uri, mimeType: String? = null) {
        if (!isSafeExternalAudioUri(uri)) {
            playerController.reportExplicitPlaybackError("Invalid or unsupported audio file")
            return
        }

        // Prevent startup restoration or MediaStore scan from racing with this external Intent
        playerController.notifyExternalIntentPending()

        val currentSongs = _libraryState.value.songs
        val exactMatch = currentSongs.firstOrNull { song -> song.contentUri == uri }
        if (exactMatch != null) {
            playSongFromList(exactMatch, currentSongs)
            _libraryState.update { it.copy(isNowPlayingExpanded = true) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (!playbackStateRepository.isExternalAudioUriValidAndAccessible(
                    uri = uri,
                    explicitMimeType = mimeType
                )
            ) {
                launch(Dispatchers.Main) {
                    playerController.reportExplicitPlaybackError("Unable to play the selected audio file")
                }
                return@launch
            }

            val song = resolveExternalSong(uri)
            launch(Dispatchers.Main) {
                playerController.playQueue(listOf(song), 0)
                _libraryState.update { it.copy(isNowPlayingExpanded = true) }
            }
        }
    }

    internal fun resolveExternalSong(uri: android.net.Uri): Song {
        var displayName: String? = null
        var fileSize = 0L

        if (uri.scheme?.lowercase() == "content") {
            try {
                app.contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.OpenableColumns.DISPLAY_NAME,
                        android.provider.OpenableColumns.SIZE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIdx != -1 && !cursor.isNull(nameIdx)) {
                            displayName = cursor.getString(nameIdx)
                        }
                        if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                            fileSize = cursor.getLong(sizeIdx)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        } else if (uri.scheme?.lowercase() == "file") {
            uri.path?.let { filePath ->
                val f = java.io.File(filePath)
                if (f.exists()) {
                    displayName = f.name
                    fileSize = f.length()
                }
            }
        }

        val decodedSegment = try {
            uri.lastPathSegment?.let { android.net.Uri.decode(it) }
        } catch (_: Exception) {
            uri.lastPathSegment
        }

        val fallbackTitle = (displayName ?: decodedSegment ?: "Audio Track")
            .substringAfterLast('/')
            .substringAfterLast(':')
            .substringBeforeLast('.')
            .ifBlank { "Audio Track" }

        var title = fallbackTitle
        var artist = "Unknown Artist"
        var album = "External Audio"
        var durationMs = 0L

        val retriever = android.media.MediaMetadataRetriever()
        try {
            if (uri.scheme?.lowercase() == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(app, uri)
            }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?.let { title = it }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?.let { artist = it }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
                ?.let { album = it }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { durationMs = it }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        // Keep original content:// URI directly for Media3; never convert content:// into a file path
        val safePath = if (uri.scheme?.lowercase() == "file") (uri.path ?: "") else ""

        return Song(
            id = uri.toString().hashCode().toLong(),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            contentUri = uri,
            albumArtUri = null,
            sizeBytes = fileSize,
            dateAdded = System.currentTimeMillis() / 1000L,
            folderName = "External",
            path = safePath
        )
    }

    override fun onCleared() {
        super.onCleared()
        playerController.stopAndReleaseOnTaskRemoved()
    }
}

