package com.example.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.model.RepeatMode
import com.example.data.model.Song
import com.example.player.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class MusicPlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSongPlayed: (Song) -> Unit = {},
    initialPlayer: Player? = null
) {
    private var controller: Player? = initialPlayer
    private var progressJob: Job? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var activeQueue: MutableList<Song> = mutableListOf()
    private var pendingPlayRequest: Pair<List<Song>, Int>? = null
    private var lastRecordedSongId: Long? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startProgressUpdate()
            } else {
                stopProgressUpdate()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isBuffering = playbackState == Player.STATE_BUFFERING
            _uiState.update { it.copy(isBuffering = isBuffering) }
            syncStateFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateFromPlayer()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val mappedRepeat = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
            _uiState.update { it.copy(repeatMode = mappedRepeat) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _uiState.update { it.copy(isShuffleEnabled = shuffleModeEnabled) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val msg = error.message ?: "Playback error encountered"
            _uiState.update { it.copy(errorMessage = msg) }
            // Skip safely to next track if available
            val player = controller ?: return
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.play()
            }
        }
    }

    init {
        if (initialPlayer != null) {
            initialPlayer.addListener(playerListener)
            syncStateFromPlayer()
        } else {
            initializeMediaController()
        }
    }

    internal fun setPlayerForTesting(testPlayer: Player) {
        controller?.removeListener(playerListener)
        controller = testPlayer
        testPlayer.addListener(playerListener)
        val pending = pendingPlayRequest
        if (pending != null) {
            pendingPlayRequest = null
            playQueue(pending.first, pending.second)
        } else {
            syncStateFromPlayer()
        }
    }

    private fun initializeMediaController() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                controller = controllerFuture.get()
                controller?.addListener(playerListener)
                val pending = pendingPlayRequest
                if (pending != null) {
                    pendingPlayRequest = null
                    playQueue(pending.first, pending.second)
                } else {
                    syncStateFromPlayer()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, songs.size - 1)
        val player = controller
        if (player == null) {
            pendingPlayRequest = songs to safeIndex
            activeQueue = songs.toMutableList()
            _uiState.update {
                it.copy(
                    queue = activeQueue.toList(),
                    currentIndex = safeIndex,
                    currentSong = activeQueue.getOrNull(safeIndex)
                )
            }
            return
        }

        activeQueue = songs.toMutableList()
        val mediaItems = songs.map { songToMediaItem(it) }

        player.setMediaItems(mediaItems, safeIndex, 0L)
        player.prepare()
        player.play()

        val targetSong = activeQueue.getOrNull(safeIndex)
        _uiState.update {
            it.copy(
                queue = activeQueue.toList(),
                currentIndex = safeIndex,
                currentSong = targetSong
            )
        }
        if (targetSong != null && targetSong.id != lastRecordedSongId) {
            lastRecordedSongId = targetSong.id
            onSongPlayed(targetSong)
        }
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        }
    }

    fun skipToNext() {
        val player = controller ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (player.mediaItemCount > 0) {
            // Loop back to start if repeat is enabled or standard behaviour
            player.seekToDefaultPosition(0)
        }
    }

    fun skipToPrevious() {
        val player = controller ?: return
        // If played more than 3 seconds, seek to start of current track, otherwise go to previous
        if (player.currentPosition > 3000L) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        val duration = player.duration
        val maxDuration = if (duration > 0) duration else Long.MAX_VALUE
        val safePosition = positionMs.coerceIn(0L, maxDuration)
        player.seekTo(safePosition)
        _uiState.update { it.copy(positionMs = safePosition) }
    }

    /**
     * Seek relative by deltaMs (e.g. -10000ms or +10000ms).
     * Guaranteed safe clamping: max(0, min(newPosition, duration)) with safe fallback if duration is unknown.
     */
    fun seekRelative(deltaMs: Long) {
        val player = controller ?: return
        val current = max(0L, player.currentPosition)
        val duration = player.duration
        val maxBound = if (duration > 0L) duration else (current + max(0L, deltaMs))
        val newPosition = max(0L, min(current + deltaMs, maxBound))
        player.seekTo(newPosition)
        _uiState.update { it.copy(positionMs = newPosition) }
    }

    fun toggleShuffle() {
        val player = controller ?: return
        val newShuffle = !player.shuffleModeEnabled
        player.shuffleModeEnabled = newShuffle
        _uiState.update { it.copy(isShuffleEnabled = newShuffle) }
    }

    fun cycleRepeatMode() {
        val player = controller ?: return
        val nextMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = nextMode
    }

    fun addToQueue(song: Song) {
        if (activeQueue.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        val player = controller ?: return
        activeQueue.add(song)
        player.addMediaItem(songToMediaItem(song))
        _uiState.update { it.copy(queue = activeQueue.toList()) }
    }

    fun playNext(song: Song) {
        if (activeQueue.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        val player = controller ?: return
        val nextIndex = (player.currentMediaItemIndex + 1).coerceIn(0, activeQueue.size)
        activeQueue.add(nextIndex, song)
        player.addMediaItem(nextIndex, songToMediaItem(song))
        _uiState.update { it.copy(queue = activeQueue.toList()) }
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        if (index in activeQueue.indices) {
            activeQueue.removeAt(index)
            player.removeMediaItem(index)
            _uiState.update { it.copy(queue = activeQueue.toList()) }
        }
    }

    fun jumpToQueueItem(index: Int) {
        val player = controller ?: return
        if (index in activeQueue.indices) {
            player.seekTo(index, 0L)
            player.play()
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun songToMediaItem(song: Song): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.albumArtUri)
            .build()

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.contentUri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun syncStateFromPlayer() {
        val player = controller ?: return
        val currentIndex = player.currentMediaItemIndex
        val currentSong = if (currentIndex in activeQueue.indices) activeQueue[currentIndex] else null
        val duration = max(0L, player.duration)
        val position = max(0L, player.currentPosition)

        val repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

        _uiState.update {
            it.copy(
                currentSong = currentSong,
                currentIndex = currentIndex,
                isPlaying = player.isPlaying,
                positionMs = position,
                durationMs = duration,
                isShuffleEnabled = player.shuffleModeEnabled,
                repeatMode = repeat,
                queue = activeQueue.toList()
            )
        }

        if (currentSong != null && currentSong.id != lastRecordedSongId) {
            lastRecordedSongId = currentSong.id
            onSongPlayed(currentSong)
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val player = controller
                if (player != null && player.isPlaying) {
                    val pos = max(0L, player.currentPosition)
                    val dur = max(0L, player.duration)
                    _uiState.update { it.copy(positionMs = pos, durationMs = dur) }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopProgressUpdate()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }
}
