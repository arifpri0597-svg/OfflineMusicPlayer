package com.example.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.PulseMusicApp
import com.example.R
import com.example.data.local.PlaybackStateEntity
import com.example.data.local.PulseDatabase
import com.example.data.model.RepeatMode
import com.example.data.repository.PlaybackStateRepository
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    internal var mediaSession: MediaSession? = null
    internal lateinit var player: ExoPlayer
    internal var isPlayerReleased: Boolean = false
        private set
    private var hasHandledTaskRemoval: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var periodicSaveJob: Job? = null
    private var serviceSleepTimerJob: Job? = null
    private var lastSavedPositionMs: Long = -1L
    private var lastSavedSongId: Long = -1L
    private var isRestoringState: Boolean = false
    private var restoredFloorPositionMs: Long? = null

    internal val playbackStateRepository: PlaybackStateRepository by lazy {
        (application as? PulseMusicApp)?.playbackStateRepository
            ?: PlaybackStateRepository(
                applicationContext,
                PulseDatabase.getInstance(applicationContext).musicDao()
            )
    }

    private val servicePlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isRestoringState || hasHandledTaskRemoval || isPlayerReleased) return
            if (isPlaying) {
                restoredFloorPositionMs = null
                startPeriodicPositionSave()
                persistPlayerState(immediate = false)
            } else {
                stopPeriodicPositionSave()
                persistPlayerState(immediate = true)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (isRestoringState || hasHandledTaskRemoval || isPlayerReleased) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            ) {
                restoredFloorPositionMs = null
                persistPlayerState(
                    positionOverride = max(0L, newPosition.positionMs),
                    immediate = true
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isRestoringState || hasHandledTaskRemoval || isPlayerReleased) return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                restoredFloorPositionMs = null
                persistPlayerState(immediate = true)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (isRestoringState || hasHandledTaskRemoval || isPlayerReleased) return
            persistPlayerState(immediate = false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isRestoringState || hasHandledTaskRemoval || isPlayerReleased) return
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_READY) {
                persistPlayerState(immediate = false)
            }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "pulse_music_channel"
        const val NOTIFICATION_ID = 1001
        private const val PERIODIC_SAVE_INTERVAL_MS = 3000L

        @Volatile
        var isServiceRunning: Boolean = false
            internal set

        @Volatile
        private var activeInstance: PlaybackService? = null

        private val taskRemovedListeners = CopyOnWriteArraySet<() -> Unit>()

        fun registerTaskRemovedListener(listener: () -> Unit) {
            taskRemovedListeners.add(listener)
        }

        fun unregisterTaskRemovedListener(listener: () -> Unit) {
            taskRemovedListeners.remove(listener)
        }

        private fun notifyTaskRemovedListeners() {
            for (listener in taskRemovedListeners) {
                try {
                    listener.invoke()
                } catch (_: Exception) {
                }
            }
        }

        fun scheduleServiceSleepTimer(durationMs: Long, onExpired: (() -> Unit)? = null) {
            activeInstance?.scheduleInternalSleepTimer(durationMs, onExpired)
        }

        fun cancelServiceSleepTimer() {
            activeInstance?.cancelInternalSleepTimer()
        }

        fun pauseForSleepTimer() {
            activeInstance?.pausePlaybackForSleepTimer()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        hasHandledTaskRemoval = false
        isPlayerReleased = false
        isServiceRunning = true
        activeInstance = this
        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(servicePlayerListener)

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (hasHandledTaskRemoval || isPlayerReleased || mediaSession == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    internal suspend fun restoreSavedPlaybackStateIfIdle(): Boolean {
        if (!::player.isInitialized || isPlayerReleased || player.mediaItemCount > 0) return false
        val saved = playbackStateRepository.getLastPlaybackState() ?: return false
        val session = playbackStateRepository.resolveRestoredSession(saved, emptyList()) ?: return false
        if (mediaSession == null || isPlayerReleased || player.mediaItemCount > 0 || player.playWhenReady) return false

        isRestoringState = true
        try {
            val validSongs = session.queue
                .filter { it.contentUri.toString().isNotBlank() }
                .distinctBy { "${it.id}_${it.contentUri}" }
            if (validSongs.isEmpty()) return false

            val mediaItems = validSongs.map { song ->
                val extras = Bundle().apply {
                    putLong("durationMs", song.durationMs)
                    putString("contentUri", song.contentUri.toString())
                    putBoolean(
                        "isExternal",
                        session.isExternal || PlaybackStateRepository.isExternalUriString(song.contentUri.toString())
                    )
                }
                val metadata = MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(song.albumArtUri)
                    .setExtras(extras)
                    .build()
                MediaItem.Builder()
                    .setMediaId(song.id.toString())
                    .setUri(song.contentUri)
                    .setMediaMetadata(metadata)
                    .build()
            }
            val safeIndex = session.currentIndex.coerceIn(0, mediaItems.size - 1)
            player.playWhenReady = false
            player.setPlaybackSpeed(PlaybackStateRepository.clampSpeed(session.playbackSpeed))
            player.shuffleModeEnabled = session.shuffleEnabled
            player.repeatMode = when (session.repeatMode) {
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            }
            restoredFloorPositionMs = session.positionMs
            player.setMediaItems(mediaItems, safeIndex, session.positionMs)
            player.seekTo(safeIndex, session.positionMs)
            // Do not call player.prepare() while paused on automatic startup restoration;
            // preparing happens when the user explicitly initiates playback.
            lastSavedSongId = session.currentSong.id
            lastSavedPositionMs = session.positionMs
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            isRestoringState = false
        }
    }

    internal fun persistPlayerState(
        positionOverride: Long? = null,
        isPlayingOverride: Boolean? = null,
        immediate: Boolean = true
    ) {
        if (!::player.isInitialized || isPlayerReleased || player.mediaItemCount == 0) return
        val currentItem = player.currentMediaItem ?: return
        val songId = currentItem.mediaId.toLongOrNull() ?: 0L
        val extras = currentItem.mediaMetadata.extras
        val uriString = currentItem.localConfiguration?.uri?.toString()
            ?: extras?.getString("contentUri")
            ?: ""
        if (songId == 0L && uriString.isBlank()) return

        val rawPlayerDuration = player.duration
        val extraDuration = extras?.getLong("durationMs", 0L) ?: 0L
        val durationMs = if (rawPlayerDuration > 0L) rawPlayerDuration else max(0L, extraDuration)

        val rawPosition = positionOverride ?: run {
            val currentPos = max(0L, player.currentPosition)
            val floorPos = restoredFloorPositionMs
            if (floorPos != null && !player.isPlaying && currentPos == 0L && floorPos > 0L) {
                floorPos
            } else {
                currentPos
            }
        }
        val clampedPosition = PlaybackStateRepository.clampPosition(rawPosition, durationMs)

        val queueIds = buildString {
            for (i in 0 until player.mediaItemCount) {
                val id = player.getMediaItemAt(i).mediaId.toLongOrNull() ?: continue
                if (isNotEmpty()) append(',')
                append(id)
            }
        }

        val repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE.name
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL.name
            else -> RepeatMode.OFF.name
        }

        val currentSpeed = try {
            PlaybackStateRepository.clampSpeed(player.playbackParameters.speed)
        } catch (_: Exception) {
            1.0f
        }
        val isExternal = (extras?.getBoolean("isExternal", false) == true) ||
            PlaybackStateRepository.isExternalUriString(uriString)

        val stateEntity = PlaybackStateEntity(
            id = PlaybackStateEntity.SINGLETON_ID,
            songId = songId,
            contentUriString = uriString,
            title = currentItem.mediaMetadata.title?.toString() ?: "",
            artist = currentItem.mediaMetadata.artist?.toString() ?: "",
            album = currentItem.mediaMetadata.albumTitle?.toString() ?: "",
            albumArtUriString = currentItem.mediaMetadata.artworkUri?.toString(),
            positionMs = clampedPosition,
            durationMs = durationMs,
            queueSongIdsCsv = queueIds,
            currentIndex = max(0, player.currentMediaItemIndex),
            isPlaying = isPlayingOverride ?: player.isPlaying,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = repeatMode,
            playbackSpeed = currentSpeed,
            isExternal = isExternal,
            lastUpdated = System.currentTimeMillis()
        )

        lastSavedSongId = songId
        lastSavedPositionMs = clampedPosition

        if (immediate) {
            playbackStateRepository.savePlaybackStateImmediate(stateEntity)
        } else {
            serviceScope.launch {
                playbackStateRepository.savePlaybackState(stateEntity)
            }
        }
    }

    private fun startPeriodicPositionSave() {
        periodicSaveJob?.cancel()
        periodicSaveJob = serviceScope.launch {
            while (isActive) {
                delay(PERIODIC_SAVE_INTERVAL_MS)
                if (::player.isInitialized && !isPlayerReleased && player.isPlaying && player.mediaItemCount > 0) {
                    val pos = max(0L, player.currentPosition)
                    val currentId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
                    if (currentId != lastSavedSongId || abs(pos - lastSavedPositionMs) >= 1000L) {
                        persistPlayerState(positionOverride = pos, immediate = false)
                    }
                }
            }
        }
    }

    private fun stopPeriodicPositionSave() {
        periodicSaveJob?.cancel()
        periodicSaveJob = null
    }

    internal fun scheduleInternalSleepTimer(durationMs: Long, onExpired: (() -> Unit)? = null) {
        serviceSleepTimerJob?.cancel()
        serviceSleepTimerJob = null
        if (durationMs <= 0L || hasHandledTaskRemoval || isPlayerReleased) return
        serviceSleepTimerJob = serviceScope.launch {
            delay(durationMs)
            pausePlaybackForSleepTimer()
            onExpired?.invoke()
        }
    }

    internal fun cancelInternalSleepTimer() {
        serviceSleepTimerJob?.cancel()
        serviceSleepTimerJob = null
    }

    internal fun pausePlaybackForSleepTimer() {
        cancelInternalSleepTimer()
        if (hasHandledTaskRemoval || isPlayerReleased || !::player.isInitialized) return
        try {
            stopPeriodicPositionSave()
            val pos = max(0L, player.currentPosition)
            player.playWhenReady = false
            player.pause()
            persistPlayerState(positionOverride = pos, isPlayingOverride = false, immediate = true)
        } catch (_: Exception) {
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (hasHandledTaskRemoval || isPlayerReleased) return null
        return mediaSession
    }

    /**
     * Triggered when the user swipes PureTune away from Android Recent Apps / Recent Tasks.
     * Immediately saves playback position & speed, stops audio, clears and releases MediaSession,
     * removes the notification & lock-screen media controls, releases ExoPlayer, and stops the service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdownPlaybackAndSessionOnTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    internal fun shutdownPlaybackAndSessionOnTaskRemoved() {
        if (hasHandledTaskRemoval) {
            stopSelf()
            return
        }
        hasHandledTaskRemoval = true
        isServiceRunning = false
        if (activeInstance === this) {
            activeInstance = null
        }

        // 1. Stop periodic position save coroutine and sleep timer
        stopPeriodicPositionSave()
        cancelInternalSleepTimer()

        // 2. Persist current song, position, and playback speed (with isPlaying = false) before clearing player
        persistPlayerState(isPlayingOverride = false, immediate = true)

        // 3. Notify in-process MusicPlayerController to unbind MediaController and mark playback stopped
        notifyTaskRemovedListeners()

        // 4. Stop and clear ExoPlayer immediately so no audio continues in the background
        if (::player.isInitialized && !isPlayerReleased) {
            try {
                player.removeListener(servicePlayerListener)
                player.playWhenReady = false
                player.pause()
                player.stop()
                player.clearMediaItems()
            } catch (_: Exception) {
            }
        }

        // 5. Remove and release MediaSession so Android removes lock-screen controls and active player entry
        mediaSession?.let { session ->
            try {
                removeSession(session)
            } catch (_: Exception) {
            }
            try {
                session.release()
            } catch (_: Exception) {
            }
            mediaSession = null
        }

        // 6. Stop foreground state and cancel any active media notification in NotificationManager
        clearForegroundAndNotifications()

        // 7. Release ExoPlayer resources safely
        if (::player.isInitialized && !isPlayerReleased) {
            isPlayerReleased = true
            try {
                player.release()
            } catch (_: Exception) {
            }
        }

        // 8. Cancel service coroutine scope and stop PlaybackService
        serviceScope.cancel()
        stopSelf()
    }

    private fun clearForegroundAndNotifications() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID)
            notificationManager?.cancelAll()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        if (activeInstance === this) {
            activeInstance = null
        }
        stopPeriodicPositionSave()
        cancelInternalSleepTimer()
        if (!hasHandledTaskRemoval) {
            persistPlayerState(isPlayingOverride = false, immediate = true)
        }
        serviceScope.cancel()
        if (::player.isInitialized && !isPlayerReleased) {
            try {
                player.removeListener(servicePlayerListener)
                player.playWhenReady = false
                player.pause()
                player.stop()
                player.clearMediaItems()
            } catch (_: Exception) {
            }
        }
        mediaSession?.let { session ->
            try {
                removeSession(session)
            } catch (_: Exception) {
            }
            try {
                session.release()
            } catch (_: Exception) {
            }
            mediaSession = null
        }
        clearForegroundAndNotifications()
        if (::player.isInitialized && !isPlayerReleased) {
            isPlayerReleased = true
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
