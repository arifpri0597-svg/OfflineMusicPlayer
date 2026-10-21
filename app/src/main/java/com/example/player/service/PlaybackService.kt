package com.example.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class PlaybackService : MediaSessionService() {

    internal var mediaSession: MediaSession? = null
    internal lateinit var player: ExoPlayer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var periodicSaveJob: Job? = null
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
            if (isPlaying) {
                restoredFloorPositionMs = null
                startPeriodicPositionSave()
                persistPlayerState(immediate = false)
            } else {
                stopPeriodicPositionSave()
                if (!isRestoringState) {
                    persistPlayerState(immediate = true)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (isRestoringState) return
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
            if (isRestoringState) return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                restoredFloorPositionMs = null
                persistPlayerState(immediate = true)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (isRestoringState) return
            persistPlayerState(immediate = false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isRestoringState) return
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_READY) {
                persistPlayerState(immediate = false)
            }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "pulse_music_channel"
        const val NOTIFICATION_ID = 1001
        private const val PERIODIC_SAVE_INTERVAL_MS = 3000L
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
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

        serviceScope.launch {
            restoreSavedPlaybackStateIfIdle()
        }
    }

    internal suspend fun restoreSavedPlaybackStateIfIdle(): Boolean {
        if (!::player.isInitialized || player.mediaItemCount > 0) return false
        val saved = playbackStateRepository.getLastPlaybackState() ?: return false
        val session = playbackStateRepository.resolveRestoredSession(saved, emptyList()) ?: return false
        if (mediaSession == null || player.mediaItemCount > 0 || player.playWhenReady) return false

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
                    putBoolean("isExternal", session.isExternal || PlaybackStateRepository.isExternalUriString(song.contentUri.toString()))
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
        immediate: Boolean = true
    ) {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
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
            isPlaying = player.isPlaying,
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
                if (::player.isInitialized && player.isPlaying && player.mediaItemCount > 0) {
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPlayerState(immediate = true)
        super.onTaskRemoved(rootIntent)
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopPeriodicPositionSave()
        persistPlayerState(immediate = true)
        serviceScope.cancel()
        mediaSession?.run {
            player.removeListener(servicePlayerListener)
            player.release()
            release()
            mediaSession = null
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
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

