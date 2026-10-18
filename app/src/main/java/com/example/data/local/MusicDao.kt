package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // Cached audio files mapped from MediaStore
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun getAllCachedSongs(): Flow<List<SongEntity>>

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

    // Favorites
    @Query("SELECT songId FROM favorites ORDER BY addedAt DESC")
    fun getAllFavoriteIds(): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    fun isFavorite(songId: Long): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun deleteFavorite(songId: Long)

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

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
    fun getSongIdsForPlaylist(playlistId: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)
}
