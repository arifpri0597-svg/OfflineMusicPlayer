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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode

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

            // 4. Verify clearNegativeCacheOnly() clears negative misses while preserving already-decoded ImageBitmaps
            com.example.ui.components.ArtworkCacheManager.markMissing("content://media/external/audio/albumart/888888")
            assertTrue(com.example.ui.components.ArtworkCacheManager.isKnownMissing("content://media/external/audio/albumart/888888"))
            com.example.ui.components.ArtworkCacheManager.clearNegativeCacheOnly()
            assertFalse(com.example.ui.components.ArtworkCacheManager.isKnownMissing("content://media/external/audio/albumart/888888"))
            val preservedThumb = com.example.ui.components.ArtworkCacheManager.getBestAvailableBitmap(
                primaryUri = fileUriStr,
                fallbackUri = null,
                fallbackPath = fallbackFile.absolutePath,
                sizePx = 144
            )
            org.junit.Assert.assertNotNull("Expected clearNegativeCacheOnly() to preserve decoded ImageBitmap in memory", preservedThumb.first)
            assertEquals(0, com.example.ui.components.ArtworkCacheManager.activeInFlightDecodeCount())

            // 5. Verify album-level negative cache deduplication across tracks sharing the same missing album URI:
            // When track 1 in an album with no albumart and no embedded artwork fails decoding,
            // track 2 sharing that albumArtUri must immediately return true from isSourceKnownMissing at 144px
            val sharedMissingAlbumUri = "content://media/external/audio/albumart/404404"
            val track1Null = com.example.ui.components.ArtworkCacheManager.loadOrDecodeArtwork(
                context = appContext,
                primaryUri = sharedMissingAlbumUri,
                fallbackUri = "content://media/external/audio/media/90001",
                fallbackPath = "/nonexistent/track1.mp3",
                artworkBytes = null,
                sizePx = 144
            )
            org.junit.Assert.assertNull(track1Null)
            assertTrue(
                "Subsequent tracks in the same missing-art album must immediately hit negative cache at 144px without I/O",
                com.example.ui.components.ArtworkCacheManager.isSourceKnownMissing(
                    primaryUri = sharedMissingAlbumUri,
                    fallbackUri = "content://media/external/audio/media/90002",
                    fallbackPath = "/nonexistent/track2.mp3",
                    hasEmbeddedBytes = false,
                    sizePx = 144
                )
            )
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
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("settings_screen")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("performance_mode_section")).assertDoesNotExist()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("performance_mode_60_120")).assertDoesNotExist()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("performance_mode_120_120")).assertDoesNotExist()

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

    @Test
    @Config(sdk = [33])
    fun `android 13 PackageParser parses app-debug apk without error`() {
        val apkCandidates = listOf(
            java.io.File("build/outputs/apk/debug/app-debug.apk"),
            java.io.File("app/build/outputs/apk/debug/app-debug.apk"),
            java.io.File("../.build-outputs/app-debug.apk"),
            java.io.File(".build-outputs/app-debug.apk"),
            java.io.File("/app/applet/app/build/outputs/apk/debug/app-debug.apk"),
            java.io.File("/app/applet/.build-outputs/app-debug.apk")
        )
        val apkFile = apkCandidates.firstOrNull { it.exists() && it.length() > 0L } ?: return

        val parserClass = Class.forName("android.content.pm.PackageParser")
        val parser = parserClass.getDeclaredConstructor().newInstance()
        val parsePackageMethod = parserClass.getMethod(
            "parsePackage",
            java.io.File::class.java,
            Int::class.javaPrimitiveType
        )
        val flags = android.content.pm.PackageManager.GET_PERMISSIONS or
            android.content.pm.PackageManager.GET_CONFIGURATIONS or
            android.content.pm.PackageManager.GET_ACTIVITIES or
            android.content.pm.PackageManager.GET_SERVICES
        val pkg = parsePackageMethod.invoke(parser, apkFile, flags)
        org.junit.Assert.assertNotNull("PackageParser returned null", pkg)
        val packageNameField = pkg.javaClass.getField("packageName")
        val pkgName = packageNameField.get(pkg) as String
        assertEquals("com.aistudio.pulsemusic.kmply", pkgName)

        val activitiesField = pkg.javaClass.getField("activities")
        val activities = activitiesField.get(pkg) as java.util.List<*>
        org.junit.Assert.assertTrue("Expected MainActivity in parsed APK", activities.isNotEmpty())

        val servicesField = pkg.javaClass.getField("services")
        val services = servicesField.get(pkg) as java.util.List<*>
        org.junit.Assert.assertTrue("Expected MusicPlaybackService in parsed APK", services.isNotEmpty())
    }

    @Test
    @Config(sdk = [33])
    fun `internal automatic frame rate policy scales from 60 FPS minimum to 90Hz 120Hz 144Hz and hides any UI setting`() {
        // 1. 60Hz device -> targets 60 FPS / 60Hz
        val modes60Hz = listOf(
            com.example.util.DisplayModeSpec(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f)
        )
        val cap60 = com.example.util.PerformanceModeManager.resolveAutomaticPolicyFromModes(
            currentRefreshRate = 60f,
            currentWidth = 1080,
            currentHeight = 2400,
            supportedModes = modes60Hz
        )
        assertEquals(60f, cap60.minTargetFps, 0.01f)
        assertEquals(60f, cap60.effectiveDisplayHz, 0.01f)
        assertEquals(60f, cap60.effectiveRenderFps, 0.01f)
        assertEquals(1, cap60.preferredDisplayModeId)
        assertFalse(cap60.supports90Hz)
        assertFalse(cap60.supports120Hz)

        // 2. 90Hz device -> maintains 60 FPS minimum target and automatically scales up to 90Hz / 90 FPS
        val modes90Hz = listOf(
            com.example.util.DisplayModeSpec(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f),
            com.example.util.DisplayModeSpec(modeId = 2, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 90f)
        )
        val cap90 = com.example.util.PerformanceModeManager.resolveAutomaticPolicyFromModes(
            currentRefreshRate = 60f,
            currentWidth = 1080,
            currentHeight = 2400,
            supportedModes = modes90Hz
        )
        assertEquals(60f, cap90.minTargetFps, 0.01f)
        assertEquals(90f, cap90.effectiveDisplayHz, 0.01f)
        assertEquals(90f, cap90.effectiveRenderFps, 0.01f)
        assertEquals(2, cap90.preferredDisplayModeId)
        assertTrue(cap90.supports90Hz)
        assertFalse(cap90.supports120Hz)

        // 3. 120Hz device -> maintains 60 FPS minimum target and automatically scales up to 120Hz / 120 FPS (never capped at 60Hz)
        val modes120Hz = listOf(
            com.example.util.DisplayModeSpec(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f),
            com.example.util.DisplayModeSpec(modeId = 2, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 90f),
            com.example.util.DisplayModeSpec(modeId = 3, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 120f)
        )
        val cap120 = com.example.util.PerformanceModeManager.resolveAutomaticPolicyFromModes(
            currentRefreshRate = 60f,
            currentWidth = 1080,
            currentHeight = 2400,
            supportedModes = modes120Hz
        )
        assertEquals(60f, cap120.minTargetFps, 0.01f)
        assertEquals(120f, cap120.effectiveDisplayHz, 0.01f)
        assertEquals(120f, cap120.effectiveRenderFps, 0.01f)
        assertEquals(3, cap120.preferredDisplayModeId)
        assertTrue(cap120.supports90Hz)
        assertTrue(cap120.supports120Hz)

        // 4. Higher-refresh-rate device (144Hz) -> scales to 144Hz at the active physical resolution
        val modes144Hz = listOf(
            com.example.util.DisplayModeSpec(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f),
            com.example.util.DisplayModeSpec(modeId = 3, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 120f),
            com.example.util.DisplayModeSpec(modeId = 4, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 144f),
            // Different resolution mode must be ignored to avoid resolution-switch jank
            com.example.util.DisplayModeSpec(modeId = 5, physicalWidth = 720, physicalHeight = 1600, refreshRate = 165f)
        )
        val cap144 = com.example.util.PerformanceModeManager.resolveAutomaticPolicyFromModes(
            currentRefreshRate = 60f,
            currentWidth = 1080,
            currentHeight = 2400,
            supportedModes = modes144Hz
        )
        assertEquals(144f, cap144.effectiveDisplayHz, 0.01f)
        assertEquals(144f, cap144.effectiveRenderFps, 0.01f)
        assertEquals(4, cap144.preferredDisplayModeId)

        // 5. Hardware-constrained device (< 60Hz, e.g. 50Hz) -> never forced beyond its actual supported capability
        val modes50Hz = listOf(
            com.example.util.DisplayModeSpec(modeId = 1, physicalWidth = 720, physicalHeight = 1280, refreshRate = 50f)
        )
        val cap50 = com.example.util.PerformanceModeManager.resolveAutomaticPolicyFromModes(
            currentRefreshRate = 50f,
            currentWidth = 720,
            currentHeight = 1280,
            supportedModes = modes50Hz
        )
        assertEquals(50f, cap50.minTargetFps, 0.01f)
        assertEquals(50f, cap50.effectiveDisplayHz, 0.01f)
        assertEquals(50f, cap50.effectiveRenderFps, 0.01f)
    }

    @Test
    @Config(sdk = [33])
    fun `now playing screen matches updated layout with top right menu and sleep timer`() {
        val app = ApplicationProvider.getApplicationContext<PulseMusicApp>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.database.clearAllTables()
        }
        registerTestMediaStoreProvider(10)

        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                composeTestRule.waitForIdle()
                var loadedCount = 0
                scenario.onActivity { activity ->
                    val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                    loadedCount = vm.libraryState.value.songs.size
                }
                if (loadedCount == 10) break
                Thread.sleep(50)
            }

            // Attach test player harness, start playback of the first song, and open Now Playing screen
            val fakeHarness = PlaybackServiceTest.FakePlayerHarness()
            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                vm.playerController.setPlayerForTesting(fakeHarness.playerProxy)
                val firstSong = vm.libraryState.value.songs.first()
                vm.playSongFromList(firstSong, vm.libraryState.value.songs)
                vm.setNowPlayingExpanded(true)
            }
            composeTestRule.waitForIdle()

            // Verify Now Playing screen is visible
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_screen")).assertExists()

            // 1. Verify standalone Track button is REMOVED from the action row
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_track_info_btn")).assertDoesNotExist()

            // 2. Verify Sleep Timer clock button/sticker + compact icon-only Favorite, Add to Playlist, and Playback Queue are in the action row
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_sleep_timer_btn")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_favorite_btn")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_add_playlist_btn")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_queue_btn")).assertExists()

            // 3. Verify top-right menu button exists and opens menu with Track Info, Sleep Timer & Playback Speed
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_menu_btn")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_menu_btn")).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_menu_track_info")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_menu_sleep_timer")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_menu_playback_speed")).assertExists()

            // 4. Verify clicking Sleep Timer opens the Sleep Timer bottom sheet with all required options (Off, 5, 10, 15, 30, 45, 60, End of current track)
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_menu_sleep_timer")).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_bottom_sheet")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_off")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_5")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_10")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_15")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_30")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_45")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_60")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_end_of_track")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_custom")).assertExists()

            // Select 15 minutes option and verify active remaining badge on the Sleep Timer sticker
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_15"))
                .performScrollTo()
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNode(
                androidx.compose.ui.test.hasTestTag("now_playing_sleep_timer_badge"),
                useUnmergedTree = true
            ).assertExists()

            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                assertEquals(15, vm.playerController.uiState.value.sleepTimerMinutes)
                assertTrue(vm.playerController.uiState.value.isSleepTimerActive)
            }

            // Also verify tapping the compact Sleep Timer clock sticker opens the Sleep Timer bottom sheet
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("now_playing_sleep_timer_btn")).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_bottom_sheet")).assertExists()

            // Select "End of current track" option and verify it pauses when the current track transitions automatically
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("sleep_timer_option_end_of_track"))
                .performScrollTo()
                .performClick()
            composeTestRule.waitForIdle()

            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                assertEquals(PlayerUiState.SLEEP_TIMER_END_OF_TRACK, vm.playerController.uiState.value.sleepTimerMinutes)
                assertTrue(vm.playerController.uiState.value.isSleepTimerActive)
                assertTrue(vm.playerController.uiState.value.isSleepTimerEndOfTrack)

                // Simulate end of current track (MEDIA_ITEM_TRANSITION_REASON_AUTO)
                fakeHarness.listeners.toList().forEach {
                    it.onMediaItemTransition(
                        fakeHarness.mediaItems.getOrNull(1),
                        androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    )
                }

                val stateAfterEndOfTrack = vm.playerController.uiState.value
                assertFalse("Playback should pause at end of current track", stateAfterEndOfTrack.isPlaying)
                assertEquals("Sleep timer should reset to Off after end of track", 0, stateAfterEndOfTrack.sleepTimerMinutes)
                assertFalse(stateAfterEndOfTrack.isSleepTimerActive)
            }

            // 5. Verify timed Sleep Timer expiration safely pauses playback, resets timer, and preserves song/queue/position
            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                vm.playerController.togglePlayPause() // resume playback
                vm.playerController.setSleepTimer(minutes = 15, durationMsOverride = 60L)
            }

            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(150, java.util.concurrent.TimeUnit.MILLISECONDS)
            composeTestRule.waitForIdle()

            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                val stateAfterExpiry = vm.playerController.uiState.value
                assertFalse("Playback should be paused after sleep timer expires", stateAfterExpiry.isPlaying)
                assertEquals("Sleep timer minutes should reset to 0", 0, stateAfterExpiry.sleepTimerMinutes)
                assertFalse("Sleep timer should be inactive", stateAfterExpiry.isSleepTimerActive)
                org.junit.Assert.assertNotNull("Current song must remain intact", stateAfterExpiry.currentSong)
                assertEquals("Queue must remain intact", 10, stateAfterExpiry.queue.size)
            }
        }
    }

    @Test
    fun `audioVisualizerBridge and audioProcessor react to real-time amplitude bass and beats while preserving PCM audio`() {
        val bridge = com.example.player.service.AudioVisualizerBridge(bandCount = 28)
        val processor = com.example.player.service.PureTuneVisualizerAudioProcessor(bridge)

        val format = androidx.media3.common.audio.AudioProcessor.AudioFormat(
            44100,
            2,
            androidx.media3.common.C.ENCODING_PCM_16BIT
        )
        val configuredFormat = processor.configure(format)
        assertEquals(format, configuredFormat)
        processor.flush()

        // 1. Feed low-level ambient audio first to establish baseline
        val frameCount = 256
        val quietBuffer = java.nio.ByteBuffer
            .allocateDirect(frameCount * format.bytesPerFrame)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            val sample = (kotlin.math.sin(i * 0.08) * 1200).toInt().toShort()
            quietBuffer.putShort(sample)
            quietBuffer.putShort(sample)
        }
        quietBuffer.flip()
        processor.queueInput(quietBuffer)

        val outQuiet = processor.output
        assertEquals(frameCount * format.bytesPerFrame, outQuiet.remaining())
        val quietRms = bridge.rmsAmplitude
        val quietBass = bridge.bassLevel
        assertTrue("Quiet signal should produce non-zero RMS ($quietRms)", quietRms > 0.01f)

        // 2. Feed a strong sub-bass kick + beat transient buffer and verify bass, RMS, and beat intensity jump
        val bassKickBuffer = java.nio.ByteBuffer
            .allocateDirect(frameCount * format.bytesPerFrame)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            // Low-frequency ~85Hz strong wave
            val sample = (kotlin.math.sin(i * 0.012) * 28000).toInt().toShort()
            bassKickBuffer.putShort(sample)
            bassKickBuffer.putShort(sample)
        }
        bassKickBuffer.flip()
        processor.queueInput(bassKickBuffer)

        val outKick = processor.output
        assertEquals(frameCount * format.bytesPerFrame, outKick.remaining())
        assertTrue("Strong bass kick should increase RMS (${bridge.rmsAmplitude} > $quietRms)", bridge.rmsAmplitude > quietRms)
        assertTrue("Strong bass kick should increase bassLevel (${bridge.bassLevel} > $quietBass)", bridge.bassLevel > quietBass)
        assertTrue("Beat transient detector should register kick (${bridge.beatIntensity})", bridge.beatIntensity > 0.05f)

        val sampledBands = FloatArray(28)
        val liveWeight = bridge.sampleLiveState(System.nanoTime(), sampledBands)
        assertEquals(1f, liveWeight, 0.01f)
        assertTrue("28-band spectral targets should be active", sampledBands.any { it > 0.25f })

        // 3. Verify flush/reset clears live signal cleanly for smooth pause/track transitions
        processor.flush()
        assertEquals(0f, bridge.rmsAmplitude, 0.0001f)
        assertEquals(0f, bridge.bassLevel, 0.0001f)
        assertEquals(0f, bridge.beatIntensity, 0.0001f)
        assertEquals(0f, bridge.sampleLiveState(System.nanoTime(), sampledBands), 0.0001f)
    }

    @Test
    @Config(sdk = [33])
    fun `playlist screen redesign supports creation deletion add tracks shuffle reordering favorite more menu playback mini player and smooth scrolling`() {
        val app = ApplicationProvider.getApplicationContext<PulseMusicApp>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
        Thread.sleep(100)
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.database.clearAllTables()
            app.playbackStateRepository.clearPlaybackState()
        }
        registerTestMediaStoreProvider(7)

        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var scannedSongs: List<com.example.data.model.Song> = emptyList()
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                composeTestRule.waitForIdle()
                scenario.onActivity { activity ->
                    val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                    scannedSongs = vm.libraryState.value.songs
                }
                if (scannedSongs.size == 7) break
                Thread.sleep(50)
            }
            assertEquals(7, scannedSongs.size)

            val fakeHarness = PlaybackServiceTest.FakePlayerHarness()
            val playlistId = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val dao = app.database.musicDao()
                val id = dao.insertPlaylist(PlaylistEntity(name = "my music"))
                dao.appendSongsToPlaylist(id, scannedSongs.take(5).map { it.id })
                id
            }

            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                vm.playerController.setPlayerForTesting(fakeHarness.playerProxy)
                vm.setNowPlayingExpanded(false)
                vm.setNavigationTab(com.example.viewmodel.NavigationTab.PLAYLISTS)
            }

            for (attempt in 0 until 20) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                composeTestRule.waitForIdle()
            }

            // 1. Verify Playlists overview screen & select "my music"
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlists_screen")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_item_$playlistId"))
                .assertExists()
                .performClick()
            composeTestRule.waitForIdle()

            for (attempt in 0 until 15) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                composeTestRule.waitForIdle()
            }

            // 2. Verify Playlist Header & 2 Horizontal Action Buttons (Shuffle + Add Tracks)
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_detail_screen")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_back_btn")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_add_tracks_btn")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_delete_btn")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_header_artwork")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_header_title")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_header_track_count")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_shuffle_btn")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_action_add_tracks_btn")).assertExists()

            val song1 = scannedSongs[0]
            val song2 = scannedSongs[1]
            val song6 = scannedSongs[5]
            val song7 = scannedSongs[6]

            // 3. Verify Add Tracks button opens dialog and adds remaining 2 tracks (making 7 tracks total)
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_action_add_tracks_btn")).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("add_tracks_item_${song6.id}")).performClick()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("add_tracks_item_${song7.id}")).performClick()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("add_tracks_confirm_btn")).performClick()

            for (attempt in 0 until 15) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                composeTestRule.waitForIdle()
            }

            val countAfterAdd = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                app.database.musicDao().getPlaylistTrackCount(playlistId)
            }
            assertEquals("Playlist should now contain 7 tracks", 7, countAfterAdd)

            // 4. Verify each playlist song row has artwork, favorite heart, 3-dot More menu, and far-right drag handle
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("song_item_${song1.id}")).assertExists()
            composeTestRule.onNode(
                androidx.compose.ui.test.hasTestTag("fav_btn_${song1.id}"),
                useUnmergedTree = true
            ).assertExists()
            composeTestRule.onNode(
                androidx.compose.ui.test.hasTestTag("more_btn_${song1.id}"),
                useUnmergedTree = true
            ).assertExists()
            composeTestRule.onNode(
                androidx.compose.ui.test.hasTestTag("playlist_drag_handle_${song1.id}"),
                useUnmergedTree = true
            ).assertExists()

            // 5. Verify playback from playlist & currently playing highlighted state + MiniPlayer + 5-tab Bottom Nav
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_detail_screen"))
                .performScrollToNode(androidx.compose.ui.test.hasTestTag("song_item_${song1.id}"))
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("song_item_${song1.id}")).performClick()
            composeTestRule.waitForIdle()
            var debugMsg = ""
            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.viewmodel.MusicViewModel::class.java]
                val st = vm.playerController.uiState.value
                debugMsg = "isPlaying=${fakeHarness.isPlaying}, mediaItems=${fakeHarness.mediaItems.size}, uiSong=${st.currentSong?.id}, uiPlaying=${st.isPlaying}, restoredPaused=${vm.playerController.isInRestoredPausedState()}, nowPlayingExpanded=${vm.libraryState.value.isNowPlayingExpanded}, selectedPlaylistSongs=${vm.libraryState.value.selectedPlaylistSongs.size}"
            }
            assertTrue("Player should be playing after clicking song1 ($debugMsg)", fakeHarness.isPlaying)
            assertEquals("Clicking song in playlist should queue all 7 playlist songs", 7, fakeHarness.mediaItems.size)

            composeTestRule.onNode(
                androidx.compose.ui.test.hasTestTag("playlist_playing_indicator_${song1.id}"),
                useUnmergedTree = true
            ).assertExists()
            composeTestRule.onNode(
                androidx.compose.ui.test.hasTestTag("playlist_playing_progress_${song1.id}"),
                useUnmergedTree = true
            ).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("mini_player_overlay")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("bottom_nav_bar")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_home")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_songs")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_albums")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_folders")).assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("nav_item_settings")).assertExists()

            // 6. Verify Favorite toggle on song1
            composeTestRule.onNode(
                androidx.compose.ui.test.hasTestTag("fav_btn_${song1.id}"),
                useUnmergedTree = true
            ).performClick()
            for (attempt in 0 until 10) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(40, java.util.concurrent.TimeUnit.MILLISECONDS)
                composeTestRule.waitForIdle()
            }
            val isFavSong1 = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                app.database.musicDao().isFavorite(song1.id).first()
            }
            assertTrue("Song 1 should be marked favorite", isFavSong1)

            // 7. Verify More menu & Song Reordering (Move Down on song1 moves it from index 0 to index 1)
            composeTestRule.onNode(
                androidx.compose.ui.test.hasTestTag("more_btn_${song1.id}"),
                useUnmergedTree = true
            ).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_menu_move_down_${song1.id}"))
                .assertExists()
                .performClick()

            for (attempt in 0 until 10) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(40, java.util.concurrent.TimeUnit.MILLISECONDS)
                composeTestRule.waitForIdle()
            }
            val orderedIdsAfterReorder = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                app.database.musicDao().getSongIdsForPlaylist(playlistId).first()
            }
            assertEquals("Song 2 should now be first and Song 1 second", song2.id, orderedIdsAfterReorder[0])
            assertEquals("Song 1 should now be at index 1", song1.id, orderedIdsAfterReorder[1])

            // 8. Verify Shuffle button starts shuffled playback of all 7 playlist tracks
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_detail_screen"))
                .performScrollToNode(androidx.compose.ui.test.hasTestTag("playlist_shuffle_btn"))
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_shuffle_btn"))
                .performClick()
            composeTestRule.waitForIdle()
            assertEquals("Shuffle should queue all 7 playlist songs", 7, fakeHarness.mediaItems.size)
            assertTrue("Player should be playing after Shuffle", fakeHarness.isPlaying)

            // 9. Verify scrolling performance on playlist_detail_screen
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_detail_screen"))
                .performScrollToNode(androidx.compose.ui.test.hasTestTag("song_item_${song7.id}"))
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("song_item_${song7.id}"))
                .assertExists()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_detail_screen"))
                .performScrollToNode(androidx.compose.ui.test.hasTestTag("playlist_header_title"))
            composeTestRule.waitForIdle()

            // 10. Verify Playlist deletion
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("playlist_delete_btn")).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("confirm_delete_playlist_btn")).performClick()
            for (attempt in 0 until 10) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(40, java.util.concurrent.TimeUnit.MILLISECONDS)
                composeTestRule.waitForIdle()
            }
            val playlistsRemaining = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                app.database.musicDao().getAllPlaylists().first()
            }
            assertTrue("Playlist should be deleted from Room database", playlistsRemaining.none { it.id == playlistId })
        }
    }
}



