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
import androidx.compose.ui.test.performClick

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

        // Verify multi-source decoding: even when primary albumart URI is missing, embedded bytes or fallback URI resolves artwork
        val appContext = ApplicationProvider.getApplicationContext<PulseMusicApp>()
        val testBitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        testBitmap.eraseColor(android.graphics.Color.MAGENTA)
        val pngBytes = java.io.ByteArrayOutputStream().use { bos ->
            testBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        }
        val fallbackFile = java.io.File(appContext.cacheDir, "fallback_cover_test.png")
        fallbackFile.writeBytes(pngBytes)

        runBlocking {
            // 1. Decode using embedded artworkBytes (same source as Media3 notification/lock screen)
            val heroFromBytes = com.example.ui.components.ArtworkCacheManager.loadOrDecodeArtwork(
                context = appContext,
                primaryUri = missingArtUri,
                fallbackUri = "content://media/external/audio/media/777",
                fallbackPath = null,
                artworkBytes = pngBytes,
                sizePx = 512
            )
            org.junit.Assert.assertNotNull("Expected 512px Now Playing hero artwork to decode from embedded bytes", heroFromBytes)

            // 2. Verify primaryUri is now cached and verified at 512px
            val cachedLookup = com.example.ui.components.ArtworkCacheManager.getBestAvailableBitmap(
                primaryUri = missingArtUri,
                fallbackUri = "content://media/external/audio/media/777",
                fallbackPath = null,
                sizePx = 512
            )
            org.junit.Assert.assertNotNull("Expected cached hero bitmap to be returned synchronously", cachedLookup.first)
            assertTrue("Expected exact 512px resolution match", cachedLookup.second)

            // 3. Also verify file:// URI decoding for 144px thumbnail and 256px album art
            val fileUriStr = android.net.Uri.fromFile(fallbackFile).toString()
            val thumbBitmap = com.example.ui.components.ArtworkCacheManager.loadOrDecodeArtwork(
                context = appContext,
                primaryUri = fileUriStr,
                fallbackUri = null,
                fallbackPath = fallbackFile.absolutePath,
                artworkBytes = null,
                sizePx = 144
            )
            org.junit.Assert.assertNotNull("Expected 144px thumbnail to decode from file URI", thumbBitmap)
        }
        fallbackFile.delete()
        com.example.ui.components.ArtworkCacheManager.clear()
    }

    @get:org.junit.Rule
    val composeTestRule = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    private fun registerTestMediaStoreProvider(songCount: Int) {
        val mediaColumns = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.ALBUM,
            android.provider.MediaStore.Audio.Media.ALBUM_ID,
            android.provider.MediaStore.Audio.Media.DURATION,
            android.provider.MediaStore.Audio.Media.TRACK,
            android.provider.MediaStore.Audio.Media.YEAR,
            android.provider.MediaStore.Audio.Media.SIZE,
            android.provider.MediaStore.Audio.Media.DATE_ADDED,
            android.provider.MediaStore.Audio.Media.DATA
        )
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            "media",
            object : android.content.ContentProvider() {
                override fun onCreate(): Boolean = true
                override fun query(
                    uri: android.net.Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                    sortOrder: String?
                ): android.database.Cursor {
                    // Simulate Android 13 MediaProvider strict SQLiteQueryBuilder.enforceStrictOrderBy
                    if (sortOrder != null && sortOrder.contains("COLLATE", ignoreCase = true)) {
                        throw IllegalArgumentException("Invalid token COLLATE in sortOrder: $sortOrder")
                    }
                    val cursor = android.database.MatrixCursor(mediaColumns, songCount.coerceAtLeast(1))
                    for (i in 1..songCount) {
                        cursor.addRow(
                            arrayOf<Any?>(
                                i.toLong(),
                                if (i % 9 == 0) null else "Song Title $i",
                                if (i % 5 == 0) "<unknown>" else "Artist ${i % 12}",
                                if (i % 7 == 0) "<unknown>" else "Album ${i % 15}",
                                if (i % 4 == 0) 0L else (i % 15 + 1).toLong(),
                                180_000L + i * 1000L,
                                i,
                                2024,
                                4_000_000L,
                                1_700_000_000L + i,
                                if (i % 11 == 0) null else "/storage/emulated/0/Music/Folder_${i % 6}/song_$i.mp3"
                            )
                        )
                    }
                    return cursor
                }
                override fun getType(uri: android.net.Uri): String? = "audio/mpeg"
                override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
                override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
                override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
            }
        )
    }

    @Test
    @Config(sdk = [33])
    fun `reproduce fresh install permission grant to HomeScreen and all tabs rendering`() {
        val app = ApplicationProvider.getApplicationContext<PulseMusicApp>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        // TEST A & TEST D & TEST F & TEST G on Android 13 (API 33):
        // Fresh install -> Permission denied shows PermissionView -> Grant permissions ->
        // Scans 250 songs (with & without artwork and null fields) -> Home screen & all tabs load
        shadowApp.denyPermissions(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.database.clearAllTables()
        }
        registerTestMediaStoreProvider(250)

        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeTestRule.waitForIdle()
            // TEST D: Permission denied -> App shows safe permission required state
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("permission_view")).assertExists()

            // TEST A: Click Grant Permission button while permission is still denied (launches system dialog),
            // then simulate user tapping "Allow" in the Android 13 system permission dialog
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("grant_permission_btn"))
                .performClick()
            composeTestRule.waitForIdle()

            shadowApp.grantPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
            scenario.onActivity { activity ->
                val shadowActivity = org.robolectric.Shadows.shadowOf(activity)
                val lastReq = shadowActivity.lastRequestedPermission
                val reqCode = lastReq?.requestCode ?: 1
                val perms = lastReq?.requestedPermissions
                    ?: arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
                activity.onRequestPermissionsResult(
                    reqCode,
                    perms,
                    IntArray(perms.size) { android.content.pm.PackageManager.PERMISSION_GRANTED }
                )
            }
            composeTestRule.waitForIdle()

            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                composeTestRule.waitForIdle()
                var loadedCount = 0
                scenario.onActivity { activity ->
                    val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                    loadedCount = vm.libraryState.value.songs.size
                }
                if (loadedCount == 250) break
                Thread.sleep(50)
            }

            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("home_screen")).assertExists()

            // Exercise all tabs (SONGS, ALBUMS, FOLDERS, SETTINGS, HOME)
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_songs")).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("scanned_audio_list_screen")).assertExists()

            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_albums")).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("albums_grid")).assertExists()

            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_folders")).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("folders_list")).assertExists()

            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_settings")).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_home")).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("home_screen")).assertExists()
        }
    }

    @Test
    @Config(sdk = [33])
    fun `clear app data and grant permission on empty device shows HomeScreen safely`() {
        val app = ApplicationProvider.getApplicationContext<PulseMusicApp>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        // TEST B & TEST E: Clear app data -> Empty device (0 songs) -> Grant permissions -> Empty library state loads safely
        shadowApp.denyPermissions(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.database.clearAllTables()
        }
        registerTestMediaStoreProvider(0)

        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("permission_view")).assertExists()

            shadowApp.grantPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("grant_permission_btn"))
                .performClick()
            composeTestRule.waitForIdle()

            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                composeTestRule.waitForIdle()
                var loading = true
                scenario.onActivity { activity ->
                    val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                    loading = vm.libraryState.value.isLoading
                }
                if (!loading) break
                Thread.sleep(50)
            }

            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("home_screen")).assertExists()
            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                assertTrue(vm.libraryState.value.songs.isEmpty())
            }
        }
    }

    @Test
    @Config(sdk = [33])
    fun `permission denied callback keeps PermissionView visible without crashing`() {
        val app = ApplicationProvider.getApplicationContext<PulseMusicApp>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        shadowApp.denyPermissions(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.database.clearAllTables()
        }
        registerTestMediaStoreProvider(50)

        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("permission_view")).assertExists()

            // Click Grant Permission -> user denies in system dialog
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("grant_permission_btn"))
                .performClick()
            composeTestRule.waitForIdle()

            scenario.onActivity { activity ->
                val shadowActivity = org.robolectric.Shadows.shadowOf(activity)
                val lastReq = shadowActivity.lastRequestedPermission
                val reqCode = lastReq?.requestCode ?: 1
                val perms = lastReq?.requestedPermissions
                    ?: arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
                activity.onRequestPermissionsResult(
                    reqCode,
                    perms,
                    IntArray(perms.size) { android.content.pm.PackageManager.PERMISSION_DENIED }
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("permission_view")).assertExists()
        }
    }

    @Test
    @Config(sdk = [33])
    fun `permission already granted on app launch loads library directly to HomeScreen`() {
        val app = ApplicationProvider.getApplicationContext<PulseMusicApp>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        // TEST C: Permission already granted -> Open app -> Library loads directly to HomeScreen
        shadowApp.grantPermissions(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.database.clearAllTables()
        }
        registerTestMediaStoreProvider(25)

        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                composeTestRule.waitForIdle()
                var loadedCount = 0
                scenario.onActivity { activity ->
                    val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                    loadedCount = vm.libraryState.value.songs.size
                }
                if (loadedCount == 25) break
                Thread.sleep(50)
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("home_screen")).assertExists()
        }
    }
}


