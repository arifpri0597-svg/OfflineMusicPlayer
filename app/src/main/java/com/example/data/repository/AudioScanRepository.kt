package com.example.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.data.local.MusicDao
import com.example.data.local.SongEntity
import com.example.data.model.Album
import com.example.data.model.Artist
import com.example.data.model.Folder
import com.example.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository that queries the Android MediaStore API to scan local audio files
 * stored on the device and maps them directly into our local Room database schema (SongEntity).
 */
class AudioScanRepository(
    private val context: Context,
    private val musicDao: MusicDao
) {
    private val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

    /**
     * Scans the device's external storage for audio files using Android MediaStore API,
     * maps each cursor entry to our local database schema (SongEntity), and persists/syncs
     * the records in Room.
     *
     * @return List of newly mapped SongEntity items stored in the local database.
     */
    suspend fun scanAndSyncDeviceAudio(): List<SongEntity> = withContext(Dispatchers.IO) {
        val scannedEntities = mutableListOf<SongEntity>()
        val contentResolver: ContentResolver = context.contentResolver

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
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

        // Filter out non-music and files shorter than 3 seconds (ringtones/clicks/UI sounds)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 3000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        try {
            contentResolver.query(
                collectionUri,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val entity = mapCursorToSongEntity(
                        cursor = cursor,
                        collectionUri = collectionUri,
                        idCol = idCol,
                        titleCol = titleCol,
                        artistCol = artistCol,
                        albumCol = albumCol,
                        albumIdCol = albumIdCol,
                        durationCol = durationCol,
                        trackCol = trackCol,
                        yearCol = yearCol,
                        sizeCol = sizeCol,
                        dateAddedCol = dateAddedCol,
                        dataCol = dataCol
                    )
                    scannedEntities.add(entity)
                }
            }

            // Sync with local Room database:
            // 1. Insert or update all scanned songs in local database in safe chunks
            if (scannedEntities.isNotEmpty()) {
                val existingIds = musicDao.getAllCachedSongIds()
                val activeIdSet = scannedEntities.mapTo(HashSet()) { it.id }
                val staleIds = existingIds.filter { !activeIdSet.contains(it) }
                staleIds.chunked(500).forEach { chunk ->
                    musicDao.deleteSongsByIds(chunk)
                }
                scannedEntities.chunked(500).forEach { chunk ->
                    musicDao.insertSongs(chunk)
                }
            } else {
                musicDao.clearSongs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        scannedEntities
    }

    /**
     * Maps a single MediaStore Cursor row to our Room local database SongEntity schema.
     */
    private fun mapCursorToSongEntity(
        cursor: Cursor,
        collectionUri: Uri,
        idCol: Int,
        titleCol: Int,
        artistCol: Int,
        albumCol: Int,
        albumIdCol: Int,
        durationCol: Int,
        trackCol: Int,
        yearCol: Int,
        sizeCol: Int,
        dateAddedCol: Int,
        dataCol: Int
    ): SongEntity {
        val id = cursor.getLong(idCol)
        val rawTitle = cursor.getString(titleCol)
        val rawArtist = cursor.getString(artistCol)
        val rawAlbum = cursor.getString(albumCol)
        val albumId = cursor.getLong(albumIdCol)
        val duration = cursor.getLong(durationCol)
        val track = if (trackCol != -1) cursor.getInt(trackCol) else 0
        val year = if (yearCol != -1) cursor.getInt(yearCol) else 0
        val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
        val dateAdded = if (dateAddedCol != -1) cursor.getLong(dateAddedCol) else 0L
        val filePath = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""

        val contentUri = ContentUris.withAppendedId(collectionUri, id)
        val albumArtUri = if (albumId > 0) {
            ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
        } else null

        val safeTitle = if (rawTitle.isNullOrBlank() || rawTitle == "<unknown>") {
            if (filePath.isNotBlank()) {
                val name = File(filePath).nameWithoutExtension
                if (name.isNotBlank()) name else "Unknown Title"
            } else "Unknown Title"
        } else rawTitle.trim()

        val safeArtist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") {
            "Unknown Artist"
        } else rawArtist.trim()

        val safeAlbum = if (rawAlbum.isNullOrBlank() || rawAlbum == "<unknown>") {
            "Unknown Album"
        } else rawAlbum.trim()

        val folderName = if (filePath.isNotBlank()) {
            File(filePath).parentFile?.name ?: "Music"
        } else "Music"

        return SongEntity(
            id = id,
            title = safeTitle,
            artist = safeArtist,
            album = safeAlbum,
            durationMs = duration,
            contentUriString = contentUri.toString(),
            albumArtUriString = albumArtUri,
            trackNumber = track,
            year = year,
            sizeBytes = size,
            dateAdded = dateAdded,
            folderName = folderName,
            path = filePath
        )
    }

    /**
     * Returns a reactive Flow of domain Song objects loaded directly from the local Room database,
     * combined with the user's favorites state.
     */
    fun getSongsFlow(): Flow<List<Song>> {
        return combine(
            musicDao.getAllCachedSongs(),
            musicDao.getAllFavoriteIds()
        ) { songEntities, favIds ->
            val favSet = favIds.toSet()
            songEntities.map { entity ->
                entity.toDomainSong(isFavorite = favSet.contains(entity.id))
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Extracts distinct Album groupings from a list of domain songs.
     */
    suspend fun getAlbums(songs: List<Song>): List<Album> = withContext(Dispatchers.Default) {
        songs.groupBy { it.album }
            .map { (albumTitle, songGroup) ->
                val firstSong = songGroup.first()
                Album(
                    id = firstSong.id,
                    title = albumTitle,
                    artist = firstSong.artist,
                    artworkUri = firstSong.albumArtUri,
                    songCount = songGroup.size,
                    year = firstSong.year
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    /**
     * Extracts distinct Artist groupings from a list of domain songs.
     */
    suspend fun getArtists(songs: List<Song>): List<Artist> = withContext(Dispatchers.Default) {
        songs.groupBy { it.artist }
            .map { (artistName, songGroup) ->
                val albumCount = songGroup.map { it.album }.distinct().size
                Artist(
                    name = artistName,
                    songCount = songGroup.size,
                    albumCount = albumCount
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Extracts distinct Folder groupings from a list of domain songs.
     */
    suspend fun getFolders(songs: List<Song>): List<Folder> = withContext(Dispatchers.Default) {
        songs.groupBy { it.folderName }
            .map { (folderName, songGroup) ->
                val firstPath = songGroup.firstOrNull()?.path ?: ""
                val parentPath = if (firstPath.isNotBlank()) {
                    File(firstPath).parent ?: ""
                } else ""
                Folder(
                    name = folderName,
                    path = parentPath,
                    songCount = songGroup.size
                )
            }
            .sortedBy { it.name.lowercase() }
    }
}
