package com.example.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
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
    val repeatMode: RepeatMode,
    val playbackSpeed: Float = 1.0f,
    val isExternal: Boolean = false
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
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serialIoDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val repositoryScope = CoroutineScope(SupervisorJob() + serialIoDispatcher)
    private val monotonicTimestamp = java.util.concurrent.atomic.AtomicLong(0L)

    private fun nextTimestamp(candidate: Long): Long {
        val base = if (candidate > 0L) candidate else System.currentTimeMillis()
        while (true) {
            val prev = monotonicTimestamp.get()
            val next = if (base > prev) base else prev + 1L
            if (monotonicTimestamp.compareAndSet(prev, next)) {
                return next
            }
        }
    }

    private val syncPrefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun savePlaybackState(state: PlaybackStateEntity) {
        val sanitized = sanitizeState(state)?.copy(lastUpdated = nextTimestamp(state.lastUpdated)) ?: return
        writeBackupPrefs(sanitized, commitSynchronously = false)
        withContext(NonCancellable + serialIoDispatcher) {
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
        playbackSpeed: Float = 1.0f,
        isExternal: Boolean = isExternalUriString(song.contentUri.toString()) || song.folderName == "External",
        lastUpdated: Long = System.currentTimeMillis()
    ) {
        val effectiveDuration = if (durationMs > 0L) durationMs else max(0L, song.durationMs)
        val clampedPosition = clampPosition(positionMs, effectiveDuration)
        val clampedSpeed = clampSpeed(playbackSpeed)
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
            playbackSpeed = clampedSpeed,
            isExternal = isExternal,
            lastUpdated = lastUpdated
        )
        savePlaybackState(entity)
    }

    /**
     * Immediately persists playback state to synchronous storage and dispatches a non-cancellable
     * Room upsert on a serialized IO dispatcher. Safe to call from `onTaskRemoved`, `onStop`, `onDestroy`,
     * pause, and seek handlers without blocking UI rendering.
     */
    fun savePlaybackStateImmediate(state: PlaybackStateEntity) {
        val sanitized = sanitizeState(state)?.copy(lastUpdated = nextTimestamp(state.lastUpdated)) ?: return
        writeBackupPrefs(sanitized, commitSynchronously = false)
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
    suspend fun getLastPlaybackState(): PlaybackStateEntity? = withContext(serialIoDispatcher) {
        val roomState = try {
            musicDao.getPlaybackState(PlaybackStateEntity.SINGLETON_ID)
        } catch (_: Exception) {
            null
        }
        val backupState = readBackupPrefs()

        val chosen = when {
            roomState != null && backupState != null -> {
                if (backupState.lastUpdated >= roomState.lastUpdated) {
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

    suspend fun clearPlaybackState() = withContext(NonCancellable + serialIoDispatcher) {
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
     * - External File Manager URIs: strictly verified for accessibility and audio validity BEFORE
     *   restoration (both when librarySongs is empty and non-empty). If invalid, deleted, moved,
     *   or inaccessible, silently ignored and cleared without affecting library/history data.
     * - Deleted MediaStore audio file
     * - Moved or renamed MediaStore audio file (matches by ID, then URI, then Title + Artist)
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

        val savedUri = try {
            Uri.parse(validState.contentUriString)
        } catch (_: Exception) {
            clearPlaybackState()
            return@withContext null
        }

        val isExternalState = validState.isExternal || isExternalUriString(validState.contentUriString)

        // Strict verification for external File Manager URIs BEFORE attempting startup restoration
        if (isExternalState) {
            if (!isExternalAudioUriValidAndAccessible(
                    uri = savedUri,
                    explicitMimeType = null,
                    fallbackTitle = validState.title
                )
            ) {
                // Silently ignore and clear only the invalid/inaccessible external playback state
                clearPlaybackState()
                return@withContext null
            }

            val externalSong = validState.toFallbackSong()
            if (externalSong == null) {
                clearPlaybackState()
                return@withContext null
            }

            val actualDuration = max(0L, validState.durationMs.coerceAtLeast(externalSong.durationMs))
            val clampedPosition = clampPosition(validState.positionMs, actualDuration)
            val repeatMode = try {
                RepeatMode.valueOf(validState.repeatMode)
            } catch (_: Exception) {
                RepeatMode.OFF
            }

            return@withContext RestoredPlaybackSession(
                currentSong = externalSong.copy(durationMs = actualDuration),
                queue = listOf(externalSong.copy(durationMs = actualDuration)),
                currentIndex = 0,
                positionMs = clampedPosition,
                durationMs = actualDuration,
                shuffleEnabled = validState.shuffleEnabled,
                repeatMode = repeatMode,
                playbackSpeed = clampSpeed(validState.playbackSpeed),
                isExternal = true
            )
        }

        // Standard MediaStore song resolution
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

            if (matchedSong == null) {
                // Song was deleted from MediaStore
                clearPlaybackState()
                return@withContext null
            }
        } else {
            // Library list hasn't been loaded into memory yet; check Room cached songs table first
            val cachedEntity = try {
                musicDao.getSongById(validState.songId)
            } catch (_: Exception) {
                null
            }
            if (cachedEntity != null) {
                val domainSong = cachedEntity.toDomainSong(isFavorite = false)
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

        // Reconstruct queue without duplicates or invalid items
        val queueIds = validState.parseQueueSongIds()
        val restoredQueue = if (librarySongs.isNotEmpty() && queueIds.isNotEmpty()) {
            val songById = librarySongs.associateBy { it.id }
            val list = queueIds
                .distinct()
                .mapNotNull { songById[it] }
                .filter { isSongFileValid(it) }
                .toMutableList()
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
            repeatMode = repeatMode,
            playbackSpeed = clampSpeed(validState.playbackSpeed),
            isExternal = false
        )
    }

    /**
     * Verifies that an external File Manager URI:
     * 1. Has a valid `content` or `file` scheme (and is not inside private app directories).
     * 2. Is currently accessible (can be opened for reading without SecurityException/FileNotFoundException).
     * 3. Is a valid audio file (by MIME type, audio extension, or audio metadata).
     */
    fun isExternalAudioUriValidAndAccessible(
        uri: Uri,
        explicitMimeType: String? = null,
        fallbackTitle: String? = null
    ): Boolean {
        val uriString = uri.toString()
        if (uriString.isBlank()) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "content" && scheme != "file") return false

        if (scheme == "file") {
            val rawPath = uri.path ?: return false
            val file = File(rawPath)
            if (!file.exists() || !file.isFile || !file.canRead()) {
                return false
            }
            val canonicalPath = try {
                file.canonicalPath
            } catch (_: Exception) {
                return false
            }
            val privateDataDir = context.applicationInfo?.dataDir?.let {
                try { File(it).canonicalPath } catch (_: Exception) { null }
            }
            if (privateDataDir != null && canonicalPath.startsWith(privateDataDir)) {
                val cacheDirPath = try { context.cacheDir?.canonicalPath } catch (_: Exception) { null }
                val isInsideCache = cacheDirPath != null && canonicalPath.startsWith(cacheDirPath)
                if (!isInsideCache) {
                    return false
                }
            }
            if (explicitMimeType != null && !isSupportedAudioMimeType(explicitMimeType)) {
                return false
            }
            return hasSupportedAudioExtension(file.name) ||
                hasSupportedAudioExtension(fallbackTitle) ||
                isSupportedAudioMimeType(explicitMimeType) ||
                hasAudioTrackMetadata(uri)
        }

        // scheme == "content"
        val isReadable = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length != 0L
            } ?: false
        } catch (_: Exception) {
            false
        }
        if (!isReadable) return false

        val resolvedMimeType = explicitMimeType?.takeIf { it.isNotBlank() }
            ?: try {
                context.contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }

        if (resolvedMimeType != null) {
            if (isSupportedAudioMimeType(resolvedMimeType)) {
                return true
            }
            // If the provider returned a generic binary MIME type, verify via extension or metadata
            if (!resolvedMimeType.equals("application/octet-stream", ignoreCase = true)) {
                return false
            }
        }

        return hasSupportedAudioExtension(uri.lastPathSegment) ||
            hasSupportedAudioExtension(fallbackTitle) ||
            hasSupportedAudioExtension(uriString) ||
            hasAudioTrackMetadata(uri)
    }

    private fun hasAudioTrackMetadata(uri: Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme?.lowercase() == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            hasAudio != null || duration > 0L || isSupportedAudioMimeType(mime)
        } catch (_: Exception) {
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
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
            if (!file.exists() || !file.canRead()) return false
        }
        if (scheme == "file" && song.path.isNotBlank() && song.path.startsWith("/")) {
            val diskFile = File(song.path)
            val parent = diskFile.parentFile
            if (parent != null && parent.exists() && !diskFile.exists()) {
                return false
            }
        }
        return true
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
        val safeSpeed = clampSpeed(state.playbackSpeed)
        val isExternal = state.isExternal || isExternalUriString(state.contentUriString)
        return state.copy(
            id = PlaybackStateEntity.SINGLETON_ID,
            positionMs = safePosition,
            durationMs = safeDuration,
            currentIndex = safeIndex,
            repeatMode = safeRepeat,
            playbackSpeed = safeSpeed,
            isExternal = isExternal
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
                .putFloat(KEY_PLAYBACK_SPEED, state.playbackSpeed)
                .putBoolean(KEY_IS_EXTERNAL, state.isExternal)
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
                playbackSpeed = clampSpeed(syncPrefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)),
                isExternal = syncPrefs.getBoolean(KEY_IS_EXTERNAL, isExternalUriString(uriStr)),
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
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_IS_EXTERNAL = "is_external"
        private const val KEY_LAST_UPDATED = "last_updated"

        private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "webm", "3gp", "amr", "mid", "xmf", "mxmf", "ota"
        )

        fun isExternalUriString(uriString: String): Boolean {
            if (uriString.isBlank()) return false
            val lower = uriString.lowercase()
            if (lower.startsWith("content://media/") || lower.startsWith("android.resource://")) {
                return false
            }
            return lower.startsWith("content://") || lower.startsWith("file://")
        }

        fun isSupportedAudioMimeType(mimeType: String?): Boolean {
            if (mimeType.isNullOrBlank()) return false
            val normalized = mimeType.lowercase().trim()
            return normalized.startsWith("audio/") ||
                normalized == "application/ogg" ||
                normalized == "application/x-ogg" ||
                normalized == "application/flac" ||
                normalized == "application/x-flac" ||
                normalized == "application/itunes"
        }

        fun hasSupportedAudioExtension(nameOrPath: String?): Boolean {
            if (nameOrPath.isNullOrBlank()) return false
            val decoded = try {
                Uri.decode(nameOrPath)
            } catch (_: Exception) {
                nameOrPath
            }
            val clean = decoded.substringBefore('?').substringBefore('#')
            val ext = clean.substringAfterLast('.', "").lowercase().trim()
            return ext in SUPPORTED_AUDIO_EXTENSIONS
        }

        fun clampPosition(positionMs: Long, durationMs: Long): Long {
            val nonNegativePos = max(0L, positionMs)
            return if (durationMs > 0L) {
                nonNegativePos.coerceIn(0L, durationMs)
            } else {
                nonNegativePos
            }
        }

        fun clampSpeed(speed: Float): Float {
            if (speed.isNaN() || speed.isInfinite() || speed <= 0f) return 1.0f
            return speed.coerceIn(0.25f, 3.0f)
        }
    }
}
