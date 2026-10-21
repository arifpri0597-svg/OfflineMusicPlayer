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
            "application/ogg"   // OGG
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
}
