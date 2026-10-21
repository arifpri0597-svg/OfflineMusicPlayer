package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FavoriteEntity
import com.example.data.local.HistoryEntity
import com.example.data.local.MusicDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistSongCrossRef
import com.example.data.local.PulseDatabase
import com.example.data.local.SongEntity
import com.example.data.repository.AudioScanRepository
import com.example.data.repository.MediaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: PulseDatabase
    private lateinit var musicDao: MusicDao
    private lateinit var audioScanRepository: AudioScanRepository
    private lateinit var mediaRepository: MediaRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        musicDao = database.musicDao()
        audioScanRepository = AudioScanRepository(context, musicDao)
        mediaRepository = MediaRepository(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createSampleSongEntity(
        id: Long,
        title: String,
        artist: String = "Artist $id",
        album: String = "Album $id",
        folderName: String = "Music",
        path: String = "/storage/emulated/0/Music/$title.mp3",
        year: Int = 2024
    ): SongEntity {
        return SongEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = 180_000L,
            contentUriString = "content://media/external/audio/media/$id",
            albumArtUriString = "content://media/external/audio/albumart/$id",
            trackNumber = 1,
            year = year,
            sizeBytes = 4_000_000L,
            dateAdded = 1_700_000_000L + id,
            folderName = folderName,
            path = path
        )
    }

    @Test
    fun `dao inserts queries and prunes cached songs accurately`() = runBlocking {
        val songs = listOf(
            createSampleSongEntity(1L, "Zebra Track"),
            createSampleSongEntity(2L, "alpha Track"),
            createSampleSongEntity(3L, "Beta Track")
        )
        musicDao.insertSongs(songs)

        // Verify case-insensitive alphabetical ordering
        val cached = musicDao.getAllCachedSongs().first()
        assertEquals(listOf(2L, 3L, 1L), cached.map { it.id })

        // Verify lookup by ID
        val song2 = musicDao.getSongById(2L)
        assertNotNull(song2)
        assertEquals("alpha Track", song2?.title)
        assertNull(musicDao.getSongById(999L))

        // Delete specific IDs
        musicDao.deleteSongsByIds(listOf(3L))
        assertEquals(listOf(1L, 2L), musicDao.getAllCachedSongIds().sorted())

        // Prune songs not in validIds
        musicDao.pruneDeletedSongs(listOf(2L))
        assertEquals(listOf(2L), musicDao.getAllCachedSongIds())

        // Clear all songs
        musicDao.clearSongs()
        assertTrue(musicDao.getAllCachedSongIds().isEmpty())
    }

    @Test
    fun `dao manages favorites and reactive isFavorite flow`() = runBlocking {
        assertFalse(musicDao.isFavorite(10L).first())

        musicDao.insertFavorite(FavoriteEntity(songId = 10L, addedAt = 1000L))
        musicDao.insertFavorite(FavoriteEntity(songId = 20L, addedAt = 2000L))

        assertTrue(musicDao.isFavorite(10L).first())
        // Ordered by addedAt DESC
        assertEquals(listOf(20L, 10L), musicDao.getAllFavoriteIds().first())

        musicDao.deleteFavorite(10L)
        assertFalse(musicDao.isFavorite(10L).first())
        assertEquals(listOf(20L), musicDao.getAllFavoriteIds().first())

        musicDao.clearFavorites()
        assertTrue(musicDao.getAllFavoriteIds().first().isEmpty())
    }

    @Test
    fun `dao records and orders playback history`() = runBlocking {
        musicDao.insertHistory(HistoryEntity(songId = 1L, title = "Track 1", artist = "Artist 1", playedAt = 100L))
        musicDao.insertHistory(HistoryEntity(songId = 2L, title = "Track 2", artist = "Artist 2", playedAt = 300L))
        musicDao.insertHistory(HistoryEntity(songId = 3L, title = "Track 3", artist = "Artist 3", playedAt = 200L))

        val history = musicDao.getHistory().first()
        assertEquals(listOf(2L, 3L, 1L), history.map { it.songId })

        musicDao.clearHistory()
        assertTrue(musicDao.getHistory().first().isEmpty())
    }

    @Test
    fun `dao manages playlists track counts and ordered song cross references`() = runBlocking {
        val songA = createSampleSongEntity(101L, "Song A")
        val songB = createSampleSongEntity(102L, "Song B")
        val songC = createSampleSongEntity(103L, "Song C")
        musicDao.insertSongs(listOf(songA, songB, songC))

        val playlistId = musicDao.insertPlaylist(PlaylistEntity(name = "Road Trip", createdAt = 5000L))
        assertTrue(playlistId > 0L)

        // Add tracks in custom order: C (index 0), A (index 1), B (index 2)
        musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, 103L, orderIndex = 0))
        musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, 101L, orderIndex = 1))
        musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, 102L, orderIndex = 2))

        val playlistsWithCount = musicDao.getPlaylistsWithTrackCount().first()
        assertEquals(1, playlistsWithCount.size)
        assertEquals("Road Trip", playlistsWithCount.first().name)
        assertEquals(3, playlistsWithCount.first().trackCount)

        // Verify getSongsForPlaylist preserves orderIndex
        val playlistSongs = musicDao.getSongsForPlaylist(playlistId).first()
        assertEquals(listOf(103L, 101L, 102L), playlistSongs.map { it.id })

        // Remove middle song and verify
        musicDao.removeSongFromPlaylist(playlistId, 101L)
        assertEquals(2, musicDao.getPlaylistTrackCount(playlistId))
        assertEquals(listOf(103L, 102L), musicDao.getSongIdsForPlaylist(playlistId).first())

        // Delete playlist and its cross-refs
        musicDao.deletePlaylistSongs(playlistId)
        musicDao.deletePlaylist(playlistId)
        assertTrue(musicDao.getAllPlaylists().first().isEmpty())
        assertEquals(0, musicDao.getPlaylistTrackCount(playlistId))
    }

    @Test
    fun `audioScanRepository combines songs flow with favorite state and groups metadata`() = runBlocking {
        val song1 = createSampleSongEntity(
            id = 1L,
            title = "Sunrise",
            artist = "Nova",
            album = "Horizon",
            folderName = "Chill",
            path = "/storage/emulated/0/Music/Chill/Sunrise.mp3",
            year = 2023
        )
        val song2 = createSampleSongEntity(
            id = 2L,
            title = "Sunset",
            artist = "Nova",
            album = "Eclipse",
            folderName = "Chill",
            path = "/storage/emulated/0/Music/Chill/Sunset.mp3",
            year = 2024
        )
        val song3 = createSampleSongEntity(
            id = 3L,
            title = "Midnight",
            artist = "Orion",
            album = "Horizon",
            folderName = "Synth",
            path = "/storage/emulated/0/Music/Synth/Midnight.mp3",
            year = 2022
        )

        musicDao.insertSongs(listOf(song1, song2, song3))
        musicDao.insertFavorite(FavoriteEntity(songId = 2L, addedAt = 1000L))

        val domainSongs = audioScanRepository.getSongsFlow().first()
        assertEquals(3, domainSongs.size)
        val favSong = domainSongs.first { it.id == 2L }
        val nonFavSong = domainSongs.first { it.id == 1L }
        assertTrue(favSong.isFavorite)
        assertFalse(nonFavSong.isFavorite)

        // Verify album aggregation (sorted alphabetically: Eclipse, Horizon)
        val albums = audioScanRepository.getAlbums(domainSongs)
        assertEquals(2, albums.size)
        assertEquals("Eclipse", albums[0].title)
        assertEquals(1, albums[0].songCount)
        assertEquals("Horizon", albums[1].title)
        assertEquals(2, albums[1].songCount)

        // Verify artist aggregation (Nova has 2 songs across 2 distinct albums; Orion has 1 song)
        val artists = audioScanRepository.getArtists(domainSongs)
        assertEquals(2, artists.size)
        assertEquals("Nova", artists[0].name)
        assertEquals(2, artists[0].songCount)
        assertEquals(2, artists[0].albumCount)
        assertEquals("Orion", artists[1].name)
        assertEquals(1, artists[1].songCount)
        assertEquals(1, artists[1].albumCount)

        // Verify folder aggregation
        val folders = audioScanRepository.getFolders(domainSongs)
        assertEquals(2, folders.size)
        assertEquals("Chill", folders[0].name)
        assertEquals("/storage/emulated/0/Music/Chill", folders[0].path)
        assertEquals(2, folders[0].songCount)
        assertEquals("Synth", folders[1].name)
        assertEquals(1, folders[1].songCount)
    }

    @Test
    fun `audioScanRepository and mediaRepository scan MediaStore provider and sanitize unknown metadata`() = runBlocking {
        // Seed a stale song in Room to verify scanAndSyncDeviceAudio prunes deleted MediaStore entries
        musicDao.insertSongs(listOf(createSampleSongEntity(999L, "Stale Deleted Song")))

        FakeMediaStoreProvider.cursorFactory = {
            val columns = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA
            )
            MatrixCursor(columns).apply {
                addRow(
                    arrayOf<Any?>(
                        501L,
                        "Crystal Waves",
                        "Aether",
                        "Oceanic",
                        77L,
                        215_000L,
                        3,
                        2025,
                        5_120_000L,
                        1_710_000_000L,
                        "/storage/emulated/0/Music/Electronic/Crystal Waves.flac"
                    )
                )
                addRow(
                    arrayOf<Any?>(
                        502L,
                        "<unknown>",
                        "<unknown>",
                        "",
                        0L,
                        140_000L,
                        0,
                        0,
                        3_200_000L,
                        1_710_000_100L,
                        "/storage/emulated/0/Downloads/FallbackFileName.mp3"
                    )
                )
            }
        }

        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, "media")

        val syncedEntities = audioScanRepository.scanAndSyncDeviceAudio()
        assertEquals(2, syncedEntities.size)

        // Stale song 999L must be pruned, and 501L + 502L persisted
        val persistedIds = musicDao.getAllCachedSongIds().sorted()
        assertEquals(listOf(501L, 502L), persistedIds)

        // Verify metadata sanitization for <unknown> fields
        val sanitized = musicDao.getSongById(502L)
        assertNotNull(sanitized)
        assertEquals("FallbackFileName", sanitized?.title)
        assertEquals("Unknown Artist", sanitized?.artist)
        assertEquals("Unknown Album", sanitized?.album)
        assertEquals("Downloads", sanitized?.folderName)
        assertNull(sanitized?.albumArtUriString)

        // Also verify MediaRepository queries the same provider accurately
        val mediaRepoSongs = mediaRepository.queryLocalSongs()
        assertEquals(2, mediaRepoSongs.size)
        assertEquals(2, mediaRepository.queryAlbums(mediaRepoSongs).size)
        assertEquals(2, mediaRepository.queryArtists(mediaRepoSongs).size)
        assertEquals(2, mediaRepository.queryFolders(mediaRepoSongs).size)
    }

    @Test
    fun `playbackStateRepository saves gets updates and clears playback state while preserving history`() = runBlocking {
        val repo = com.example.data.repository.PlaybackStateRepository(context, musicDao)
        repo.clearPlaybackState()

        val song1 = createSampleSongEntity(10L, "Neon Horizon", artist = "Synthwave").toDomainSong()
        val song2 = createSampleSongEntity(20L, "Midnight Drive", artist = "Outrun").toDomainSong()
        musicDao.insertSongs(
            listOf(
                com.example.data.local.SongEntity.fromDomainSong(song1),
                com.example.data.local.SongEntity.fromDomainSong(song2)
            )
        )
        musicDao.insertHistory(HistoryEntity(songId = 10L, title = song1.title, artist = song1.artist, playedAt = 1000L))

        // Save position at 0:42 (42_000L)
        repo.savePlaybackState(
            song = song1,
            positionMs = 42_000L,
            durationMs = 180_000L,
            queueSongIds = listOf(10L, 20L),
            currentIndex = 0,
            isPlaying = false,
            shuffleEnabled = true,
            repeatMode = com.example.data.model.RepeatMode.ALL
        )

        val saved = repo.getLastPlaybackState()
        assertNotNull(saved)
        assertEquals(10L, saved?.songId)
        assertEquals(42_000L, saved?.positionMs)
        assertEquals(180_000L, saved?.durationMs)
        assertEquals(listOf(10L, 20L), saved?.parseQueueSongIds())
        assertEquals(0, saved?.currentIndex)
        assertFalse(saved?.isPlaying ?: true)
        assertTrue(saved?.shuffleEnabled ?: false)
        assertEquals("ALL", saved?.repeatMode)

        // Update position via seek to 1:35 (95_000L)
        repo.savePlaybackState(
            song = song1,
            positionMs = 95_000L,
            durationMs = 180_000L,
            queueSongIds = listOf(10L, 20L),
            currentIndex = 0,
            isPlaying = true
        )
        val updated = audioScanRepository.getLastPlaybackState()
        assertEquals(95_000L, updated?.positionMs)

        // Verify Recently Played (history) is completely untouched
        val history = musicDao.getHistory().first()
        assertEquals(1, history.size)
        assertEquals(10L, history.first().songId)

        // Clear playback state
        repo.clearPlaybackState()
        assertNull(repo.getLastPlaybackState())
        // History still intact after clearing playback state
        assertEquals(1, musicDao.getHistory().first().size)
    }

    @Test
    fun `playbackStateRepository handles edge cases clamping moved deleted and corrupted states`() = runBlocking {
        val repo = com.example.data.repository.PlaybackStateRepository(context, musicDao)
        repo.clearPlaybackState()

        val activeSong = createSampleSongEntity(
            id = 55L,
            title = "Starlight Echo",
            artist = "Luna"
        ).copy(durationMs = 150_000L).toDomainSong()
        val secondSong = createSampleSongEntity(
            id = 66L,
            title = "Deep Space",
            artist = "Luna"
        ).toDomainSong()
        val librarySongs = listOf(activeSong, secondSong)

        // 1. Position greater than actual track duration (e.g. duration changed from 240s to 150s, saved pos 200s)
        val overflowState = com.example.data.local.PlaybackStateEntity(
            songId = 55L,
            contentUriString = activeSong.contentUri.toString(),
            title = activeSong.title,
            artist = activeSong.artist,
            positionMs = 200_000L,
            durationMs = 240_000L,
            queueSongIdsCsv = "55,66,999", // 999 was deleted
            currentIndex = 0
        )
        val clampedSession = repo.resolveRestoredSession(overflowState, librarySongs)
        assertNotNull(clampedSession)
        assertEquals(150_000L, clampedSession?.durationMs)
        assertEquals(150_000L, clampedSession?.positionMs)
        // Deleted song 999 is pruned from restored queue
        assertEquals(listOf(55L, 66L), clampedSession?.queue?.map { it.id })

        // 2. Negative position clamps to 0L
        val negativePosState = overflowState.copy(positionMs = -15_000L)
        val zeroClampedSession = repo.resolveRestoredSession(negativePosState, librarySongs)
        assertEquals(0L, zeroClampedSession?.positionMs)

        // 3. Moved/re-indexed audio file (old ID 111 and old URI, but same Title + Artist in library)
        val movedState = com.example.data.local.PlaybackStateEntity(
            songId = 111L,
            contentUriString = "content://media/external/audio/media/111",
            title = "Starlight Echo",
            artist = "Luna",
            positionMs = 42_000L,
            durationMs = 150_000L
        )
        val movedSession = repo.resolveRestoredSession(movedState, librarySongs)
        assertNotNull("Moved file should resolve by title and artist", movedSession)
        assertEquals(55L, movedSession?.currentSong?.id)
        assertEquals(42_000L, movedSession?.positionMs)

        // 4. Deleted audio file (not in librarySongs and MediaStore URI no longer exists)
        val deletedState = com.example.data.local.PlaybackStateEntity(
            songId = 888L,
            contentUriString = "content://media/external/audio/media/888",
            title = "Deleted Track",
            artist = "Ghost",
            positionMs = 30_000L,
            durationMs = 180_000L
        )
        repo.savePlaybackState(deletedState)
        val deletedSession = repo.resolveRestoredSession(deletedState, librarySongs)
        assertNull("Deleted song should not restore", deletedSession)
        assertNull("Deleted song state should be cleared", repo.getLastPlaybackState())

        // 5. Invalid/corrupted media URI (disallowed http scheme or empty)
        val invalidUriState = com.example.data.local.PlaybackStateEntity(
            songId = 55L,
            contentUriString = "http://example.com/malicious.mp3",
            positionMs = 10_000L,
            durationMs = 150_000L
        )
        repo.savePlaybackState(invalidUriState)
        assertNull("Invalid URI scheme must be rejected", repo.getLastPlaybackState())
    }

    @Test
    fun `database MIGRATION_3_4 and MIGRATION_4_5 safely create and upgrade playback_state table without losing existing data`() = runBlocking {
        val supportDb = database.openHelper.writableDatabase
        PulseDatabase.MIGRATION_3_4.migrate(supportDb)
        PulseDatabase.MIGRATION_4_5.migrate(supportDb)

        val entity = com.example.data.local.PlaybackStateEntity(
            songId = 77L,
            contentUriString = "content://media/external/audio/media/77",
            title = "Migrated Song",
            artist = "Migrated Artist",
            positionMs = 42_000L,
            durationMs = 180_000L,
            playbackSpeed = 1.25f,
            isExternal = false
        )
        musicDao.upsertPlaybackState(entity)
        val loaded = musicDao.getPlaybackState()
        assertNotNull(loaded)
        assertEquals(77L, loaded?.songId)
        assertEquals(42_000L, loaded?.positionMs)
        assertEquals(1.25f, loaded?.playbackSpeed ?: 1.0f, 0.001f)
    }

    class FakeMediaStoreProvider : ContentProvider() {
        companion object {
            var cursorFactory: (() -> Cursor?)? = null
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = cursorFactory?.invoke()

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}

