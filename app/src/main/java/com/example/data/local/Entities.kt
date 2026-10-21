package com.example.data.local

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.Song

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["folderName"])
    ]
)
data class SongEntity(
    @PrimaryKey
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUriString: String,
    val albumArtUriString: String?,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val sizeBytes: Long = 0L,
    val dateAdded: Long = 0L,
    val folderName: String = "",
    val path: String = ""
) {
    fun toDomainSong(isFavorite: Boolean = false): Song {
        return Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            contentUri = Uri.parse(contentUriString),
            albumArtUri = albumArtUriString?.let { Uri.parse(it) },
            trackNumber = trackNumber,
            year = year,
            sizeBytes = sizeBytes,
            dateAdded = dateAdded,
            folderName = folderName,
            path = path,
            isFavorite = isFavorite
        )
    }

    companion object {
        fun fromDomainSong(song: Song): SongEntity {
            return SongEntity(
                id = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationMs = song.durationMs,
                contentUriString = song.contentUri.toString(),
                albumArtUriString = song.albumArtUri?.toString(),
                trackNumber = song.trackNumber,
                year = song.year,
                sizeBytes = song.sizeBytes,
                dateAdded = song.dateAdded,
                folderName = song.folderName,
                path = song.path
            )
        }
    }
}

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey
    val songId: Long,
    val addedAt: Long = System.currentTimeMillis()
)

@Immutable
@Entity(
    tableName = "history",
    indices = [
        Index(value = ["playedAt"]),
        Index(value = ["songId"])
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val songId: Long,
    val title: String,
    val artist: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [
        Index(value = ["playlistId", "orderIndex"]),
        Index(value = ["songId"])
    ]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val orderIndex: Int = 0
)

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val trackCount: Int
) {
    fun toPlaylist(): com.example.data.model.Playlist = com.example.data.model.Playlist(
        id = id,
        name = name,
        createdAt = createdAt,
        trackCount = trackCount
    )
}
