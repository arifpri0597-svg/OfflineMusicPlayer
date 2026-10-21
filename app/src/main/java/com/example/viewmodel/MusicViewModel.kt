package com.example.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.PulseMusicApp
import com.example.data.local.FavoriteEntity
import com.example.data.local.HistoryEntity
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
    HISTORY,
    SETTINGS
}

@Immutable
data class LibraryUiState(
    val isLoading: Boolean = false,
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylist: Playlist? = null,
    val selectedPlaylistSongs: List<Song> = emptyList(),
    val songForAddToPlaylist: Song? = null,
    val favoriteIds: Set<Long> = emptySet(),
    val historyItems: List<HistoryEntity> = emptyList(),
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
    private val audioScanRepository = app.audioScanRepository
    private val playbackStateRepository = app.playbackStateRepository
    private val musicDao = app.database.musicDao()
    private val userPrefs = app.userPreferences

    val playerController: MusicPlayerController = MusicPlayerController(
        context = application,
        scope = viewModelScope,
        onSongPlayed = { song ->
            recordSongHistory(song)
        },
        playbackStateRepository = playbackStateRepository
    )

    val playerUiState: StateFlow<PlayerUiState> = playerController.uiState

    private val _libraryState = MutableStateFlow(LibraryUiState())
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()

    private var playlistSongsJob: Job? = null
    private var scanJob: Job? = null
    private var hasPerformedInitialScan = false
    private var lastGroupedSongIds: List<Long> = emptyList()

    init {
        restoreLastPlaybackState()
        observeSongsFromDatabase()
        observeFavoritesAndHistory()
        observePlaylists()
        observePreferences()
    }

    private fun restoreLastPlaybackState(librarySongs: List<Song> = emptyList()) {
        viewModelScope.launch {
            if (_libraryState.value.resumeLastPlayback) {
                playerController.restorePlaybackState(librarySongs)
            }
        }
    }

    private fun observeSongsFromDatabase() {
        viewModelScope.launch(Dispatchers.Default) {
            audioScanRepository.getSongsFlow().collect { songsList ->
                val currentSongIds = songsList.map { it.id }
                if (currentSongIds != lastGroupedSongIds || (songsList.isEmpty() && _libraryState.value.albums.isNotEmpty())) {
                    lastGroupedSongIds = currentSongIds
                    coroutineScope {
                        val albumsDeferred = async { audioScanRepository.getAlbums(songsList) }
                        val artistsDeferred = async { audioScanRepository.getArtists(songsList) }
                        val foldersDeferred = async { audioScanRepository.getFolders(songsList) }
                        launch { playerController.prewarmMediaItems(songsList) }

                        val albums = albumsDeferred.await()
                        val artists = artistsDeferred.await()
                        val folders = foldersDeferred.await()
                        _libraryState.update { state ->
                            state.copy(
                                songs = songsList,
                                albums = albums,
                                artists = artists,
                                folders = folders
                            )
                        }
                    }
                } else {
                    _libraryState.update { state ->
                        state.copy(songs = songsList)
                    }
                }
                if (songsList.isNotEmpty() && _libraryState.value.resumeLastPlayback) {
                    playerController.restorePlaybackState(songsList)
                }
            }
        }
    }

    private fun observeFavoritesAndHistory() {
        viewModelScope.launch {
            musicDao.getAllFavoriteIds()
                .distinctUntilChanged()
                .map { it.toHashSet() }
                .flowOn(Dispatchers.Default)
                .collect { favSet ->
                    _libraryState.update { it.copy(favoriteIds = favSet) }
                }
        }
        viewModelScope.launch {
            musicDao.getHistory()
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { historyList ->
                    _libraryState.update { it.copy(historyItems = historyList) }
                }
        }
    }

    private fun observePlaylists() {
        viewModelScope.launch {
            musicDao.getPlaylistsWithTrackCount()
                .distinctUntilChanged()
                .map { list -> list.map { it.toPlaylist() } }
                .flowOn(Dispatchers.Default)
                .collect { playlists ->
                    _libraryState.update { it.copy(playlists = playlists) }
                }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            userPrefs.themeMode
                .distinctUntilChanged()
                .collect { mode ->
                    _libraryState.update { it.copy(userThemePreference = mode) }
                }
        }
        viewModelScope.launch {
            userPrefs.resumeLast
                .distinctUntilChanged()
                .collect { resume ->
                    _libraryState.update { it.copy(resumeLastPlayback = resume) }
                }
        }
        viewModelScope.launch {
            userPrefs.pauseOnHeadsetUnplug
                .distinctUntilChanged()
                .collect { pauseOnUnplug ->
                    _libraryState.update { it.copy(pauseOnHeadsetUnplug = pauseOnUnplug) }
                }
        }
    }

    fun onPermissionResult(isGranted: Boolean) {
        val wasGranted = _libraryState.value.hasPermission
        if (wasGranted != isGranted) {
            _libraryState.update { it.copy(hasPermission = isGranted) }
        }
        if (isGranted && (!hasPerformedInitialScan || !wasGranted)) {
            hasPerformedInitialScan = true
            refreshLibrary()
        }
    }

    fun refreshLibrary() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _libraryState.update { it.copy(isLoading = true) }
            try {
                audioScanRepository.scanAndSyncDeviceAudio()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
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
    }

    fun setSortOrder(order: SortOrder) {
        _libraryState.update { it.copy(sortOrder = order) }
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

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.clearHistory()
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

    private fun recordSongHistory(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.insertHistory(
                HistoryEntity(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist
                )
            )
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

    fun playExternalAudioUri(uri: android.net.Uri) {
        if (!isSafeExternalAudioUri(uri)) return

        val currentSongs = _libraryState.value.songs
        val existingMatch = currentSongs.firstOrNull { song ->
            song.contentUri == uri ||
                (uri.lastPathSegment != null && song.contentUri.lastPathSegment == uri.lastPathSegment)
        }
        if (existingMatch != null) {
            playSongFromList(existingMatch, currentSongs)
            _libraryState.update { it.copy(isNowPlayingExpanded = true) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val song = resolveExternalSong(uri)
            launch(Dispatchers.Main) {
                playerController.playQueue(listOf(song), 0)
                _libraryState.update { it.copy(isNowPlayingExpanded = true) }
            }
        }
    }

    private fun resolveExternalSong(uri: android.net.Uri): Song {
        var displayName: String? = null
        var fileSize = 0L

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

        val fallbackTitle = (displayName ?: uri.lastPathSegment ?: "Audio Track")
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifBlank { "Audio Track" }

        var title = fallbackTitle
        var artist = "Unknown Artist"
        var album = "External Audio"
        var durationMs = 0L

        val retriever = android.media.MediaMetadataRetriever()
        try {
            if (uri.scheme == "file" && uri.path != null) {
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
            path = uri.path ?: uri.toString()
        )
    }

    override fun onCleared() {
        savePlaybackStateNow()
        super.onCleared()
        playerController.release()
    }
}

