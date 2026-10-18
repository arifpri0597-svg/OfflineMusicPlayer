package com.example

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.RepeatMode
import com.example.data.model.Song
import com.example.player.MusicPlayerController
import com.example.player.PlayerUiState
import com.example.player.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceTest {

    private lateinit var context: Context
    private lateinit var testScope: CoroutineScope
    private lateinit var fakePlayer: FakePlayerHarness
    private lateinit var controller: MusicPlayerController
    private val playedSongsHistory = mutableListOf<Song>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        playedSongsHistory.clear()
        fakePlayer = FakePlayerHarness()
        controller = MusicPlayerController(
            context = context,
            scope = testScope,
            onSongPlayed = { playedSongsHistory.add(it) },
            initialPlayer = fakePlayer.playerProxy
        )
    }

    @After
    fun tearDown() {
        controller.release()
        testScope.cancel()
    }

    private fun sampleSong(id: Long, title: String = "Track $id", durationMs: Long = 200_000L): Song {
        return Song(
            id = id,
            title = title,
            artist = "Artist $id",
            album = "Album $id",
            durationMs = durationMs,
            contentUri = Uri.parse("content://media/external/audio/media/$id"),
            albumArtUri = Uri.parse("content://media/external/audio/albumart/$id"),
            trackNumber = id.toInt(),
            year = 2024,
            sizeBytes = 5_000_000L,
            dateAdded = 1_700_000_000L + id,
            folderName = "Music",
            path = "/storage/emulated/0/Music/$title.mp3"
        )
    }

    @Test
    fun `playbackService creates notification channel and initializes mediaSession with audio attributes`() {
        val serviceController = Robolectric.buildService(PlaybackService::class.java)
        val service = serviceController.create().get()

        // Verify notification channel creation
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(PlaybackService.NOTIFICATION_CHANNEL_ID)
        assertNotNull("Playback notification channel should be registered", channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)

        // Verify MediaSession and underlying ExoPlayer configuration
        val session = service.mediaSession
        assertNotNull("MediaSession must be initialized on service create", session)
        assertEquals(C.USAGE_MEDIA, session!!.player.audioAttributes.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, session.player.audioAttributes.contentType)

        // Verify onTaskRemoved stops service when idle with empty queue
        service.onTaskRemoved(Intent())
        assertTrue("Service should stop itself when task removed while idle", shadowOf(service).isStoppedBySelf)

        // Verify onDestroy cleans up MediaSession
        serviceController.destroy()
        assertNull("MediaSession should be null after onDestroy", service.mediaSession)
    }

    @Test
    fun `playerUiState calculates progress and formats duration and position edge cases`() {
        val defaultState = PlayerUiState()
        assertEquals(0f, defaultState.progress, 0.001f)
        assertEquals("0:00", defaultState.formattedPosition)
        assertEquals("--:--", defaultState.formattedDuration)

        val activeState = PlayerUiState(positionMs = 125_000L, durationMs = 250_000L)
        assertEquals(0.5f, activeState.progress, 0.001f)
        assertEquals("2:05", activeState.formattedPosition)
        assertEquals("4:10", activeState.formattedDuration)

        // Overflow clamping test
        val overflowState = PlayerUiState(positionMs = 300_000L, durationMs = 200_000L)
        assertEquals(1.0f, overflowState.progress, 0.001f)
    }

    @Test
    fun `controller queues pending play request before player connection and executes on attach`() {
        val disconnectedController = MusicPlayerController(
            context = context,
            scope = testScope,
            onSongPlayed = { playedSongsHistory.add(it) },
            initialPlayer = null
        )
        val songs = listOf(sampleSong(1L), sampleSong(2L), sampleSong(3L))

        // Play while controller has no active Player yet
        disconnectedController.playQueue(songs, startIndex = 1)
        assertEquals(1, disconnectedController.uiState.value.currentIndex)
        assertEquals(2L, disconnectedController.uiState.value.currentSong?.id)

        // Attach test player and verify pending request is replayed onto the player
        val harness = FakePlayerHarness()
        disconnectedController.setPlayerForTesting(harness.playerProxy)

        assertEquals(3, harness.mediaItems.size)
        assertEquals(1, harness.currentMediaItemIndex)
        assertTrue(harness.isPlaying)
        assertEquals(1, playedSongsHistory.size)
        assertEquals(2L, playedSongsHistory.first().id)

        disconnectedController.release()
    }

    @Test
    fun `controller playQueue togglePlayPause and skip controls operate accurately`() {
        val songs = listOf(sampleSong(10L), sampleSong(20L), sampleSong(30L))
        fakePlayer.duration = 180_000L

        controller.playQueue(songs, startIndex = 0)
        assertEquals(3, controller.uiState.value.queue.size)
        assertEquals(10L, controller.uiState.value.currentSong?.id)
        assertTrue(fakePlayer.isPlaying)
        assertEquals(listOf(10L), playedSongsHistory.map { it.id })

        // Toggle pause and resume
        controller.togglePlayPause()
        assertFalse(fakePlayer.isPlaying)
        controller.togglePlayPause()
        assertTrue(fakePlayer.isPlaying)

        // Skip to next (0 -> 1 -> 2 -> wraps to 0)
        controller.skipToNext()
        assertEquals(1, fakePlayer.currentMediaItemIndex)
        assertEquals(20L, controller.uiState.value.currentSong?.id)

        controller.skipToNext()
        assertEquals(2, fakePlayer.currentMediaItemIndex)
        assertEquals(30L, controller.uiState.value.currentSong?.id)

        controller.skipToNext()
        assertEquals(0, fakePlayer.currentMediaItemIndex)
        assertEquals(10L, controller.uiState.value.currentSong?.id)

        // Skip to previous when position > 3000ms restarts current track instead of changing index
        fakePlayer.currentMediaItemIndex = 1
        fakePlayer.currentPosition = 5_000L
        controller.skipToPrevious()
        assertEquals(1, fakePlayer.currentMediaItemIndex)
        assertEquals(0L, fakePlayer.currentPosition)

        // Skip to previous when position <= 3000ms moves to previous track
        fakePlayer.currentPosition = 1_500L
        controller.skipToPrevious()
        assertEquals(0, fakePlayer.currentMediaItemIndex)
    }

    @Test
    fun `controller seekTo and seekRelative clamp within valid track bounds`() {
        val songs = listOf(sampleSong(1L, durationMs = 120_000L))
        fakePlayer.duration = 120_000L
        controller.playQueue(songs, 0)

        // Seek within bounds
        controller.seekTo(45_000L)
        assertEquals(45_000L, fakePlayer.currentPosition)
        assertEquals(45_000L, controller.uiState.value.positionMs)

        // Seek beyond duration clamps to 120_000L
        controller.seekTo(999_000L)
        assertEquals(120_000L, fakePlayer.currentPosition)

        // Seek negative clamps to 0L
        controller.seekTo(-5_000L)
        assertEquals(0L, fakePlayer.currentPosition)

        // Relative forward and backward seeks
        controller.seekTo(50_000L)
        controller.seekRelative(10_000L)
        assertEquals(60_000L, fakePlayer.currentPosition)

        controller.seekRelative(-70_000L)
        assertEquals(0L, fakePlayer.currentPosition)
    }

    @Test
    fun `controller toggles shuffle and cycles repeat modes OFF to ALL to ONE to OFF`() {
        assertFalse(controller.uiState.value.isShuffleEnabled)
        assertEquals(RepeatMode.OFF, controller.uiState.value.repeatMode)

        controller.toggleShuffle()
        assertTrue(fakePlayer.shuffleModeEnabled)
        assertTrue(controller.uiState.value.isShuffleEnabled)

        controller.toggleShuffle()
        assertFalse(fakePlayer.shuffleModeEnabled)
        assertFalse(controller.uiState.value.isShuffleEnabled)

        // Cycle repeat modes: OFF -> ALL -> ONE -> OFF
        controller.cycleRepeatMode()
        assertEquals(Player.REPEAT_MODE_ALL, fakePlayer.repeatMode)
        assertEquals(RepeatMode.ALL, controller.uiState.value.repeatMode)

        controller.cycleRepeatMode()
        assertEquals(Player.REPEAT_MODE_ONE, fakePlayer.repeatMode)
        assertEquals(RepeatMode.ONE, controller.uiState.value.repeatMode)

        controller.cycleRepeatMode()
        assertEquals(Player.REPEAT_MODE_OFF, fakePlayer.repeatMode)
        assertEquals(RepeatMode.OFF, controller.uiState.value.repeatMode)
    }

    @Test
    fun `controller manages queue mutations and recovers from playback errors`() {
        val s1 = sampleSong(1L, "First")
        val s2 = sampleSong(2L, "Second")
        val s3 = sampleSong(3L, "Third")
        val s4 = sampleSong(4L, "Fourth")

        controller.playQueue(listOf(s1, s2), startIndex = 0)
        assertEquals(listOf(1L, 2L), controller.uiState.value.queue.map { it.id })

        // Play next inserts right after current index (0 -> inserts at 1)
        controller.playNext(s3)
        assertEquals(listOf(1L, 3L, 2L), controller.uiState.value.queue.map { it.id })

        // Add to queue appends to end
        controller.addToQueue(s4)
        assertEquals(listOf(1L, 3L, 2L, 4L), controller.uiState.value.queue.map { it.id })

        // Remove from queue
        controller.removeFromQueue(2) // Removes s2
        assertEquals(listOf(1L, 3L, 4L), controller.uiState.value.queue.map { it.id })

        // Jump to queue item
        controller.jumpToQueueItem(2)
        assertEquals(2, fakePlayer.currentMediaItemIndex)
        assertTrue(fakePlayer.isPlaying)

        // Simulate playback error when a next item exists
        fakePlayer.currentMediaItemIndex = 0
        fakePlayer.notifyPlayerError(
            PlaybackException("Corrupted audio stream", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        )
        assertEquals("Corrupted audio stream", controller.uiState.value.errorMessage)
        // Should automatically advance to index 1 and play
        assertEquals(1, fakePlayer.currentMediaItemIndex)
        assertTrue(fakePlayer.isPlaying)

        controller.clearErrorMessage()
        assertNull(controller.uiState.value.errorMessage)
    }

    private class FakePlayerHarness {
        val listeners = mutableListOf<Player.Listener>()
        val mediaItems = mutableListOf<MediaItem>()
        var currentMediaItemIndex: Int = 0
        var currentPosition: Long = 0L
        var duration: Long = 200_000L
        var isPlaying: Boolean = false
        var playbackState: Int = Player.STATE_READY
        var shuffleModeEnabled: Boolean = false
        var repeatMode: Int = Player.REPEAT_MODE_OFF

        fun notifyPlayerError(error: PlaybackException) {
            listeners.toList().forEach { it.onPlayerError(error) }
        }

        val playerProxy: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addListener" -> {
                    val listener = args[0] as Player.Listener
                    if (!listeners.contains(listener)) listeners.add(listener)
                    Unit
                }
                "removeListener" -> {
                    val listener = args[0] as Player.Listener
                    listeners.remove(listener)
                    Unit
                }
                "setMediaItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    val items = args[0] as List<MediaItem>
                    mediaItems.clear()
                    mediaItems.addAll(items)
                    currentMediaItemIndex = if (args.size >= 2 && args[1] is Int) args[1] as Int else 0
                    currentPosition = if (args.size >= 3 && args[2] is Long) args[2] as Long else 0L
                    Unit
                }
                "prepare" -> {
                    playbackState = Player.STATE_READY
                    listeners.toList().forEach { it.onPlaybackStateChanged(playbackState) }
                    Unit
                }
                "play" -> {
                    isPlaying = true
                    listeners.toList().forEach { it.onIsPlayingChanged(true) }
                    Unit
                }
                "pause" -> {
                    isPlaying = false
                    listeners.toList().forEach { it.onIsPlayingChanged(false) }
                    Unit
                }
                "isPlaying" -> isPlaying
                "getPlaybackState" -> playbackState
                "getCurrentMediaItemIndex" -> currentMediaItemIndex
                "getMediaItemCount" -> mediaItems.size
                "getCurrentPosition" -> currentPosition
                "getDuration" -> duration
                "hasNextMediaItem" -> currentMediaItemIndex + 1 < mediaItems.size
                "hasPreviousMediaItem" -> currentMediaItemIndex > 0
                "seekToNextMediaItem" -> {
                    if (currentMediaItemIndex + 1 < mediaItems.size) {
                        currentMediaItemIndex++
                        currentPosition = 0L
                        listeners.toList().forEach {
                            it.onMediaItemTransition(
                                mediaItems.getOrNull(currentMediaItemIndex),
                                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                            )
                        }
                    }
                    Unit
                }
                "seekToPreviousMediaItem" -> {
                    if (currentMediaItemIndex > 0) {
                        currentMediaItemIndex--
                        currentPosition = 0L
                        listeners.toList().forEach {
                            it.onMediaItemTransition(
                                mediaItems.getOrNull(currentMediaItemIndex),
                                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                            )
                        }
                    }
                    Unit
                }
                "seekToDefaultPosition" -> {
                    if (args != null && args.isNotEmpty() && args[0] is Int) {
                        currentMediaItemIndex = args[0] as Int
                    }
                    currentPosition = 0L
                    listeners.toList().forEach {
                        it.onMediaItemTransition(
                            mediaItems.getOrNull(currentMediaItemIndex),
                            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                        )
                    }
                    Unit
                }
                "seekTo" -> {
                    if (args.size == 1 && args[0] is Long) {
                        currentPosition = args[0] as Long
                    } else if (args.size == 2 && args[0] is Int && args[1] is Long) {
                        currentMediaItemIndex = args[0] as Int
                        currentPosition = args[1] as Long
                        listeners.toList().forEach {
                            it.onMediaItemTransition(
                                mediaItems.getOrNull(currentMediaItemIndex),
                                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                            )
                        }
                    }
                    Unit
                }
                "getShuffleModeEnabled" -> shuffleModeEnabled
                "setShuffleModeEnabled" -> {
                    shuffleModeEnabled = args[0] as Boolean
                    listeners.toList().forEach { it.onShuffleModeEnabledChanged(shuffleModeEnabled) }
                    Unit
                }
                "getRepeatMode" -> repeatMode
                "setRepeatMode" -> {
                    repeatMode = args[0] as Int
                    listeners.toList().forEach { it.onRepeatModeChanged(repeatMode) }
                    Unit
                }
                "addMediaItem" -> {
                    if (args.size == 1 && args[0] is MediaItem) {
                        mediaItems.add(args[0] as MediaItem)
                    } else if (args.size == 2 && args[0] is Int && args[1] is MediaItem) {
                        mediaItems.add(args[0] as Int, args[1] as MediaItem)
                    }
                    Unit
                }
                "removeMediaItem" -> {
                    val idx = args[0] as Int
                    if (idx in mediaItems.indices) {
                        mediaItems.removeAt(idx)
                    }
                    Unit
                }
                "release" -> {
                    isPlaying = false
                    listeners.clear()
                    Unit
                }
                "toString" -> "FakePlayerProxy"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            }
        } as Player
    }
}
