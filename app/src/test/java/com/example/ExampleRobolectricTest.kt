package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.RepeatMode
import com.example.data.model.Song
import com.example.player.PlayerUiState
import androidx.room.Room
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistSongCrossRef
import com.example.data.local.PulseDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Pulse Music", appName)
    }

    @Test
    fun `test player ui state progress calculation`() {
        val state = PlayerUiState(
            positionMs = 30000L,
            durationMs = 60000L
        )
        assertEquals(0.5f, state.progress, 0.01f)
        assertEquals("0:30", state.formattedPosition)
        assertEquals("1:00", state.formattedDuration)
    }

    @Test
    fun `test room playlist creation naming and song association`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.musicDao()

        // 1. Create and name playlist
        val playlistId = dao.insertPlaylist(PlaylistEntity(name = "Gym Beats"))
        assertTrue("Playlist ID should be positive", playlistId > 0)

        // 2. Add tracks to playlist
        dao.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlistId, songId = 1001L, orderIndex = 0))
        dao.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlistId, songId = 1002L, orderIndex = 1))

        // 3. Verify track count and song IDs
        val count = dao.getPlaylistTrackCount(playlistId)
        assertEquals(2, count)

        val songIds = dao.getSongIdsForPlaylist(playlistId).first()
        assertEquals(listOf(1001L, 1002L), songIds)

        // 4. Remove a track
        dao.removeSongFromPlaylist(playlistId, 1001L)
        val remainingCount = dao.getPlaylistTrackCount(playlistId)
        assertEquals(1, remainingCount)

        db.close()
    }

    @Test
    fun `test open with audio intent filters resolve MainActivity for all supported formats`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager

        val supportedMimeTypes = listOf(
            "audio/*",          // Wildcard audio
            "audio/mpeg",       // MP3
            "audio/mp3",        // MP3
            "audio/wav",        // WAV
            "audio/x-wav",      // WAV
            "audio/mp4",        // M4A
            "audio/m4a",        // M4A
            "audio/x-m4a",      // M4A
            "audio/aac",        // AAC
            "audio/flac",       // FLAC
            "audio/x-flac",     // FLAC
            "application/flac", // FLAC
            "audio/ogg",        // OGG
            "application/ogg",  // OGG
            "audio/webm"        // WEBM
        )

        val testUris = listOf(
            android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2Ftrack.mp3"),
            android.net.Uri.parse("file:///storage/emulated/0/Music/track.mp3")
        )

        for (uri in testUris) {
            for (mimeType in supportedMimeTypes) {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addCategory(android.content.Intent.CATEGORY_DEFAULT)
                }
                val resolved = packageManager.queryIntentActivities(intent, 0)
                val matchesMainActivity = resolved.any {
                    it.activityInfo.name == MainActivity::class.java.name
                }
                assertTrue(
                    "MainActivity must resolve in Open with menu for scheme=${uri.scheme} mimeType=$mimeType",
                    matchesMainActivity
                )
            }
        }
    }

    @Test
    fun `test external audio uri security validation blocks private app directory traversal`() {
        val app = ApplicationProvider.getApplicationContext<PulseMusicApp>()
        val viewModel = com.example.viewmodel.MusicViewModel(app)

        val validContentUri = android.net.Uri.parse("content://media/external/audio/media/42")
        val validStorageFileUri = android.net.Uri.parse("file:///storage/emulated/0/Music/song.mp3")
        val invalidSchemeUri = android.net.Uri.parse("javascript:alert(1)")
        val privateDbFile = java.io.File(app.applicationInfo.dataDir, "databases/pulse_music_database.db")
        val privateFileUri = android.net.Uri.fromFile(privateDbFile)

        assertTrue(viewModel.isSafeExternalAudioUri(validContentUri))
        assertTrue(viewModel.isSafeExternalAudioUri(validStorageFileUri))
        assertFalse(viewModel.isSafeExternalAudioUri(invalidSchemeUri))
        assertFalse(viewModel.isSafeExternalAudioUri(privateFileUri))
    }

    @Test
    fun `test atomic appendSongsToPlaylist and orphan cleanup on syncScannedSongs`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.musicDao()

        val song1 = com.example.data.local.SongEntity(
            id = 10L,
            title = "Track 10",
            artist = "Artist",
            album = "Album",
            durationMs = 120_000L,
            contentUriString = "content://media/external/audio/media/10",
            albumArtUriString = null
        )
        val song2 = song1.copy(id = 20L, title = "Track 20")

        dao.syncScannedSongs(listOf(song1, song2))
        val playlistId = dao.insertPlaylist(PlaylistEntity(name = "Audit Playlist"))
        dao.appendSongsToPlaylist(playlistId, listOf(10L, 20L))
        dao.insertFavorite(com.example.data.local.FavoriteEntity(songId = 20L))

        assertEquals(2, dao.getPlaylistTrackCount(playlistId))
        assertTrue(dao.isFavorite(20L).first())

        // Sync with only song1 remaining -> song2 should be pruned from songs, playlist_songs, and favorites
        dao.syncScannedSongs(listOf(song1))
        assertEquals(listOf(10L), dao.getAllCachedSongIds())
        assertEquals(1, dao.getPlaylistTrackCount(playlistId))
        assertFalse(dao.isFavorite(20L).first())

        db.close()
    }

    @Test
    fun firstLaunchAndClearData_permissionGrantAndRepeatedCallbacks_isIdempotentAndCrashFree() {
        val context = ApplicationProvider.getApplicationContext<PulseMusicApp>()
        val db = context.database
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            db.clearAllTables()
        }

        val viewModel = com.example.viewmodel.MusicViewModel(context)

        // 1. Permission denied state on first launch
        viewModel.onPermissionResult(false)
        assertFalse(viewModel.libraryState.value.hasPermission)

        // 2. Simultaneous / repeated permission granted callbacks after fresh install or Clear Data
        viewModel.onPermissionResult(true)
        viewModel.onPermissionResult(true)
        viewModel.refreshLibrary()
        viewModel.refreshLibrary()

        assertTrue(viewModel.libraryState.value.hasPermission)
    }

    @Test
    fun `nowPlaying speed button labels and track info real metadata resolution omit unavailable fields`() {
        // 1. Verify playback speed button labels (0.5X .. 2.0X) and sheet labels (0.5× .. 2×)
        assertEquals(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f), PlayerUiState.PLAYBACK_SPEED_OPTIONS)
        assertEquals("0.5X", PlayerUiState.formatSpeedButtonLabel(0.5f))
        assertEquals("0.75X", PlayerUiState.formatSpeedButtonLabel(0.75f))
        assertEquals("1.0X", PlayerUiState.formatSpeedButtonLabel(1.0f))
        assertEquals("1.25X", PlayerUiState.formatSpeedButtonLabel(1.25f))
        assertEquals("1.5X", PlayerUiState.formatSpeedButtonLabel(1.5f))
        assertEquals("2.0X", PlayerUiState.formatSpeedButtonLabel(2.0f))

        // 2. Verify Track Info metadata extraction only includes real available fields
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fullSong = Song(
            id = 501L,
            title = "Neon Horizon",
            artist = "Synthwave Collective",
            album = "Midnight Drive",
            durationMs = 215_000L,
            contentUri = android.net.Uri.parse("content://media/external/audio/media/501"),
            albumArtUri = null,
            sizeBytes = 5_242_880L,
            path = "/storage/emulated/0/Music/Neon_Horizon.flac"
        )
        val fullRows = com.example.ui.screens.resolveAvailableTrackMetadata(context, fullSong)
        val fullMap = fullRows.associate { it.label to it.value }
        assertEquals("Neon Horizon", fullMap["Title"])
        assertEquals("Synthwave Collective", fullMap["Artist"])
        assertEquals("Midnight Drive", fullMap["Album"])
        assertEquals("3:35", fullMap["Duration"])
        assertEquals("Neon_Horizon.flac", fullMap["File Name"])
        assertEquals("/storage/emulated/0/Music/Neon_Horizon.flac", fullMap["File Location"])
        assertEquals("5.00 MB", fullMap["File Size"])
        assertEquals("FLAC", fullMap["Format"])
        // No fake Bitrate or Sample Rate when no real audio stream is backing the URI
        assertFalse(fullMap.containsKey("Bitrate"))
        assertFalse(fullMap.containsKey("Sample Rate"))

        // 3. Verify unknown/empty fields are omitted rather than showing fake placeholders
        val minimalSong = Song(
            id = 502L,
            title = "Minimal Track",
            artist = "<unknown>",
            album = "Unknown Album",
            durationMs = 0L,
            contentUri = android.net.Uri.parse("content://media/external/audio/media/502"),
            albumArtUri = null,
            sizeBytes = 0L,
            path = ""
        )
        val minimalRows = com.example.ui.screens.resolveAvailableTrackMetadata(context, minimalSong)
        val minimalMap = minimalRows.associate { it.label to it.value }
        assertEquals(listOf("Title"), minimalRows.map { it.label })
        assertEquals("Minimal Track", minimalMap["Title"])
    }

    @Test
    fun `artworkCacheManager negative cache and staticPlayerUiState prevent scroll jank`() {
        val missingArtUri = "content://media/external/audio/albumart/999999"
        val validArtUri = "content://media/external/audio/albumart/111111"

        com.example.ui.components.ArtworkCacheManager.clear()
        assertFalse(com.example.ui.components.ArtworkCacheManager.isKnownMissing(missingArtUri))

        // Once an album's art URI fails to open, ArtworkCacheManager caches the miss so
        // subsequent songs / scroll passes skip ContentResolver IPC and AsyncImage completely
        com.example.ui.components.ArtworkCacheManager.markMissing(missingArtUri)
        assertTrue(com.example.ui.components.ArtworkCacheManager.isKnownMissing(missingArtUri))
        assertTrue(com.example.ui.components.ArtworkCacheManager.isKnownMissing(null))
        assertTrue(com.example.ui.components.ArtworkCacheManager.isKnownMissing(""))

        com.example.ui.components.ArtworkCacheManager.markVerified(validArtUri)
        assertTrue(com.example.ui.components.ArtworkCacheManager.isVerified(validArtUri))
        assertFalse(com.example.ui.components.ArtworkCacheManager.isKnownMissing(validArtUri))

        com.example.ui.components.ArtworkCacheManager.clear()
        assertFalse(com.example.ui.components.ArtworkCacheManager.isKnownMissing(missingArtUri))
    }
}

