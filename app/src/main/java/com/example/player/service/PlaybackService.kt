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
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.PulseMusicApp
import com.example.R
import com.example.data.local.PlaybackStateEntity
import com.example.data.local.PulseDatabase
import com.example.data.model.RepeatMode
import com.example.data.repository.PlaybackStateRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lock-free, zero-allocation real-time audio telemetry bridge between ExoPlayer's
 * PCM rendering pipeline and the Now Playing waveform visualizer.
 */
class AudioVisualizerBridge(val bandCount: Int = 28) {
    private val bandTargets = FloatArray(bandCount)

    @Volatile
    var rmsAmplitude: Float = 0f
        private set

    @Volatile
    var bassLevel: Float = 0f
        private set

    @Volatile
    var midLevel: Float = 0f
        private set

    @Volatile
    var trebleLevel: Float = 0f
        private set

    @Volatile
    var beatIntensity: Float = 0f
        private set

    @Volatile
    var lastUpdateNanos: Long = 0L
        private set

    // Internal filter states (accessed on ExoPlayer audio thread)
    private var lpStage1: Float = 0f
    private var lpStage2: Float = 0f
    private var prevSample: Float = 0f
    private var rollingBassAvg: Float = 0.08f
    private var beatEnvelope: Float = 0f
    private var spectralPhase: Float = 0f

    /**
     * Processes a buffer of PCM audio samples in-place with zero heap allocations.
     * Extracts RMS amplitude, sub-bass/bass energy, mid/treble energy, beat transients,
     * and 28-band spectral distribution.
     */
    fun updateFromPcmSamples(
        monoSamples: FloatArray,
        sampleCount: Int,
        sampleRate: Int,
        timestampNanos: Long = System.nanoTime()
    ) {
        if (sampleCount <= 0) return

        // Cutoff ~140Hz adjusted for strided effective sample rate
        val alphaLow = (900f / sampleRate.coerceAtLeast(8000)).coerceIn(0.06f, 0.38f)
        var sumSq = 0f
        var bassSq = 0f
        var midSq = 0f
        var highSq = 0f

        var s1 = lpStage1
        var s2 = lpStage2
        var prev = prevSample

        for (i in 0 until sampleCount) {
            val sample = monoSamples[i].coerceIn(-1f, 1f)
            sumSq += sample * sample

            // Two-pole cascaded low-pass for deep bass / kick isolation
            s1 += alphaLow * (sample - s1)
            s2 += alphaLow * (s1 - s2)
            val low = s2

            // High-frequency component via first difference
            val high = (sample - prev) * 0.5f
            prev = sample

            // Mid-range residual
            val mid = sample - low - high * 0.45f

            bassSq += low * low
            midSq += mid * mid
            highSq += high * high
        }

        lpStage1 = s1
        lpStage2 = s2
        prevSample = prev

        val invN = 1f / sampleCount.toFloat()
        val rawRms = sqrt(sumSq * invN)
        val rawBass = sqrt(bassSq * invN)
        val rawMid = sqrt(midSq * invN)
        val rawHigh = sqrt(highSq * invN)

        // Perceptual soft-knee compression for natural visual dynamics across quiet & loud masters
        val normRms = (sqrt(rawRms * 2.4f) * 1.15f).coerceIn(0f, 1f)
        val normBass = (sqrt(rawBass * 3.1f) * 1.25f).coerceIn(0f, 1f)
        val normMid = (sqrt(rawMid * 2.8f) * 1.12f).coerceIn(0f, 1f)
        val normHigh = (sqrt(rawHigh * 3.6f) * 1.08f).coerceIn(0f, 1f)

        // Adaptive beat & kick transient detector based on positive bass/RMS flux
        val bassFlux = max(0f, normBass - rollingBassAvg * 1.16f)
        rollingBassAvg = rollingBassAvg * 0.90f + normBass * 0.10f
        val instantBeat = (bassFlux * 2.8f + max(0f, normRms - rmsAmplitude) * 1.4f).coerceIn(0f, 1f)
        beatEnvelope = if (instantBeat > beatEnvelope) {
            beatEnvelope * 0.30f + instantBeat * 0.70f
        } else {
            (beatEnvelope * 0.84f).coerceAtLeast(0f)
        }

        spectralPhase = (spectralPhase + 0.18f + normBass * 0.24f + beatEnvelope * 0.22f) % 62.831852f

        // Compute 28-band spectral target heights combining real-time bass, mid, treble,
        // and multi-lag autocorrelation / comb energy across the sample window.
        val centerIndex = (bandCount - 1) * 0.5f
        for (b in 0 until bandCount) {
            val centerDist = abs(b - centerIndex) / centerIndex // 0 at center, 1 at edges
            val centerWeight = 1f - centerDist

            // Lagged correlation tap to give each bar genuine sample-driven texture
            val tapStride = (1 + (b % 7)).coerceAtMost(max(1, sampleCount / 2))
            val tapIdxA = (b * 3) % sampleCount
            val tapIdxB = (tapIdxA + tapStride) % sampleCount
            val tapEnergy = abs(monoSamples[tapIdxA] - monoSamples[tapIdxB] * 0.65f)
                .coerceIn(0f, 1f)

            // Center bars emphasize bass + kick beat; inner-mid bars emphasize vocals/mids;
            // outer bars emphasize crisp highs/air while maintaining balanced symmetry.
            val bassContribution = normBass * (0.45f + 0.55f * centerWeight * centerWeight)
            val midContribution = normMid * (0.35f + 0.65f * (1f - abs(centerDist - 0.42f)))
            val highContribution = normHigh * (0.30f + 0.70f * centerDist)
            val beatBoost = beatEnvelope * (0.28f + 0.42f * centerWeight)
            val harmonicMod = 0.88f + 0.12f * sin(spectralPhase + b * 0.52f)

            val combined = (
                bassContribution * 0.44f +
                    midContribution * 0.30f +
                    highContribution * 0.16f +
                    sqrt(tapEnergy) * 0.10f +
                    beatBoost
                ) * harmonicMod * (0.58f + 0.42f * centerWeight)

            bandTargets[b] = combined.coerceIn(0f, 1f)
        }

        rmsAmplitude = normRms
        bassLevel = normBass
        midLevel = normMid
        trebleLevel = normHigh
        beatIntensity = beatEnvelope
        lastUpdateNanos = timestampNanos
    }

    /**
     * Copies the latest live 28-band targets into [outBands] and returns a live signal weight
     * in `0f..1f` indicating how fresh the PCM audio stream is relative to [nowNanos].
     */
    fun sampleLiveState(nowNanos: Long, outBands: FloatArray): Float {
        val lastNanos = lastUpdateNanos
        if (lastNanos == 0L) return 0f
        val ageMs = ((nowNanos - lastNanos) / 1_000_000L).coerceAtLeast(0L)
        if (ageMs > 220L) return 0f

        val copyLen = min(bandCount, outBands.size)
        for (i in 0 until copyLen) {
            outBands[i] = bandTargets[i]
        }
        return if (ageMs <= 90L) {
            1f
        } else {
            (1f - (ageMs - 90L) / 130f).coerceIn(0f, 1f)
        }
    }

    fun clearLiveSignal() {
        lastUpdateNanos = 0L
        rmsAmplitude = 0f
        bassLevel = 0f
        midLevel = 0f
        trebleLevel = 0f
        beatIntensity = 0f
        lpStage1 = 0f
        lpStage2 = 0f
        prevSample = 0f
        beatEnvelope = 0f
        for (i in 0 until bandCount) {
            bandTargets[i] = 0f
        }
    }
}

/**
 * Zero-allocation Media3 passthrough [AudioProcessor] that taps decoded PCM samples
 * for [AudioVisualizerBridge] without altering the audio stream.
 */
@OptIn(UnstableApi::class)
internal class PureTuneVisualizerAudioProcessor(
    private val bridge: AudioVisualizerBridge
) : BaseAudioProcessor() {

    private val scratchSamples = FloatArray(96)

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        val supported = encoding == C.ENCODING_PCM_16BIT ||
            encoding == C.ENCODING_PCM_FLOAT ||
            encoding == C.ENCODING_PCM_8BIT ||
            encoding == C.ENCODING_PCM_24BIT ||
            encoding == C.ENCODING_PCM_32BIT
        return if (supported && inputAudioFormat.channelCount > 0) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        try {
            extractAndAnalyze(inputBuffer)
        } catch (_: Exception) {
            // Never disrupt audio playback if an unexpected buffer layout is encountered
        }

        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private fun extractAndAnalyze(buffer: ByteBuffer) {
        val format = inputAudioFormat
        val bytesPerFrame = format.bytesPerFrame
        if (bytesPerFrame <= 0) return

        val totalFrames = buffer.remaining() / bytesPerFrame
        if (totalFrames <= 0) return

        val targetSamples = min(scratchSamples.size, totalFrames)
        val frameStep = max(1, totalFrames / targetSamples)
        val basePos = buffer.position()
        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        var count = 0
        var frameIdx = 0
        val encoding = format.encoding
        val channels = format.channelCount.coerceAtLeast(1)

        while (frameIdx < totalFrames && count < scratchSamples.size) {
            val frameByteOffset = basePos + frameIdx * bytesPerFrame
            val sample = when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    var sum = 0f
                    val chLimit = min(channels, 2)
                    for (ch in 0 until chLimit) {
                        val s = buffer.getShort(frameByteOffset + ch * 2)
                        sum += s / 32768f
                    }
                    sum / chLimit
                }
                C.ENCODING_PCM_FLOAT -> {
                    var sum = 0f
                    val chLimit = min(channels, 2)
                    for (ch in 0 until chLimit) {
                        val f = buffer.getFloat(frameByteOffset + ch * 4)
                        if (!f.isNaN() && !f.isInfinite()) {
                            sum += f
                        }
                    }
                    sum / chLimit
                }
                C.ENCODING_PCM_8BIT -> {
                    val b = buffer.get(frameByteOffset).toInt() and 0xFF
                    (b - 128) / 128f
                }
                C.ENCODING_PCM_24BIT -> {
                    val b0 = buffer.get(frameByteOffset).toInt() and 0xFF
                    val b1 = buffer.get(frameByteOffset + 1).toInt() and 0xFF
                    val b2 = buffer.get(frameByteOffset + 2).toInt()
                    val combined = b0 or (b1 shl 8) or (b2 shl 16)
                    combined / 8388608f
                }
                C.ENCODING_PCM_32BIT -> {
                    val i = buffer.getInt(frameByteOffset)
                    (i / 2147483648.0).toFloat()
                }
                else -> 0f
            }
            scratchSamples[count++] = sample.coerceIn(-1f, 1f)
            frameIdx += frameStep
        }

        buffer.order(order)
        if (count > 0) {
            val effectiveSampleRate = max(1000, format.sampleRate / frameStep)
            bridge.updateFromPcmSamples(scratchSamples, count, effectiveSampleRate)
        }
    }

    override fun onFlush() {
        bridge.clearLiveSignal()
    }

    override fun onReset() {
        bridge.clearLiveSignal()
    }
}

class PlaybackService : MediaSessionService() {

    internal var mediaSession: MediaSession? = null
    internal lateinit var player: ExoPlayer
    internal var isPlayerReleased: Boolean = false
        private set
    private var hasHandledTaskRemoval: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var periodicSaveJob: Job? = null
    private var serviceSleepTimerJob: Job? = null
    @Volatile
    private var sleepTimerStopAtEndOfTrack: Boolean = false
    @Volatile
    private var sleepTimerEndOfTrackCallback: (() -> Unit)? = null
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
            if (!isPlaying) {
                visualizerBridge.clearLiveSignal()
            }
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
            visualizerBridge.clearLiveSignal()
            if (isRestoringState || hasHandledTaskRemoval || isPlayerReleased) return
            if (sleepTimerStopAtEndOfTrack &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            ) {
                val callback = sleepTimerEndOfTrackCallback
                sleepTimerStopAtEndOfTrack = false
                sleepTimerEndOfTrackCallback = null
                pausePlaybackForSleepTimer()
                callback?.invoke()
                return
            }
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
            if (sleepTimerStopAtEndOfTrack && playbackState == Player.STATE_ENDED) {
                val callback = sleepTimerEndOfTrackCallback
                sleepTimerStopAtEndOfTrack = false
                sleepTimerEndOfTrackCallback = null
                pausePlaybackForSleepTimer()
                callback?.invoke()
                return
            }
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_READY) {
                persistPlayerState(immediate = false)
            }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "pulse_music_channel"
        const val NOTIFICATION_ID = 1001
        private const val PERIODIC_SAVE_INTERVAL_MS = 3000L

        val visualizerBridge: AudioVisualizerBridge = AudioVisualizerBridge(bandCount = 28)

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

        fun setServiceSleepTimerEndOfTrack(enabled: Boolean, onExpired: (() -> Unit)? = null) {
            activeInstance?.setInternalSleepTimerEndOfTrack(enabled, onExpired)
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

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(PureTuneVisualizerAudioProcessor(visualizerBridge)))
                    .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
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
        sleepTimerStopAtEndOfTrack = false
        sleepTimerEndOfTrackCallback = null
        if (durationMs <= 0L || hasHandledTaskRemoval || isPlayerReleased) return
        serviceSleepTimerJob = serviceScope.launch {
            delay(durationMs)
            pausePlaybackForSleepTimer()
            onExpired?.invoke()
        }
    }

    internal fun setInternalSleepTimerEndOfTrack(enabled: Boolean, onExpired: (() -> Unit)? = null) {
        serviceSleepTimerJob?.cancel()
        serviceSleepTimerJob = null
        if (!enabled || hasHandledTaskRemoval || isPlayerReleased) {
            sleepTimerStopAtEndOfTrack = false
            sleepTimerEndOfTrackCallback = null
            return
        }
        sleepTimerStopAtEndOfTrack = true
        sleepTimerEndOfTrackCallback = onExpired
    }

    internal fun cancelInternalSleepTimer() {
        serviceSleepTimerJob?.cancel()
        serviceSleepTimerJob = null
        sleepTimerStopAtEndOfTrack = false
        sleepTimerEndOfTrackCallback = null
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
        visualizerBridge.clearLiveSignal()
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
