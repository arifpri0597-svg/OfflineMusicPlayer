package com.example.viewmodel

import android.app.Application
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val userThemePreference: String = "system"
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PulseMusicApp
    private val audioScanRepository = app.audioScanRepository
    private val musicDao = app.database.musicDao()
    private val userPrefs = app.userPreferences

    val playerController: MusicPlayerController = MusicPlayerController(
        context = application,
        scope = viewModelScope,
        onSongPlayed = { song ->
            recordSongHistory(song)
        }
    )

    val playerUiState: StateFlow<PlayerUiState> = playerController.uiState

    private val _libraryState = MutableStateFlow(LibraryUiState())
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()

    private var playlistSongsJob: Job? = null

    init {
        observeSongsFromDatabase()
        observeFavoritesAndHistory()
        observePlaylists()
        observePreferences()
    }

    private fun observeSongsFromDatabase() {
        viewModelScope.launch {
            audioScanRepository.getSongsFlow().collect { songsList ->
                val albums = audioScanRepository.getAlbums(songsList)
                val artists = audioScanRepository.getArtists(songsList)
                val folders = audioScanRepository.getFolders(songsList)
                _libraryState.update { state ->
                    state.copy(
                        songs = songsList,
                        albums = albums,
                        artists = artists,
                        folders = folders
                    )
                }
            }
        }
    }

    private fun observeFavoritesAndHistory() {
        viewModelScope.launch {
            musicDao.getAllFavoriteIds().collect { favList ->
                _libraryState.update { it.copy(favoriteIds = favList.toSet()) }
            }
        }
        viewModelScope.launch {
            musicDao.getHistory().collect { historyList ->
                _libraryState.update { it.copy(historyItems = historyList) }
            }
        }
    }

    private fun observePlaylists() {
        viewModelScope.launch {
            musicDao.getPlaylistsWithTrackCount().collect { playlistsWithCount ->
                val list = playlistsWithCount.map { it.toPlaylist() }
                _libraryState.update { it.copy(playlists = list) }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            userPrefs.themeMode.collect { mode ->
                _libraryState.update { it.copy(userThemePreference = mode) }
            }
        }
    }

    fun onPermissionResult(isGranted: Boolean) {
        _libraryState.update { it.copy(hasPermission = isGranted) }
        if (isGranted) {
            refreshLibrary()
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
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
            it.copy(
                selectedTab = tab,
                selectedAlbum = null,
                selectedArtist = null,
                selectedFolder = null,
                selectedPlaylist = null
            )
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
                musicDao.getSongsForPlaylist(playlistId),
                musicDao.getAllFavoriteIds()
            ) { entities, favIds ->
                val favSet = favIds.toSet()
                entities.map { it.toDomainSong(isFavorite = favSet.contains(it.id)) }
            }.collect { domainSongs ->
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
            musicDao.deletePlaylist(playlistId)
            musicDao.deletePlaylistSongs(playlistId)
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
            val count = musicDao.getPlaylistTrackCount(playlistId)
            musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlistId, songId = song.id, orderIndex = count))
            _libraryState.update { it.copy(songForAddToPlaylist = null) }
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var currentCount = musicDao.getPlaylistTrackCount(playlistId)
            for (id in songIds) {
                musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlistId, songId = id, orderIndex = currentCount++))
            }
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
        val index = list.indexOfFirst { it.id == song.id }
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

    override fun onCleared() {
        super.onCleared()
        playerController.release()
    }
}
