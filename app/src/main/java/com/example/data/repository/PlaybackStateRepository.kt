package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.MusicDao
import com.example.data.local.PlaybackStateEntity
import com.example.data.model.RepeatMode
import com.example.data.model.Song
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

data class RestoredPlaybackSession(
    val currentSong: Song,
    val queue: List<Song>,
    val currentIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode
)

/**
 * Single reliable source of truth for persisting and restoring music playback state
 * across app restarts, Recent Apps swipe-dismissals, and service recreation.
 *
 * Uses Room (`playback_state` table) as the primary structured database store, paired with a
 * synchronous SharedPreferences mirror so lifecycle termination callbacks (`onTaskRemoved`,
 * `onStop`, `onDestroy`) never lose in-flight state if the OS kills the process immediately.
 */
class PlaybackStateRepository(
    private val context: Context,
    private val musicDao: MusicDao
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val syncPrefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun savePlaybackState(state: PlaybackStateEntity) {
        val sanitized = sanitizeState(state) ?: return
        writeBackupPrefs(sanitized, commitSynchronously = false)
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                musicDao.upsertPlaybackState(sanitized)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun savePlaybackState(
        song: Song,
        positionMs: Long,
        durationMs: Long = song.durationMs,
        queueSongIds: List<Long> = listOf(song.id),
        currentIndex: Int = 0,
        isPlaying: Boolean = false,
        shuffleEnabled: Boolean = false,
        repeatMode: RepeatMode = RepeatMode.OFF,
        lastUpdated: Long = System.currentTimeMillis()
    ) {
        val effectiveDuration = if (durationMs > 0L) durationMs else max(0L, song.durationMs)
        val clampedPosition = clampPosition(positionMs, effectiveDuration)
        val entity = PlaybackStateEntity(
            id = PlaybackStateEntity.SINGLETON_ID,
            songId = song.id,
            contentUriString = song.contentUri.toString(),
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumArtUriString = song.albumArtUri?.toString(),
            positionMs = clampedPosition,
            durationMs = effectiveDuration,
            queueSongIdsCsv = queueSongIds.joinToString(","),
            currentIndex = currentIndex.coerceAtLeast(0),
            isPlaying = isPlaying,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode.name,
            lastUpdated = lastUpdated
        )
        savePlaybackState(entity)
    }

    /**
     * Immediately persists playback state to synchronous storage and dispatches a non-cancellable
     * Room upsert on Dispatchers.IO. Safe to call from `onTaskRemoved`, `onStop`, `onDestroy`,
     * pause, and seek handlers without blocking UI rendering.
     */
    fun savePlaybackStateImmediate(state: PlaybackStateEntity) {
        val sanitized = sanitizeState(state) ?: return
        writeBackupPrefs(sanitized, commitSynchronously = true)
        repositoryScope.launch(NonCancellable) {
            try {
                musicDao.upsertPlaybackState(sanitized)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Retrieves the last saved playback state from Room (reconciling with the synchronous
     * lifecycle backup if the process was terminated before an async transaction completed).
     */
    suspend fun getLastPlaybackState(): PlaybackStateEntity? = withContext(Dispatchers.IO) {
        val roomState = try {
            musicDao.getPlaybackState(PlaybackStateEntity.SINGLETON_ID)
        } catch (_: Exception) {
            null
        }
        val backupState = readBackupPrefs()

        val chosen = when {
            roomState != null && backupState != null -> {
                if (backupState.lastUpdated > roomState.lastUpdated) {
                    try {
                        musicDao.upsertPlaybackState(backupState)
                    } catch (_: Exception) {
                    }
                    backupState
                } else {
                    roomState
                }
            }
            roomState != null -> roomState
            backupState != null -> {
                try {
                    musicDao.upsertPlaybackState(backupState)
                } catch (_: Exception) {
                }
                backupState
            }
            else -> null
        } ?: return@withContext null

        val validated = sanitizeState(chosen)
        if (validated == null) {
            clearPlaybackState()
            return@withContext null
        }
        validated
    }

    fun getLastPlaybackStateFlow(): Flow<PlaybackStateEntity?> {
        return musicDao.getPlaybackStateFlow(PlaybackStateEntity.SINGLETON_ID)
    }

    suspend fun clearPlaybackState() = withContext(NonCancellable + Dispatchers.IO) {
        clearBackupPrefs()
        try {
            musicDao.clearPlaybackState()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resolves the persisted [PlaybackStateEntity] against the current library (or cached Room songs),
     * handling edge cases safely:
     * - Deleted audio file
     * - Moved or renamed audio file (matches by ID, then URI, then Title + Artist)
     * - Invalid media URI
     * - Changed track duration
     * - Saved position > duration or < 0 (clamped to 0..actualDuration)
     */
    suspend fun resolveRestoredSession(
        savedState: PlaybackStateEntity,
        librarySongs: List<Song>
    ): RestoredPlaybackSession? = withContext(Dispatchers.IO) {
        val validState = sanitizeState(savedState)
        if (validState == null) {
            clearPlaybackState()
            return@withContext null
        }

        val favIds = try {
            // Best-effort favorite lookup if needed
            emptySet<Long>()
        } catch (_: Exception) {
            emptySet()
        }

        // 1. Resolve song from librarySongs if available
        var matchedSong: Song? = null
        if (librarySongs.isNotEmpty()) {
            matchedSong = librarySongs.firstOrNull { it.id == validState.songId }
                ?: librarySongs.firstOrNull { it.contentUri.toString() == validState.contentUriString }
                ?: if (validState.title.isNotBlank()) {
                    librarySongs.firstOrNull {
                        it.title.equals(validState.title, ignoreCase = true) &&
                            it.artist.equals(validState.artist, ignoreCase = true)
                    }
                } else null

            // If library is populated and the track wasn't found in the scanned library,
            // check whether it was an external file that still exists; otherwise it was deleted.
            if (matchedSong == null) {
                if (isExternalUriStillAccessible(validState.contentUriString)) {
                    matchedSong = validState.toFallbackSong()
                } else {
                    clearPlaybackState()
                    return@withContext null
                }
            }
        } else {
            // Library list hasn't been loaded into memory yet; check Room cached songs table first
            val cachedEntity = try {
                musicDao.getSongById(validState.songId)
            } catch (_: Exception) {
                null
            }
            if (cachedEntity != null) {
                val domainSong = cachedEntity.toDomainSong(isFavorite = favIds.contains(cachedEntity.id))
                if (isSongFileValid(domainSong)) {
                    matchedSong = domainSong
                } else {
                    clearPlaybackState()
                    return@withContext null
                }
            } else {
                val fallback = validState.toFallbackSong()
                if (fallback != null && isSongFileValid(fallback)) {
                    matchedSong = fallback
                } else {
                    clearPlaybackState()
                    return@withContext null
                }
            }
        }

        val resolvedSong = matchedSong ?: return@withContext null
        if (!isSongFileValid(resolvedSong)) {
            clearPlaybackState()
            return@withContext null
        }

        // Clamp duration and position safely
        val actualDuration = when {
            resolvedSong.durationMs > 0L -> resolvedSong.durationMs
            validState.durationMs > 0L -> validState.durationMs
            else -> 0L
        }
        val clampedPosition = clampPosition(validState.positionMs, actualDuration)

        // Reconstruct queue
        val queueIds = validState.parseQueueSongIds()
        val restoredQueue = if (librarySongs.isNotEmpty() && queueIds.isNotEmpty()) {
            val songById = librarySongs.associateBy { it.id }
            val list = queueIds.mapNotNull { songById[it] }.toMutableList()
            if (list.none { it.id == resolvedSong.id }) {
                val insertIdx = validState.currentIndex.coerceIn(0, list.size)
                list.add(insertIdx, resolvedSong)
            }
            list
        } else {
            listOf(resolvedSong)
        }

        val restoredIndex = restoredQueue.indexOfFirst { it.id == resolvedSong.id }
            .let { if (it != -1) it else 0 }

        val repeatMode = try {
            RepeatMode.valueOf(validState.repeatMode)
        } catch (_: Exception) {
            RepeatMode.OFF
        }

        RestoredPlaybackSession(
            currentSong = resolvedSong.copy(durationMs = actualDuration),
            queue = restoredQueue,
            currentIndex = restoredIndex,
            positionMs = clampedPosition,
            durationMs = actualDuration,
            shuffleEnabled = validState.shuffleEnabled,
            repeatMode = repeatMode
        )
    }

    private fun isSongFileValid(song: Song): Boolean {
        val uriString = song.contentUri.toString()
        if (uriString.isBlank()) return false
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "content" && scheme != "file" && scheme != "android.resource") {
            return false
        }
        if (scheme == "file") {
            val filePath = uri.path ?: return false
            val file = File(filePath)
            if (!file.exists()) return false
        }
        if (song.path.isNotBlank() && song.path.startsWith("/")) {
            val diskFile = File(song.path)
            // Only fail if parent directory exists on real filesystem and the specific file was deleted
            val parent = diskFile.parentFile
            if (parent != null && parent.exists() && !diskFile.exists()) {
                return false
            }
        }
        return true
    }

    private fun isExternalUriStillAccessible(uriString: String): Boolean {
        if (uriString.isBlank()) return false
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "file") {
            val path = uri.path ?: return false
            return File(path).exists()
        }
        if (scheme == "content") {
            // MediaStore audio URIs that disappeared from a non-empty MediaStore scan were deleted
            if (uriString.startsWith("content://media/")) {
                return false
            }
            return try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    private fun sanitizeState(state: PlaybackStateEntity): PlaybackStateEntity? {
        if (state.songId == 0L && state.contentUriString.isBlank()) return null
        val uri = try {
            Uri.parse(state.contentUriString)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme.isNullOrBlank() || (scheme != "content" && scheme != "file" && scheme != "android.resource")) {
            return null
        }
        val safeDuration = max(0L, state.durationMs)
        val safePosition = clampPosition(state.positionMs, safeDuration)
        val safeIndex = max(0, state.currentIndex)
        val safeRepeat = try {
            RepeatMode.valueOf(state.repeatMode).name
        } catch (_: Exception) {
            RepeatMode.OFF.name
        }
        return state.copy(
            id = PlaybackStateEntity.SINGLETON_ID,
            positionMs = safePosition,
            durationMs = safeDuration,
            currentIndex = safeIndex,
            repeatMode = safeRepeat
        )
    }

    private fun writeBackupPrefs(state: PlaybackStateEntity, commitSynchronously: Boolean) {
        try {
            val editor = syncPrefs.edit()
                .putBoolean(KEY_HAS_STATE, true)
                .putLong(KEY_SONG_ID, state.songId)
                .putString(KEY_CONTENT_URI, state.contentUriString)
                .putString(KEY_TITLE, state.title)
                .putString(KEY_ARTIST, state.artist)
                .putString(KEY_ALBUM, state.album)
                .putString(KEY_ALBUM_ART_URI, state.albumArtUriString)
                .putLong(KEY_POSITION_MS, state.positionMs)
                .putLong(KEY_DURATION_MS, state.durationMs)
                .putString(KEY_QUEUE_IDS, state.queueSongIdsCsv)
                .putInt(KEY_CURRENT_INDEX, state.currentIndex)
                .putBoolean(KEY_IS_PLAYING, state.isPlaying)
                .putBoolean(KEY_SHUFFLE, state.shuffleEnabled)
                .putString(KEY_REPEAT_MODE, state.repeatMode)
                .putLong(KEY_LAST_UPDATED, state.lastUpdated)
            if (commitSynchronously) {
                editor.commit()
            } else {
                editor.apply()
            }
        } catch (_: Exception) {
        }
    }

    private fun readBackupPrefs(): PlaybackStateEntity? {
        return try {
            if (!syncPrefs.getBoolean(KEY_HAS_STATE, false)) return null
            val uriStr = syncPrefs.getString(KEY_CONTENT_URI, "") ?: ""
            val songId = syncPrefs.getLong(KEY_SONG_ID, 0L)
            if (songId == 0L && uriStr.isBlank()) return null
            PlaybackStateEntity(
                id = PlaybackStateEntity.SINGLETON_ID,
                songId = songId,
                contentUriString = uriStr,
                title = syncPrefs.getString(KEY_TITLE, "") ?: "",
                artist = syncPrefs.getString(KEY_ARTIST, "") ?: "",
                album = syncPrefs.getString(KEY_ALBUM, "") ?: "",
                albumArtUriString = syncPrefs.getString(KEY_ALBUM_ART_URI, null),
                positionMs = syncPrefs.getLong(KEY_POSITION_MS, 0L),
                durationMs = syncPrefs.getLong(KEY_DURATION_MS, 0L),
                queueSongIdsCsv = syncPrefs.getString(KEY_QUEUE_IDS, "") ?: "",
                currentIndex = syncPrefs.getInt(KEY_CURRENT_INDEX, 0),
                isPlaying = syncPrefs.getBoolean(KEY_IS_PLAYING, false),
                shuffleEnabled = syncPrefs.getBoolean(KEY_SHUFFLE, false),
                repeatMode = syncPrefs.getString(KEY_REPEAT_MODE, RepeatMode.OFF.name) ?: RepeatMode.OFF.name,
                lastUpdated = syncPrefs.getLong(KEY_LAST_UPDATED, 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun clearBackupPrefs() {
        try {
            syncPrefs.edit().clear().commit()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val PREFS_NAME = "pulse_playback_state_backup"
        private const val KEY_HAS_STATE = "has_state"
        private const val KEY_SONG_ID = "song_id"
        private const val KEY_CONTENT_URI = "content_uri"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_ALBUM = "album"
        private const val KEY_ALBUM_ART_URI = "album_art_uri"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_QUEUE_IDS = "queue_ids"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_IS_PLAYING = "is_playing"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_LAST_UPDATED = "last_updated"

        fun clampPosition(positionMs: Long, durationMs: Long): Long {
            val nonNegativePos = max(0L, positionMs)
            return if (durationMs > 0L) {
                nonNegativePos.coerceIn(0L, durationMs)
            } else {
                nonNegativePos
            }
        }
    }
}
