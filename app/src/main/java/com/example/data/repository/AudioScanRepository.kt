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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository that queries the Android MediaStore API to scan local audio files
 * stored on the device and maps them directly into our local Room database schema (SongEntity).
 */
class AudioScanRepository(
    private val context: Context,
    private val musicDao: MusicDao
) {
    private val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

    private val scanMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Scans the device's external storage for audio files using Android MediaStore API,
     * maps each cursor entry to our local database schema (SongEntity), and persists/syncs
     * the records in Room.
     *
     * @return List of newly mapped SongEntity items stored in the local database.
     */
    suspend fun scanAndSyncDeviceAudio(): List<SongEntity> = withContext(Dispatchers.IO) {
        scanMutex.lock()
        var scannedEntities = ArrayList<SongEntity>(256)
        try {
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
            // Android 10–13+ MediaProvider strict SQLite sortOrder validator only allows column + ASC/DESC
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            val queryCursor: Cursor? = try {
                contentResolver.query(
                    collectionUri,
                    projection,
                    selection,
                    null,
                    sortOrder
                )
            } catch (_: IllegalArgumentException) {
                // Fallback if OEM MediaProvider rejects selection or sortOrder grammar
                contentResolver.query(
                    collectionUri,
                    projection,
                    null,
                    null,
                    null
                )
            }

            queryCursor?.use { cursor ->
                val count = cursor.count.coerceAtLeast(0)
                if (count > 256) {
                    scannedEntities = ArrayList(count)
                }
                val seenIds = HashSet<Long>(count.coerceAtLeast(16))
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                if (idCol == -1) return@use
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    try {
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
                        if (entity != null && seenIds.add(entity.id)) {
                            scannedEntities.add(entity)
                        }
                    } catch (_: Throwable) {
                        // Skip malformed MediaStore row without aborting entire scan
                    }
                }
            }

            // Atomically sync with local Room database in a single transaction on Dispatchers.IO
            musicDao.syncScannedSongs(scannedEntities)
        } catch (_: SecurityException) {
            // Permission revoked or unavailable at OS level; do not wipe existing Room cache
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            scanMutex.unlock()
        }

        scannedEntities
    }

    private fun Cursor.getLongSafe(col: Int, default: Long = 0L): Long {
        if (col == -1) return default
        return try {
            if (isNull(col)) default else getLong(col)
        } catch (_: Throwable) {
            try {
                getString(col)?.trim()?.toLongOrNull() ?: default
            } catch (_: Throwable) {
                default
            }
        }
    }

    private fun Cursor.getIntSafe(col: Int, default: Int = 0): Int {
        if (col == -1) return default
        return try {
            if (isNull(col)) default else getInt(col)
        } catch (_: Throwable) {
            try {
                getString(col)?.trim()?.toIntOrNull() ?: default
            } catch (_: Throwable) {
                default
            }
        }
    }

    private fun Cursor.getStringSafe(col: Int): String? {
        if (col == -1) return null
        return try {
            if (isNull(col)) null else getString(col)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Maps a single MediaStore Cursor row to our Room local database SongEntity schema
     * without allocating File objects in the hot loop.
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
    ): SongEntity? {
        val id = cursor.getLongSafe(idCol, -1L)
        if (id <= 0L) return null
        val rawTitle = cursor.getStringSafe(titleCol)
        val rawArtist = cursor.getStringSafe(artistCol)
        val rawAlbum = cursor.getStringSafe(albumCol)
        val albumId = cursor.getLongSafe(albumIdCol, 0L)
        val duration = cursor.getLongSafe(durationCol, 0L).coerceAtLeast(0L)
        val track = cursor.getIntSafe(trackCol, 0)
        val year = cursor.getIntSafe(yearCol, 0)
        val size = cursor.getLongSafe(sizeCol, 0L).coerceAtLeast(0L)
        val dateAdded = cursor.getLongSafe(dateAddedCol, 0L).coerceAtLeast(0L)
        val filePath = cursor.getStringSafe(dataCol) ?: ""

        val contentUri = ContentUris.withAppendedId(collectionUri, id)
        val albumArtUri = if (albumId > 0L) {
            ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
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

    private val domainSongCache = java.util.concurrent.ConcurrentHashMap<SongEntity, Song>()

    /**
     * Returns a reactive Flow of domain Song objects loaded directly from the local Room database
     * without re-emitting the entire song list when favorites change.
     */
    fun getBaseSongsFlow(): Flow<List<Song>> {
        return musicDao.getAllCachedSongs()
            .distinctUntilChanged()
            .map { songEntities ->
                if (domainSongCache.size > songEntities.size * 2 + 64) {
                    domainSongCache.clear()
                }
                val seenIds = HashSet<Long>(songEntities.size.coerceAtLeast(16))
                val result = ArrayList<Song>(songEntities.size)
                for (entity in songEntities) {
                    if (seenIds.add(entity.id)) {
                        val domainSong = domainSongCache.getOrPut(entity) {
                            entity.toDomainSong(isFavorite = false)
                        }
                        result.add(domainSong)
                    }
                }
                result
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    /**
     * Returns a reactive Flow of domain Song objects loaded directly from the local Room database,
     * combined with the user's favorites state on Dispatchers.Default.
     */
    fun getSongsFlow(): Flow<List<Song>> {
        val baseSongsFlow = getBaseSongsFlow()
        val favIdsFlow = musicDao.getAllFavoriteIds()
            .distinctUntilChanged()
            .map { it.toHashSet() }

        return combine(baseSongsFlow, favIdsFlow) { baseSongs, favSet ->
            if (favSet.isEmpty()) {
                baseSongs
            } else {
                baseSongs.map { song ->
                    val isFav = favSet.contains(song.id)
                    if (song.isFavorite == isFav) song else song.copy(isFavorite = isFav)
                }
            }
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    /**
     * Extracts distinct Album groupings from a list of domain songs on a background thread.
     */
    suspend fun getAlbums(songs: List<Song>): List<Album> = withContext(Dispatchers.Default) {
        if (songs.isEmpty()) return@withContext emptyList()
        songs.groupBy { it.album.lowercase(java.util.Locale.ROOT) }
            .mapNotNull { (_, songGroup) ->
                val firstSong = songGroup.firstOrNull() ?: return@mapNotNull null
                val artworkSong = songGroup.firstOrNull { it.albumArtUri != null } ?: firstSong
                Album(
                    id = firstSong.id,
                    title = firstSong.album.ifBlank { "Unknown Album" },
                    artist = firstSong.artist.ifBlank { "Unknown Artist" },
                    artworkUri = artworkSong.albumArtUri,
                    songCount = songGroup.size,
                    year = firstSong.year
                )
            }
            .distinctBy { "${it.id}_${it.title}" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /**
     * Extracts distinct Artist groupings from a list of domain songs on a background thread.
     */
    suspend fun getArtists(songs: List<Song>): List<Artist> = withContext(Dispatchers.Default) {
        if (songs.isEmpty()) return@withContext emptyList()
        songs.groupBy { it.artist.lowercase(java.util.Locale.ROOT) }
            .mapNotNull { (_, songGroup) ->
                val firstSong = songGroup.firstOrNull() ?: return@mapNotNull null
                val distinctAlbums = HashSet<String>()
                for (song in songGroup) {
                    distinctAlbums.add(song.album.lowercase(java.util.Locale.ROOT))
                }
                Artist(
                    name = firstSong.artist.ifBlank { "Unknown Artist" },
                    songCount = songGroup.size,
                    albumCount = distinctAlbums.size
                )
            }
            .distinctBy { it.name }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * Extracts distinct Folder groupings from a list of domain songs on a background thread.
     */
    suspend fun getFolders(songs: List<Song>): List<Folder> = withContext(Dispatchers.Default) {
        if (songs.isEmpty()) return@withContext emptyList()
        songs.groupBy { it.folderName.lowercase(java.util.Locale.ROOT) }
            .mapNotNull { (_, songGroup) ->
                val firstSong = songGroup.firstOrNull() ?: return@mapNotNull null
                val firstPath = firstSong.path
                val parentPath = if (firstPath.isNotBlank()) {
                    firstPath.substringBeforeLast('/', "")
                } else ""
                Folder(
                    name = firstSong.folderName.ifBlank { "Music" },
                    path = parentPath,
                    songCount = songGroup.size
                )
            }
            .distinctBy { "${it.name}_${it.path}" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private val playbackStateRepository by lazy {
        PlaybackStateRepository(context, musicDao)
    }

    suspend fun savePlaybackState(state: com.example.data.local.PlaybackStateEntity) {
        playbackStateRepository.savePlaybackState(state)
    }

    suspend fun getLastPlaybackState(): com.example.data.local.PlaybackStateEntity? {
        return playbackStateRepository.getLastPlaybackState()
    }

    suspend fun clearPlaybackState() {
        playbackStateRepository.clearPlaybackState()
    }
}

