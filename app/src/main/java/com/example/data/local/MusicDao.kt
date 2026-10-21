package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // Cached audio files mapped from MediaStore
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun getAllCachedSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun getAllCachedSongsSnapshot(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: Long): SongEntity?

    @Query("SELECT id FROM songs")
    suspend fun getAllCachedSongIds(): List<Long>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIds(ids: List<Long>)

    @Query("DELETE FROM songs WHERE id NOT IN (:validIds)")
    suspend fun pruneDeletedSongs(validIds: List<Long>)

    @Query("DELETE FROM songs")
    suspend fun clearSongs()

    /**
     * Atomically syncs scanned MediaStore entities with the Room cache in a single transaction,
     * only deleting removed rows and upserting new or modified rows to avoid redundant index rewrites
     * and unnecessary Flow emissions.
     */
    @Transaction
    suspend fun syncScannedSongs(scannedEntities: List<SongEntity>) {
        if (scannedEntities.isEmpty()) {
            clearSongs()
            return
        }
        val existingSongs = getAllCachedSongsSnapshot()
        val existingById = HashMap<Long, SongEntity>(existingSongs.size)
        for (song in existingSongs) {
            existingById[song.id] = song
        }

        val activeIdSet = HashSet<Long>(scannedEntities.size)
        val toUpsert = ArrayList<SongEntity>()

        for (entity in scannedEntities) {
            activeIdSet.add(entity.id)
            val existing = existingById[entity.id]
            if (existing != entity) {
                toUpsert.add(entity)
            }
        }

        if (existingSongs.isNotEmpty()) {
            val staleIds = ArrayList<Long>()
            for (existing in existingSongs) {
                if (!activeIdSet.contains(existing.id)) {
                    staleIds.add(existing.id)
                }
            }
            if (staleIds.isNotEmpty()) {
                staleIds.chunked(500).forEach { chunk ->
                    deleteSongsByIds(chunk)
                    deletePlaylistSongsBySongIds(chunk)
                    deleteFavoritesBySongIds(chunk)
                }
            }
        }

        if (toUpsert.isNotEmpty()) {
            toUpsert.chunked(500).forEach { chunk ->
                insertSongs(chunk)
            }
        }
    }

    // Favorites
    @Query("SELECT songId FROM favorites ORDER BY addedAt DESC")
    fun getAllFavoriteIds(): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    fun isFavorite(songId: Long): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun deleteFavorite(songId: Long)

    @Query("DELETE FROM favorites WHERE songId IN (:songIds)")
    suspend fun deleteFavoritesBySongIds(songIds: List<Long>)

    @Query("DELETE FROM favorites")
    suspend fun clearFavorites()

    // Playback History
    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 100")
    fun getHistory(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    // Playlists
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("""
        SELECT p.id, p.name, p.createdAt, COUNT(ps.songId) as trackCount 
        FROM playlists p 
        LEFT JOIN playlist_songs ps ON p.id = ps.playlistId 
        GROUP BY p.id 
        ORDER BY p.createdAt DESC
    """)
    fun getPlaylistsWithTrackCount(): Flow<List<PlaylistWithCount>>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.orderIndex ASC
    """)
    fun getSongsForPlaylist(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getPlaylistTrackCount(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deletePlaylistSongs(playlistId: Long)

    @Query("DELETE FROM playlist_songs WHERE songId IN (:songIds)")
    suspend fun deletePlaylistSongsBySongIds(songIds: List<Long>)

    @Transaction
    suspend fun deletePlaylistWithSongs(playlistId: Long) {
        deletePlaylistSongs(playlistId)
        deletePlaylist(playlistId)
    }

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
    fun getSongIdsForPlaylist(playlistId: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongsToPlaylist(crossRefs: List<PlaylistSongCrossRef>)

    @Transaction
    suspend fun appendSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        var currentCount = getPlaylistTrackCount(playlistId)
        val crossRefs = songIds.map { id ->
            PlaylistSongCrossRef(playlistId = playlistId, songId = id, orderIndex = currentCount++)
        }
        addSongsToPlaylist(crossRefs)
    }

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)
}
