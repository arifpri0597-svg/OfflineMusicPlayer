package com.example.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.data.model.Album
import com.example.data.model.Artist
import com.example.data.model.Folder
import com.example.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    private val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

    suspend fun queryLocalSongs(): List<Song> = withContext(Dispatchers.IO) {
        var songsList = ArrayList<Song>(256)
        val contentResolver = context.contentResolver

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

        // Filter music files only and ignore files shorter than 3 seconds (ringtones/clicks)
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
                val count = cursor.count
                if (count > 256) {
                    songsList = ArrayList(count)
                }
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
                        ContentUris.withAppendedId(albumArtBaseUri, albumId)
                    } else null

                    val safeTitle = if (rawTitle.isNullOrBlank() || rawTitle == "<unknown>") {
                        if (filePath.isNotBlank()) {
                            val fileName = filePath.substringAfterLast('/')
                            val nameWithoutExt = fileName.substringBeforeLast('.')
                            if (nameWithoutExt.isNotBlank()) nameWithoutExt else "Unknown Title"
                        } else "Unknown Title"
                    } else rawTitle.trim()

                    val safeArtist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") {
                        "Unknown Artist"
                    } else rawArtist.trim()

                    val safeAlbum = if (rawAlbum.isNullOrBlank() || rawAlbum == "<unknown>") {
                        "Unknown Album"
                    } else rawAlbum.trim()

                    val folderName = if (filePath.isNotBlank()) {
                        val parentPath = filePath.substringBeforeLast('/', "")
                        val parentName = parentPath.substringAfterLast('/')
                        if (parentName.isNotBlank()) parentName else "Music"
                    } else "Music"

                    songsList.add(
                        Song(
                            id = id,
                            title = safeTitle,
                            artist = safeArtist,
                            album = safeAlbum,
                            durationMs = duration,
                            contentUri = contentUri,
                            albumArtUri = albumArtUri,
                            trackNumber = track,
                            year = year,
                            sizeBytes = size,
                            dateAdded = dateAdded,
                            folderName = folderName,
                            path = filePath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        songsList
    }

    suspend fun queryAlbums(songs: List<Song>): List<Album> = withContext(Dispatchers.Default) {
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
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    suspend fun queryArtists(songs: List<Song>): List<Artist> = withContext(Dispatchers.Default) {
        songs.groupBy { it.artist }
            .map { (artistName, songGroup) ->
                val distinctAlbums = HashSet<String>()
                for (song in songGroup) {
                    distinctAlbums.add(song.album)
                }
                Artist(
                    name = artistName,
                    songCount = songGroup.size,
                    albumCount = distinctAlbums.size
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    suspend fun queryFolders(songs: List<Song>): List<Folder> = withContext(Dispatchers.Default) {
        songs.groupBy { it.folderName }
            .map { (folderName, songGroup) ->
                val firstPath = songGroup.firstOrNull()?.path ?: ""
                val parentPath = if (firstPath.isNotBlank()) {
                    firstPath.substringBeforeLast('/', "")
                } else ""
                Folder(
                    name = folderName,
                    path = parentPath,
                    songCount = songGroup.size
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
