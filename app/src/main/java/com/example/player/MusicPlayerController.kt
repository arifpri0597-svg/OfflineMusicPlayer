package com.example.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Stable
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.local.PlaybackStateEntity
import com.example.data.model.RepeatMode
import com.example.data.model.Song
import com.example.data.repository.PlaybackStateRepository
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class PendingPlayRequest(
    val songs: List<Song>,
    val startIndex: Int,
    val startPositionMs: Long,
    val autoPlay: Boolean
)

@Stable
class MusicPlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    initialPlayer: Player? = null,
    private val playbackStateRepository: PlaybackStateRepository? = null
) {
    private var controller: Player? = initialPlayer
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var progressJob: Job? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var activeQueue: MutableList<Song> = mutableListOf()
    private var immutableQueue: List<Song> = emptyList()
    private var pendingPlayRequest: PendingPlayRequest? = null

    private var isRestoredPausedState: Boolean = false
    private var restoredPositionFloorMs: Long? = null
    private var lastSeekPositionMs: Long? = null
    private var lastSeekTimestampMs: Long = 0L
    private var lastPeriodicSaveTimeMs: Long = 0L
    private var lastSavedPositionMs: Long = -1L
    private var lastSavedSongId: Long = -1L
    private var isApplyingRestoredPlayerState: Boolean = false
    @Volatile
    private var isUserInitiatedPlayback: Boolean = false
    @Volatile
    private var hasExternalIntentOverride: Boolean = false

    fun isInRestoredPausedState(): Boolean {
        return isRestoredPausedState && !_uiState.value.isPlaying
    }

    /**
     * Marks that an external File Manager Intent is actively being loaded so startup
     * or MediaStore library scan callbacks do not race with or overwrite it.
     */
    fun notifyExternalIntentPending() {
        hasExternalIntentOverride = true
        isRestoredPausedState = false
        restoredPositionFloorMs = null
    }

    fun reportExplicitPlaybackError(message: String) {
        _uiState.update {
            it.copy(
                errorMessage = message,
                isPlaying = false,
                isBuffering = false
            )
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                isRestoredPausedState = false
                restoredPositionFloorMs = null
                _uiState.update { it.copy(isPlaying = true) }
                startProgressUpdate()
                saveCurrentPlaybackState(isPlayingOverride = true, immediate = false)
            } else {
                stopProgressUpdate()
                val pos = controller?.let { resolveEffectivePosition(it) } ?: _uiState.value.positionMs
                _uiState.update { it.copy(isPlaying = false, positionMs = pos) }
                if (!isApplyingRestoredPlayerState) {
                    saveCurrentPlaybackState(
                        positionOverride = pos,
                        isPlayingOverride = false,
                        immediate = true
                    )
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isBuffering = playbackState == Player.STATE_BUFFERING
            _uiState.update { it.copy(isBuffering = isBuffering) }
            syncStateFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isApplyingRestoredPlayerState && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                isRestoredPausedState = false
                restoredPositionFloorMs = null
                lastSeekPositionMs = null
            }
            syncStateFromPlayer()
            if (!isApplyingRestoredPlayerState) {
                saveCurrentPlaybackState(immediate = true)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (isApplyingRestoredPlayerState) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                val seekPos = max(0L, newPosition.positionMs)
                lastSeekPositionMs = seekPos
                lastSeekTimestampMs = System.currentTimeMillis()
                if (isRestoredPausedState) {
                    restoredPositionFloorMs = seekPos
                }
                _uiState.update { it.copy(positionMs = seekPos) }
                saveCurrentPlaybackState(positionOverride = seekPos, immediate = true)
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val mappedRepeat = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
            _uiState.update { it.copy(repeatMode = mappedRepeat) }
            if (!isApplyingRestoredPlayerState) {
                saveCurrentPlaybackState(immediate = false)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _uiState.update { it.copy(isShuffleEnabled = shuffleModeEnabled) }
            if (!isApplyingRestoredPlayerState) {
                saveCurrentPlaybackState(immediate = false)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            val speed = PlaybackStateRepository.clampSpeed(playbackParameters.speed)
            _uiState.update { it.copy(playbackSpeed = speed) }
            if (!isApplyingRestoredPlayerState) {
                saveCurrentPlaybackState(immediate = false)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // NEVER show "Source error" during automatic startup restoration or when not explicitly user-initiated
            if (!isUserInitiatedPlayback || isRestoredPausedState || isApplyingRestoredPlayerState) {
                isRestoredPausedState = false
                restoredPositionFloorMs = null
                val currentUriStr = _uiState.value.currentSong?.contentUri?.toString() ?: ""
                if (PlaybackStateRepository.isExternalUriString(currentUriStr)) {
                    activeQueue.clear()
                    immutableQueue = emptyList()
                    _uiState.update {
                        it.copy(
                            currentSong = null,
                            queue = emptyList(),
                            currentIndex = -1,
                            isPlaying = false,
                            isBuffering = false,
                            positionMs = 0L,
                            errorMessage = null
                        )
                    }
                    scope.launch {
                        playbackStateRepository?.clearPlaybackState()
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = false,
                            errorMessage = null
                        )
                    }
                }
                return
            }

            val msg = error.message ?: "Playback error encountered"
            _uiState.update { it.copy(errorMessage = msg, isPlaying = false, isBuffering = false) }
            val player = controller ?: return
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.play()
                _uiState.update { it.copy(errorMessage = msg) }
            }
        }
    }

    @Volatile
    private var isControllerConnectionRequested: Boolean = false

    private val serviceTaskRemovedListener: () -> Unit = {
        onServiceTaskRemoved()
    }

    init {
        PlaybackService.registerTaskRemovedListener(serviceTaskRemovedListener)
        if (initialPlayer != null) {
            initialPlayer.addListener(playerListener)
            syncStateFromPlayer()
        }
    }

    internal fun onServiceTaskRemoved() {
        stopProgressUpdate()
        pendingPlayRequest = null
        val currentPos = controller?.let { resolveEffectivePosition(it) } ?: _uiState.value.positionMs
        isUserInitiatedPlayback = false
        isRestoredPausedState = _uiState.value.currentSong != null
        restoredPositionFloorMs = currentPos
        controller?.removeListener(playerListener)
        controllerFuture?.let {
            try {
                MediaController.releaseFuture(it)
            } catch (_: Exception) {
            }
            controllerFuture = null
        }
        controller = null
        isControllerConnectionRequested = false
        _uiState.update {
            it.copy(
                isPlaying = false,
                isBuffering = false,
                positionMs = currentPos
            )
        }
    }

    /**
     * Connects to [PlaybackService] only if the service is already actively running in the background.
     * Prevents starting PlaybackService or creating a stale MediaSession when the user simply opens the app.
     */
    fun connectIfServiceActive() {
        if (PlaybackService.isServiceRunning) {
            ensureControllerInitialized()
        }
    }

    private fun ensurePlaybackServiceStarted() {
        try {
            context.startService(android.content.Intent(context, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    /**
     * Ensures the Media3 MediaController connection to PlaybackService is initialized.
     * Safe and idempotent to call multiple times; avoids starting PlaybackService before
     * first-launch permissions are resolved.
     */
    fun ensureControllerInitialized() {
        if (controller != null || isControllerConnectionRequested) return
        synchronized(this) {
            if (controller != null || isControllerConnectionRequested) return
            isControllerConnectionRequested = true
        }
        try {
            initializeMediaController()
        } catch (e: Exception) {
            isControllerConnectionRequested = false
            e.printStackTrace()
        }
    }

    internal fun setPlayerForTesting(testPlayer: Player) {
        controller?.removeListener(playerListener)
        controller = testPlayer
        isControllerConnectionRequested = true
        testPlayer.addListener(playerListener)
        val pending = pendingPlayRequest
        if (pending != null) {
            pendingPlayRequest = null
            playQueue(
                songs = pending.songs,
                startIndex = pending.startIndex,
                startPositionMs = pending.startPositionMs,
                autoPlay = pending.autoPlay
            )
        } else if (testPlayer.mediaItemCount == 0 && _uiState.value.currentSong != null && isRestoredPausedState) {
            applyRestoredStateToPlayer(testPlayer)
        } else {
            syncStateFromPlayer()
        }
    }

    private fun initializeMediaController() {
        ensurePlaybackServiceStarted()
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    onServiceTaskRemoved()
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val connectedPlayer = future.get()
                controller = connectedPlayer
                connectedPlayer?.addListener(playerListener)
                val pending = pendingPlayRequest
                if (pending != null) {
                    pendingPlayRequest = null
                    playQueue(
                        songs = pending.songs,
                        startIndex = pending.startIndex,
                        startPositionMs = pending.startPositionMs,
                        autoPlay = pending.autoPlay
                    )
                } else if (connectedPlayer != null) {
                    if (connectedPlayer.isPlaying) {
                        syncStateFromPlayer()
                        startProgressUpdate()
                    } else if (connectedPlayer.mediaItemCount > 0) {
                        // If the service holds a paused external URI that is no longer accessible,
                        // clear it silently rather than leaving a broken source loaded.
                        val currentItem = connectedPlayer.currentMediaItem
                        val uriStr = currentItem?.localConfiguration?.uri?.toString()
                            ?: currentItem?.mediaMetadata?.extras?.getString("contentUri")
                            ?: ""
                        val repo = playbackStateRepository
                        if (repo != null && PlaybackStateRepository.isExternalUriString(uriStr)) {
                            val uri = try { Uri.parse(uriStr) } catch (_: Exception) { null }
                            val isValid = uri != null && repo.isExternalAudioUriValidAndAccessible(
                                uri = uri,
                                fallbackTitle = currentItem?.mediaMetadata?.title?.toString()
                            )
                            if (!isValid) {
                                connectedPlayer.clearMediaItems()
                                activeQueue.clear()
                                immutableQueue = emptyList()
                                isRestoredPausedState = false
                                restoredPositionFloorMs = null
                                _uiState.update {
                                    it.copy(
                                        currentSong = null,
                                        queue = emptyList(),
                                        currentIndex = -1,
                                        positionMs = 0L,
                                        isPlaying = false,
                                        errorMessage = null
                                    )
                                }
                                scope.launch { repo.clearPlaybackState() }
                                return@addListener
                            }
                        }

                        if (!isRestoredPausedState) {
                            syncStateFromPlayer()
                        } else if (_uiState.value.currentSong != null) {
                            applyRestoredStateToPlayer(connectedPlayer)
                        } else {
                            syncStateFromPlayer()
                        }
                    } else if (_uiState.value.currentSong != null && isRestoredPausedState) {
                        applyRestoredStateToPlayer(connectedPlayer)
                    } else {
                        syncStateFromPlayer()
                    }
                }
            } catch (e: Exception) {
                isControllerConnectionRequested = false
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Restores the last saved playback state (track, queue, index, playback speed, and clamped position)
     * from [PlaybackStateRepository] without automatically starting playback.
     */
    suspend fun restorePlaybackState(librarySongs: List<Song> = emptyList()): Boolean {
        val repo = playbackStateRepository ?: return false
        // Do not overwrite an actively playing track or an incoming external File Manager intent
        if (_uiState.value.isPlaying || hasExternalIntentOverride || pendingPlayRequest != null) {
            return false
        }
        val isPlayerCurrentlyPlaying = kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            controller?.isPlaying == true
        }
        if (isPlayerCurrentlyPlaying) {
            return false
        }

        val savedState = repo.getLastPlaybackState() ?: return false
        val session = repo.resolveRestoredSession(savedState, librarySongs)
        if (session == null) {
            // If the saved state was an invalid/deleted external URI, clear any stale restored state in UI
            if (isRestoredPausedState) {
                isRestoredPausedState = false
                restoredPositionFloorMs = null
                activeQueue.clear()
                immutableQueue = emptyList()
                _uiState.update {
                    it.copy(
                        currentSong = null,
                        queue = emptyList(),
                        currentIndex = -1,
                        positionMs = 0L,
                        isPlaying = false,
                        errorMessage = null
                    )
                }
            }
            return false
        }

        // Re-check after suspend point in case user started playing or external intent arrived
        if (_uiState.value.isPlaying || hasExternalIntentOverride || pendingPlayRequest != null) {
            return false
        }

        // If the user already manually switched to a different track in this session, don't overwrite it
        val existingSong = _uiState.value.currentSong
        if (existingSong != null && !isRestoredPausedState && existingSong.id != session.currentSong.id) {
            return false
        }

        // Preserve any newer seek position performed while already in the restored session
        val effectivePos = if (isRestoredPausedState && existingSong?.id == session.currentSong.id) {
            val currentPos = restoredPositionFloorMs ?: _uiState.value.positionMs
            PlaybackStateRepository.clampPosition(currentPos, session.durationMs)
        } else {
            session.positionMs
        }

        // Deduplicate and validate queue items
        val cleanQueue = session.queue
            .filter { it.contentUri.toString().isNotBlank() }
            .distinctBy { "${it.id}_${it.contentUri}" }
            .ifEmpty { listOf(session.currentSong) }

        val cleanIndex = cleanQueue.indexOfFirst { it.id == session.currentSong.id }
            .let { if (it != -1) it else 0 }

        activeQueue = cleanQueue.toMutableList()
        immutableQueue = activeQueue.toList()
        isRestoredPausedState = true
        isUserInitiatedPlayback = false
        restoredPositionFloorMs = effectivePos
        lastSavedSongId = session.currentSong.id
        lastSavedPositionMs = effectivePos

        _uiState.update {
            it.copy(
                currentSong = session.currentSong,
                queue = immutableQueue,
                currentIndex = cleanIndex,
                positionMs = effectivePos,
                durationMs = session.durationMs,
                isPlaying = false,
                isShuffleEnabled = session.shuffleEnabled,
                repeatMode = session.repeatMode,
                playbackSpeed = session.playbackSpeed,
                errorMessage = null
            )
        }

        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            val player = controller
            if (player != null && !player.isPlaying) {
                applyRestoredStateToPlayer(player)
            } else if (player == null && PlaybackService.isServiceRunning) {
                ensureControllerInitialized()
            }
        }
        return true
    }

    private fun applyRestoredStateToPlayer(player: Player) {
        val state = _uiState.value
        val currentSong = state.currentSong ?: return
        val queueToLoad = (if (immutableQueue.isNotEmpty()) immutableQueue else listOf(currentSong))
            .filter { it.contentUri.toString().isNotBlank() }
            .distinctBy { "${it.id}_${it.contentUri}" }
        if (queueToLoad.isEmpty()) return

        val targetIndex = state.currentIndex.coerceIn(0, queueToLoad.size - 1)
        val targetPos = PlaybackStateRepository.clampPosition(
            restoredPositionFloorMs ?: state.positionMs,
            state.durationMs
        )

        isApplyingRestoredPlayerState = true
        try {
            player.playWhenReady = false
            player.setPlaybackSpeed(PlaybackStateRepository.clampSpeed(state.playbackSpeed))
            player.shuffleModeEnabled = state.isShuffleEnabled
            player.repeatMode = when (state.repeatMode) {
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            }

            if (!isSamePlayerMediaItems(player, queueToLoad)) {
                val mediaItems = ArrayList<MediaItem>(queueToLoad.size)
                for (song in queueToLoad) {
                    mediaItems.add(songToMediaItem(song))
                }
                player.setMediaItems(mediaItems, targetIndex, targetPos)
            }
            player.seekTo(targetIndex, targetPos)
            // Do NOT call player.prepare() during automatic startup restoration while paused;
            // preparing only when the user initiates playback prevents startup "Source error".
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isApplyingRestoredPlayerState = false
        }
    }

    private fun isSamePlayerMediaItems(player: Player, songs: List<Song>): Boolean {
        if (player.mediaItemCount != songs.size) return false
        for (i in songs.indices) {
            val item = player.getMediaItemAt(i)
            if (item.mediaId != songs[i].id.toString()) return false
            val itemUri = item.localConfiguration?.uri
                ?: item.mediaMetadata.extras?.getString("contentUri")?.let { Uri.parse(it) }
            if (itemUri != null && itemUri != songs[i].contentUri) return false
        }
        return true
    }

    private fun isSameQueue(songs: List<Song>): Boolean {
        if (activeQueue.size != songs.size) return false
        for (i in songs.indices) {
            if (activeQueue[i].id != songs[i].id || activeQueue[i].contentUri != songs[i].contentUri) {
                return false
            }
        }
        return true
    }

    fun playQueue(
        songs: List<Song>,
        startIndex: Int = 0,
        startPositionMs: Long? = null,
        autoPlay: Boolean = true
    ) {
        val validSongs = songs.filter { it.contentUri.toString().isNotBlank() }
        if (validSongs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, validSongs.size - 1)
        val targetSong = validSongs[safeIndex]

        // Clear external intent override if user switched to a MediaStore song
        if (!PlaybackStateRepository.isExternalUriString(targetSong.contentUri.toString())) {
            hasExternalIntentOverride = false
        }

        // If re-selecting the currently loaded/restored song and no explicit startPositionMs was given,
        // preserve its saved/current position rather than resetting to 0:00.
        val currentState = _uiState.value
        val resolvedPositionMs = when {
            startPositionMs != null -> PlaybackStateRepository.clampPosition(startPositionMs, targetSong.durationMs)
            currentState.currentSong?.id == targetSong.id && currentState.positionMs > 0L -> {
                PlaybackStateRepository.clampPosition(currentState.positionMs, targetSong.durationMs)
            }
            else -> 0L
        }

        if (autoPlay) {
            isUserInitiatedPlayback = true
            isRestoredPausedState = false
            restoredPositionFloorMs = null
        } else {
            isUserInitiatedPlayback = false
            isRestoredPausedState = true
            restoredPositionFloorMs = resolvedPositionMs
        }
        if (resolvedPositionMs > 0L) {
            lastSeekPositionMs = resolvedPositionMs
            lastSeekTimestampMs = System.currentTimeMillis()
        } else {
            lastSeekPositionMs = null
        }

        val player = controller
        if (player == null) {
            pendingPlayRequest = PendingPlayRequest(
                songs = validSongs,
                startIndex = safeIndex,
                startPositionMs = resolvedPositionMs,
                autoPlay = autoPlay
            )
            activeQueue = validSongs.toMutableList()
            immutableQueue = activeQueue.toList()
            _uiState.update {
                it.copy(
                    queue = immutableQueue,
                    currentIndex = safeIndex,
                    currentSong = targetSong,
                    positionMs = resolvedPositionMs,
                    durationMs = if (targetSong.durationMs > 0L) targetSong.durationMs else it.durationMs,
                    isPlaying = autoPlay,
                    errorMessage = null
                )
            }
            saveCurrentPlaybackState(
                positionOverride = resolvedPositionMs,
                isPlayingOverride = autoPlay,
                immediate = true
            )
            if (autoPlay || PlaybackService.isServiceRunning) {
                ensureControllerInitialized()
            }
            return
        }

        player.setPlaybackSpeed(PlaybackStateRepository.clampSpeed(currentState.playbackSpeed))

        if (player.mediaItemCount == validSongs.size && isSameQueue(validSongs) && player.playbackState != Player.STATE_IDLE) {
            player.playWhenReady = autoPlay
            player.seekTo(safeIndex, resolvedPositionMs)
            if (autoPlay) {
                player.play()
            } else {
                player.pause()
            }
        } else {
            activeQueue = validSongs.toMutableList()
            immutableQueue = activeQueue.toList()
            val mediaItems = ArrayList<MediaItem>(validSongs.size)
            for (song in validSongs) {
                mediaItems.add(songToMediaItem(song))
            }

            player.playWhenReady = autoPlay
            player.setMediaItems(mediaItems, safeIndex, resolvedPositionMs)
            player.seekTo(safeIndex, resolvedPositionMs)
            if (autoPlay) {
                player.prepare()
                player.play()
            }
        }

        _uiState.update {
            it.copy(
                queue = immutableQueue,
                currentIndex = safeIndex,
                currentSong = targetSong,
                positionMs = resolvedPositionMs,
                durationMs = if (targetSong.durationMs > 0L) targetSong.durationMs else it.durationMs,
                isPlaying = autoPlay,
                errorMessage = null
            )
        }
        saveCurrentPlaybackState(
            positionOverride = resolvedPositionMs,
            isPlayingOverride = autoPlay,
            immediate = true
        )
    }

    fun togglePlayPause() {
        val currentState = _uiState.value
        val player = controller
        if (player == null) {
            val currentSong = currentState.currentSong ?: return
            val queueToPlay = if (immutableQueue.isNotEmpty()) immutableQueue else listOf(currentSong)
            val willPlay = !currentState.isPlaying
            isUserInitiatedPlayback = willPlay
            pendingPlayRequest = PendingPlayRequest(
                songs = queueToPlay,
                startIndex = currentState.currentIndex.coerceIn(0, queueToPlay.size - 1),
                startPositionMs = currentState.positionMs,
                autoPlay = willPlay
            )
            isRestoredPausedState = false
            restoredPositionFloorMs = null
            _uiState.update { it.copy(isPlaying = willPlay) }
            saveCurrentPlaybackState(
                positionOverride = currentState.positionMs,
                isPlayingOverride = willPlay,
                immediate = true
            )
            ensureControllerInitialized()
            return
        }

        if (player.isPlaying) {
            isRestoredPausedState = false
            restoredPositionFloorMs = null
            val pausedPos = resolveEffectivePosition(player)
            player.pause()
            _uiState.update { it.copy(isPlaying = false, positionMs = pausedPos) }
            saveCurrentPlaybackState(
                positionOverride = pausedPos,
                isPlayingOverride = false,
                immediate = true
            )
        } else {
            val currentSong = currentState.currentSong
            if (currentSong != null && PlaybackStateRepository.isExternalUriString(currentSong.contentUri.toString())) {
                val repo = playbackStateRepository
                if (repo != null && !repo.isExternalAudioUriValidAndAccessible(
                        uri = currentSong.contentUri,
                        fallbackTitle = currentSong.title
                    )
                ) {
                    reportExplicitPlaybackError("Unable to play file: audio source is no longer available")
                    scope.launch { repo.clearPlaybackState() }
                    return
                }
            }

            isUserInitiatedPlayback = true
            val resumePos = restoredPositionFloorMs ?: currentState.positionMs
            val wasRestored = isRestoredPausedState
            isRestoredPausedState = false
            restoredPositionFloorMs = null

            player.setPlaybackSpeed(PlaybackStateRepository.clampSpeed(currentState.playbackSpeed))

            if (player.mediaItemCount == 0 && currentState.currentSong != null) {
                val queueToLoad = if (immutableQueue.isNotEmpty()) immutableQueue else listOf(currentState.currentSong)
                val idx = currentState.currentIndex.coerceIn(0, queueToLoad.size - 1)
                val mediaItems = queueToLoad.map { songToMediaItem(it) }
                player.setMediaItems(mediaItems, idx, resumePos)
                player.seekTo(idx, resumePos)
                player.prepare()
            } else {
                if (wasRestored || (resumePos > 0L && abs(player.currentPosition - resumePos) > 1000L)) {
                    val idx = player.currentMediaItemIndex.coerceAtLeast(0)
                    lastSeekPositionMs = resumePos
                    lastSeekTimestampMs = System.currentTimeMillis()
                    player.seekTo(idx, resumePos)
                }
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
            }
            player.play()
            _uiState.update { it.copy(isPlaying = true, positionMs = resumePos, errorMessage = null) }
            saveCurrentPlaybackState(
                positionOverride = resumePos,
                isPlayingOverride = true,
                immediate = true
            )
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = PlaybackStateRepository.clampSpeed(speed)
        controller?.setPlaybackSpeed(clamped)
        _uiState.update { it.copy(playbackSpeed = clamped) }
        saveCurrentPlaybackState(immediate = true)
    }

    fun skipToNext() {
        val player = controller
        if (player == null) {
            if (immutableQueue.isEmpty()) return
            val nextIdx = if (_uiState.value.currentIndex + 1 < immutableQueue.size) {
                _uiState.value.currentIndex + 1
            } else {
                0
            }
            playQueue(
                songs = immutableQueue,
                startIndex = nextIdx,
                startPositionMs = 0L,
                autoPlay = _uiState.value.isPlaying
            )
            return
        }
        isUserInitiatedPlayback = true
        isRestoredPausedState = false
        restoredPositionFloorMs = null
        lastSeekPositionMs = null
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (player.mediaItemCount > 0) {
            player.seekToDefaultPosition(0)
        }
        if (player.playbackState == Player.STATE_IDLE && player.playWhenReady) {
            player.prepare()
        }
        syncStateFromPlayer()
        saveCurrentPlaybackState(positionOverride = 0L, immediate = true)
    }

    fun skipToPrevious() {
        val player = controller
        if (player == null) {
            if (immutableQueue.isEmpty()) return
            if (_uiState.value.positionMs > 3000L) {
                seekTo(0L)
            } else {
                val prevIdx = (_uiState.value.currentIndex - 1).coerceAtLeast(0)
                playQueue(
                    songs = immutableQueue,
                    startIndex = prevIdx,
                    startPositionMs = 0L,
                    autoPlay = _uiState.value.isPlaying
                )
            }
            return
        }
        isUserInitiatedPlayback = true
        isRestoredPausedState = false
        restoredPositionFloorMs = null
        lastSeekPositionMs = null
        if (player.currentPosition > 3000L) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }
        if (player.playbackState == Player.STATE_IDLE && player.playWhenReady) {
            player.prepare()
        }
        syncStateFromPlayer()
        saveCurrentPlaybackState(positionOverride = 0L, immediate = true)
    }

    fun seekTo(positionMs: Long) {
        val player = controller
        val playerDuration = player?.duration ?: 0L
        val fallbackDuration = _uiState.value.currentSong?.durationMs ?: _uiState.value.durationMs
        val effectiveDuration = if (playerDuration > 0L) playerDuration else max(0L, fallbackDuration)
        val safePosition = PlaybackStateRepository.clampPosition(positionMs, effectiveDuration)

        lastSeekPositionMs = safePosition
        lastSeekTimestampMs = System.currentTimeMillis()
        if (isRestoredPausedState) {
            restoredPositionFloorMs = safePosition
        }

        player?.seekTo(safePosition)
        _uiState.update {
            it.copy(
                positionMs = safePosition,
                durationMs = if (effectiveDuration > 0L) effectiveDuration else it.durationMs
            )
        }
        saveCurrentPlaybackState(positionOverride = safePosition, immediate = true)
    }

    /**
     * Seek relative by deltaMs (e.g. -10000ms or +10000ms).
     * Guaranteed safe clamping: max(0, min(newPosition, duration)) with safe fallback if duration is unknown.
     */
    fun seekRelative(deltaMs: Long) {
        val player = controller
        val current = player?.let { resolveEffectivePosition(it) } ?: max(0L, _uiState.value.positionMs)
        val playerDuration = player?.duration ?: 0L
        val fallbackDuration = _uiState.value.currentSong?.durationMs ?: _uiState.value.durationMs
        val duration = if (playerDuration > 0L) playerDuration else fallbackDuration
        val maxBound = if (duration > 0L) duration else (current + max(0L, deltaMs))
        val newPosition = max(0L, min(current + deltaMs, maxBound))

        lastSeekPositionMs = newPosition
        lastSeekTimestampMs = System.currentTimeMillis()
        if (isRestoredPausedState) {
            restoredPositionFloorMs = newPosition
        }

        player?.seekTo(newPosition)
        _uiState.update { it.copy(positionMs = newPosition) }
        saveCurrentPlaybackState(positionOverride = newPosition, immediate = true)
    }

    fun toggleShuffle() {
        val player = controller
        val newShuffle = if (player != null) {
            val next = !player.shuffleModeEnabled
            player.shuffleModeEnabled = next
            next
        } else {
            !_uiState.value.isShuffleEnabled
        }
        _uiState.update { it.copy(isShuffleEnabled = newShuffle) }
        saveCurrentPlaybackState(immediate = true)
    }

    fun cycleRepeatMode() {
        val player = controller
        if (player != null) {
            val nextMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            player.repeatMode = nextMode
            val mappedRepeat = when (nextMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
            _uiState.update { it.copy(repeatMode = mappedRepeat) }
        } else {
            val nextRepeat = when (_uiState.value.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            _uiState.update { it.copy(repeatMode = nextRepeat) }
        }
        saveCurrentPlaybackState(immediate = true)
    }

    fun addToQueue(song: Song) {
        if (activeQueue.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        activeQueue.add(song)
        immutableQueue = activeQueue.toList()
        controller?.addMediaItem(songToMediaItem(song))
        _uiState.update { it.copy(queue = immutableQueue) }
        saveCurrentPlaybackState(immediate = true)
    }

    fun playNext(song: Song) {
        if (activeQueue.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        val player = controller
        val currentIdx = player?.currentMediaItemIndex ?: _uiState.value.currentIndex.coerceAtLeast(0)
        val nextIndex = (currentIdx + 1).coerceIn(0, activeQueue.size)
        activeQueue.add(nextIndex, song)
        immutableQueue = activeQueue.toList()
        player?.addMediaItem(nextIndex, songToMediaItem(song))
        _uiState.update { it.copy(queue = immutableQueue) }
        saveCurrentPlaybackState(immediate = true)
    }

    fun removeFromQueue(index: Int) {
        val player = controller
        if (player == null) {
            if (index in activeQueue.indices) {
                activeQueue.removeAt(index)
                immutableQueue = activeQueue.toList()
                if (immutableQueue.isEmpty()) {
                    isRestoredPausedState = false
                    restoredPositionFloorMs = null
                    _uiState.update {
                        it.copy(
                            queue = emptyList(),
                            currentSong = null,
                            currentIndex = 0,
                            isPlaying = false,
                            positionMs = 0L
                        )
                    }
                    scope.launch {
                        playbackStateRepository?.clearPlaybackState()
                    }
                } else {
                    val newIdx = _uiState.value.currentIndex.coerceIn(0, immutableQueue.size - 1)
                    val newSong = immutableQueue[newIdx]
                    _uiState.update {
                        it.copy(
                            queue = immutableQueue,
                            currentIndex = newIdx,
                            currentSong = newSong,
                            durationMs = newSong.durationMs
                        )
                    }
                    saveCurrentPlaybackState(immediate = true)
                }
            }
            return
        }
        if (index in activeQueue.indices) {
            activeQueue.removeAt(index)
            immutableQueue = activeQueue.toList()
            player.removeMediaItem(index)
            if (immutableQueue.isEmpty()) {
                isRestoredPausedState = false
                restoredPositionFloorMs = null
                _uiState.update {
                    it.copy(
                        queue = emptyList(),
                        currentSong = null,
                        currentIndex = 0,
                        isPlaying = false,
                        positionMs = 0L
                    )
                }
                scope.launch {
                    playbackStateRepository?.clearPlaybackState()
                }
            } else {
                syncStateFromPlayer()
                saveCurrentPlaybackState(immediate = true)
            }
        }
    }

    fun jumpToQueueItem(index: Int) {
        val player = controller
        if (player == null) {
            if (index in activeQueue.indices) {
                playQueue(
                    songs = immutableQueue,
                    startIndex = index,
                    startPositionMs = 0L,
                    autoPlay = true
                )
            }
            return
        }
        if (index in activeQueue.indices) {
            isUserInitiatedPlayback = true
            isRestoredPausedState = false
            restoredPositionFloorMs = null
            lastSeekPositionMs = null
            player.seekTo(index, 0L)
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
            syncStateFromPlayer()
            saveCurrentPlaybackState(positionOverride = 0L, isPlayingOverride = true, immediate = true)
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Immediately persists the current playback state to [PlaybackStateRepository].
     */
    fun saveCurrentPlaybackState(
        positionOverride: Long? = null,
        isPlayingOverride: Boolean? = null,
        immediate: Boolean = true
    ) {
        val repo = playbackStateRepository ?: return
        val state = _uiState.value
        val currentSong = state.currentSong ?: return

        val player = controller
        val playerDur = player?.duration ?: 0L
        val effectiveDuration = when {
            playerDur > 0L -> playerDur
            currentSong.durationMs > 0L -> currentSong.durationMs
            state.durationMs > 0L -> state.durationMs
            else -> 0L
        }

        val rawPosition = positionOverride
            ?: player?.let { resolveEffectivePosition(it) }
            ?: state.positionMs
        val clampedPosition = PlaybackStateRepository.clampPosition(rawPosition, effectiveDuration)
        val isPlayingNow = isPlayingOverride ?: player?.isPlaying ?: state.isPlaying

        val currentQueue = immutableQueue
        val queueIds = if (currentQueue.isNotEmpty()) {
            if (currentQueue === cachedQueueForCsv && cachedQueueCsv.isNotEmpty()) {
                cachedQueueCsv
            } else {
                val built = currentQueue.joinToString(",") { it.id.toString() }
                cachedQueueForCsv = currentQueue
                cachedQueueCsv = built
                built
            }
        } else {
            currentSong.id.toString()
        }

        val uriString = currentSong.contentUri.toString()
        val isExternal = PlaybackStateRepository.isExternalUriString(uriString) ||
            currentSong.folderName == "External"

        val entity = PlaybackStateEntity(
            id = PlaybackStateEntity.SINGLETON_ID,
            songId = currentSong.id,
            contentUriString = uriString,
            title = currentSong.title,
            artist = currentSong.artist,
            album = currentSong.album,
            albumArtUriString = currentSong.albumArtUri?.toString(),
            positionMs = clampedPosition,
            durationMs = effectiveDuration,
            queueSongIdsCsv = queueIds,
            currentIndex = state.currentIndex.coerceAtLeast(0),
            isPlaying = isPlayingNow,
            shuffleEnabled = state.isShuffleEnabled,
            repeatMode = state.repeatMode.name,
            playbackSpeed = PlaybackStateRepository.clampSpeed(state.playbackSpeed),
            isExternal = isExternal,
            lastUpdated = System.currentTimeMillis()
        )

        lastSavedSongId = currentSong.id
        lastSavedPositionMs = clampedPosition

        if (immediate) {
            repo.savePlaybackStateImmediate(entity)
        } else {
            scope.launch(Dispatchers.IO) {
                repo.savePlaybackState(entity)
            }
        }
    }

    private fun resolveEffectivePosition(player: Player): Long {
        val now = System.currentTimeMillis()
        val recentSeek = lastSeekPositionMs
        if (recentSeek != null && (now - lastSeekTimestampMs) < 600L) {
            return recentSeek
        }
        val restoredFloor = restoredPositionFloorMs
        if (isRestoredPausedState && !player.isPlaying && restoredFloor != null) {
            return restoredFloor
        }
        return max(0L, player.currentPosition)
    }

    @Volatile
    private var cachedQueueForCsv: List<Song> = emptyList()
    @Volatile
    private var cachedQueueCsv: String = ""

    private val mediaItemCache = java.util.concurrent.ConcurrentHashMap<Long, MediaItem>()

    fun prewarmMediaItems(songs: List<Song>) {
        if (mediaItemCache.size > songs.size * 2 + 64) {
            mediaItemCache.clear()
        }
        for (song in songs) {
            songToMediaItem(song)
        }
    }

    private fun songToMediaItem(song: Song): MediaItem {
        mediaItemCache[song.id]?.let { cached ->
            if (cached.localConfiguration?.uri == song.contentUri) {
                return cached
            }
        }
        val isExternal = PlaybackStateRepository.isExternalUriString(song.contentUri.toString()) ||
            song.folderName == "External"
        val extras = Bundle().apply {
            putLong("durationMs", song.durationMs)
            putInt("trackNumber", song.trackNumber)
            putInt("year", song.year)
            putLong("sizeBytes", song.sizeBytes)
            putLong("dateAdded", song.dateAdded)
            putString("folderName", song.folderName)
            putString("path", song.path)
            putString("contentUri", song.contentUri.toString())
            putBoolean("isExternal", isExternal)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.albumArtUri)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.contentUri)
            .setMediaMetadata(metadata)
            .build()
            .also { mediaItemCache[song.id] = it }
    }

    private fun mediaItemToSong(item: MediaItem): Song? {
        val id = item.mediaId.toLongOrNull() ?: 0L
        val extras = item.mediaMetadata.extras
        val uri = item.localConfiguration?.uri
            ?: extras?.getString("contentUri")?.let { Uri.parse(it) }
            ?: return null
        val durationMs = extras?.getLong("durationMs", 0L) ?: 0L
        return Song(
            id = id,
            title = item.mediaMetadata.title?.toString()?.ifBlank { "Unknown Title" } ?: "Unknown Title",
            artist = item.mediaMetadata.artist?.toString()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist",
            album = item.mediaMetadata.albumTitle?.toString()?.ifBlank { "Unknown Album" } ?: "Unknown Album",
            durationMs = max(0L, durationMs),
            contentUri = uri,
            albumArtUri = item.mediaMetadata.artworkUri,
            trackNumber = extras?.getInt("trackNumber", 0) ?: 0,
            year = extras?.getInt("year", 0) ?: 0,
            sizeBytes = extras?.getLong("sizeBytes", 0L) ?: 0L,
            dateAdded = extras?.getLong("dateAdded", 0L) ?: 0L,
            folderName = extras?.getString("folderName", "") ?: "",
            path = extras?.getString("path", "") ?: ""
        )
    }

    private fun syncStateFromPlayer() {
        val player = controller ?: return
        if (player.mediaItemCount == 0 && isRestoredPausedState && _uiState.value.currentSong != null) {
            return
        }

        // Reconstruct queue from player if controller was recreated while PlaybackService kept running
        if (immutableQueue.isEmpty() && player.mediaItemCount > 0) {
            val reconstructed = ArrayList<Song>(player.mediaItemCount)
            for (i in 0 until player.mediaItemCount) {
                mediaItemToSong(player.getMediaItemAt(i))?.let { reconstructed.add(it) }
            }
            if (reconstructed.isNotEmpty()) {
                activeQueue = reconstructed.toMutableList()
                immutableQueue = activeQueue.toList()
            }
        }

        val currentIndex = player.currentMediaItemIndex
        val currentSong = if (currentIndex in immutableQueue.indices) {
            immutableQueue[currentIndex]
        } else {
            player.currentMediaItem?.let { mediaItemToSong(it) } ?: _uiState.value.currentSong
        }
        val rawPlayerDuration = player.duration
        val duration = when {
            rawPlayerDuration > 0L -> rawPlayerDuration
            currentSong != null && currentSong.durationMs > 0L -> currentSong.durationMs
            else -> _uiState.value.durationMs
        }
        val position = PlaybackStateRepository.clampPosition(
            resolveEffectivePosition(player),
            duration
        )

        val repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

        val speed = try {
            val rawSpeed = player.playbackParameters?.speed ?: _uiState.value.playbackSpeed
            if (rawSpeed > 0f) PlaybackStateRepository.clampSpeed(rawSpeed) else _uiState.value.playbackSpeed
        } catch (_: Exception) {
            _uiState.value.playbackSpeed
        }

        _uiState.update {
            it.copy(
                currentSong = currentSong,
                currentIndex = if (currentIndex >= 0) currentIndex else it.currentIndex,
                isPlaying = player.isPlaying,
                positionMs = position,
                durationMs = duration,
                isShuffleEnabled = player.shuffleModeEnabled,
                repeatMode = repeat,
                playbackSpeed = speed,
                queue = immutableQueue
            )
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val player = controller
                if (player != null && player.isPlaying) {
                    val pos = resolveEffectivePosition(player)
                    val rawDur = player.duration
                    val dur = if (rawDur > 0L) rawDur else _uiState.value.durationMs
                    _uiState.update { current ->
                        if (current.positionMs / 100L == pos / 100L && current.durationMs == dur) {
                            current
                        } else {
                            current.copy(positionMs = pos, durationMs = dur)
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastPeriodicSaveTimeMs >= 3000L && abs(pos - lastSavedPositionMs) >= 1000L) {
                        lastPeriodicSaveTimeMs = now
                        saveCurrentPlaybackState(
                            positionOverride = pos,
                            isPlayingOverride = true,
                            immediate = false
                        )
                    }
                }
                delay(200)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * Stops active playback, clears the active MediaController/MediaSession connection,
     * and stops PlaybackService when the task is removed, while preserving saved position and speed.
     */
    fun stopAndReleaseOnTaskRemoved() {
        saveCurrentPlaybackState(isPlayingOverride = false, immediate = true)
        stopProgressUpdate()
        pendingPlayRequest = null
        PlaybackService.unregisterTaskRemovedListener(serviceTaskRemovedListener)
        val player = controller
        if (player != null) {
            try {
                player.removeListener(playerListener)
                player.playWhenReady = false
                player.pause()
                player.stop()
                player.clearMediaItems()
            } catch (_: Exception) {
            }
        }
        controllerFuture?.let {
            try {
                MediaController.releaseFuture(it)
            } catch (_: Exception) {
            }
            controllerFuture = null
        } ?: run {
            try {
                player?.release()
            } catch (_: Exception) {
            }
        }
        controller = null
        isControllerConnectionRequested = false
        _uiState.update {
            it.copy(
                isPlaying = false,
                isBuffering = false
            )
        }
        try {
            context.stopService(android.content.Intent(context, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    fun release() {
        saveCurrentPlaybackState(immediate = true)
        stopProgressUpdate()
        PlaybackService.unregisterTaskRemovedListener(serviceTaskRemovedListener)
        controller?.removeListener(playerListener)
        controllerFuture?.let {
            try {
                MediaController.releaseFuture(it)
            } catch (_: Exception) {
            }
            controllerFuture = null
        } ?: controller?.release()
        controller = null
        isControllerConnectionRequested = false
    }
}

