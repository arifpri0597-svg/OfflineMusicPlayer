package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * High-performance in-memory cache of decoded [ImageBitmap] thumbnails and negative (missing) URIs.
 *
 * Resolves album artwork using the exact same sources that Android Notification / MediaSession /
 * Media3 ExoPlayer use:
 * 1. Embedded artwork bytes (`player.mediaMetadata.artworkData`) extracted by Media3 when available.
 * 2. Android 10+ (`API 29+`) `ContentResolver.loadThumbnail(uri, Size(w, h), null)` on the album art URI.
 * 3. `ContentResolver.openFileDescriptor` / `openInputStream` sampled decode on the album art URI.
 * 4. Android 10+ `ContentResolver.loadThumbnail` on the song's audio `contentUri` (`content://media/external/audio/media/{id}`).
 * 5. Embedded ID3/MP4/FLAC picture (`MediaMetadataRetriever.getEmbeddedPicture()`) from the audio
 *    `contentUri` or file `path`.
 */
object ArtworkCacheManager {
    private val missingArtworkUris: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val missingPrimaryOnlyUris: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val verifiedArtworkUris: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Lock-free read map mirroring LruCache so UI thread lookups during 120Hz scrolling never block on locks
    private val fastReadCache = ConcurrentHashMap<String, ImageBitmap>(256)

    private val maxCacheKb: Int = ((Runtime.getRuntime().maxMemory() / 1024L) / 8L)
        .toInt()
        .coerceIn(4096, 32768)

    private val bitmapCache = object : LruCache<String, ImageBitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return ((value.width * value.height * 4) / 1024).coerceAtLeast(1)
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: String?,
            oldValue: ImageBitmap?,
            newValue: ImageBitmap?
        ) {
            if (key != null && newValue == null) {
                fastReadCache.remove(key, oldValue)
            }
        }
    }

    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val decodeScope = CoroutineScope(SupervisorJob() + decodeDispatcher)
    private val inFlightDecodes = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    private val missingArtworkException = NoSuchElementException("Cached missing album artwork")

    fun bucketSizeFor(sizePx: Int): Int = when {
        sizePx <= 160 -> 144
        sizePx <= 320 -> 256
        else -> 512
    }

    fun buildSourceIdentity(
        primaryUri: String?,
        fallbackUri: String? = null,
        fallbackPath: String? = null
    ): String {
        val p = primaryUri?.trim().orEmpty()
        val f = fallbackUri?.trim().orEmpty()
        val path = fallbackPath?.trim().orEmpty()
        return when {
            p.isNotEmpty() && f.isNotEmpty() && p != f -> "$p|$f"
            p.isNotEmpty() -> p
            f.isNotEmpty() -> f
            path.isNotEmpty() -> "file:$path"
            else -> ""
        }
    }

    fun isKnownMissing(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return true
        return missingArtworkUris.contains(uriString)
    }

    fun isSourceKnownMissing(
        primaryUri: String?,
        fallbackUri: String? = null,
        fallbackPath: String? = null,
        hasEmbeddedBytes: Boolean = false
    ): Boolean {
        if (hasEmbeddedBytes) return false
        val identity = buildSourceIdentity(primaryUri, fallbackUri, fallbackPath)
        if (identity.isEmpty()) return true
        if (missingArtworkUris.contains(identity)) return true
        // If no fallback is provided, check whether primaryUri itself was marked missing
        if (fallbackUri.isNullOrBlank() && fallbackPath.isNullOrBlank() && !primaryUri.isNullOrBlank()) {
            return missingArtworkUris.contains(primaryUri)
        }
        return false
    }

    fun isVerified(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return verifiedArtworkUris.contains(uriString)
    }

    fun markMissing(uriString: String?) {
        if (!uriString.isNullOrBlank()) {
            missingArtworkUris.add(uriString)
        }
    }

    fun markVerified(uriString: String?) {
        if (!uriString.isNullOrBlank()) {
            verifiedArtworkUris.add(uriString)
            missingArtworkUris.remove(uriString)
            missingPrimaryOnlyUris.remove(uriString)
        }
    }

    fun getCachedBitmap(cacheKey: String): ImageBitmap? {
        if (cacheKey.isEmpty()) return null
        return fastReadCache[cacheKey]
    }

    internal fun putCachedBitmap(cacheKey: String, bitmap: ImageBitmap) {
        if (cacheKey.isEmpty()) return
        fastReadCache[cacheKey] = bitmap
        synchronized(bitmapCache) {
            bitmapCache.put(cacheKey, bitmap)
        }
    }

    fun cacheKeyFor(uriString: String, sizePx: Int): String {
        val bucketSize = bucketSizeFor(sizePx)
        return "${bucketSize}_$uriString"
    }

    fun cacheKeyFor(
        primaryUri: String?,
        fallbackUri: String?,
        fallbackPath: String?,
        sizePx: Int
    ): String {
        val identity = buildSourceIdentity(primaryUri, fallbackUri, fallbackPath)
        if (identity.isEmpty()) return ""
        val bucketSize = bucketSizeFor(sizePx)
        return "${bucketSize}_$identity"
    }

    /**
     * Looks up the exact bucket size in the lock-free cache first, or falls back to any already-cached
     * bucket size (e.g. 256px or 144px thumbnail) so Now Playing (`512px`) can display an
     * instant preview on frame 1 while the 512px decode finishes in the background.
     */
    fun getBestAvailableBitmap(
        primaryUri: String?,
        fallbackUri: String? = null,
        fallbackPath: String? = null,
        sizePx: Int
    ): Pair<ImageBitmap?, Boolean> {
        val identity = buildSourceIdentity(primaryUri, fallbackUri, fallbackPath)
        if (identity.isNotEmpty() && missingArtworkUris.contains(identity)) {
            return null to false
        }

        val targetBucket = bucketSizeFor(sizePx)

        // 1. Check exact bucket size first (lock-free ConcurrentHashMap reads)
        if (identity.isNotEmpty()) {
            fastReadCache["${targetBucket}_$identity"]?.let { return it to true }
        }
        if (!primaryUri.isNullOrBlank() && primaryUri != identity) {
            fastReadCache["${targetBucket}_$primaryUri"]?.let { return it to true }
        }
        if (!fallbackUri.isNullOrBlank() && fallbackUri != primaryUri && fallbackUri != identity) {
            fastReadCache["${targetBucket}_$fallbackUri"]?.let { return it to true }
        }

        if (fastReadCache.isEmpty()) {
            return null to false
        }

        // 2. If requesting a larger size (e.g. 512 hero or 256 album), check if a smaller/larger bucket is already cached
        val fallbackBuckets = when (targetBucket) {
            512 -> intArrayOf(256, 144)
            256 -> intArrayOf(512, 144)
            else -> intArrayOf(256)
        }
        for (bucket in fallbackBuckets) {
            if (identity.isNotEmpty()) {
                fastReadCache["${bucket}_$identity"]?.let { return it to false }
            }
            if (!primaryUri.isNullOrBlank() && primaryUri != identity) {
                fastReadCache["${bucket}_$primaryUri"]?.let { return it to false }
            }
            if (!fallbackUri.isNullOrBlank() && fallbackUri != primaryUri && fallbackUri != identity) {
                fastReadCache["${bucket}_$fallbackUri"]?.let { return it to false }
            }
        }
        return null to false
    }

    suspend fun loadOrDecodeArtwork(
        context: Context,
        uriString: String,
        sizePx: Int
    ): ImageBitmap? {
        return loadOrDecodeArtwork(
            context = context,
            primaryUri = uriString,
            fallbackUri = null,
            fallbackPath = null,
            artworkBytes = null,
            sizePx = sizePx
        )
    }

    suspend fun loadOrDecodeArtwork(
        context: Context,
        primaryUri: String?,
        fallbackUri: String? = null,
        fallbackPath: String? = null,
        artworkBytes: ByteArray? = null,
        sizePx: Int
    ): ImageBitmap? {
        val hasBytes = artworkBytes != null && artworkBytes.isNotEmpty()
        val identity = buildSourceIdentity(primaryUri, fallbackUri, fallbackPath)
        if (identity.isEmpty() && !hasBytes) return null
        if (isSourceKnownMissing(primaryUri, fallbackUri, fallbackPath, hasBytes)) return null

        val bucketSize = bucketSizeFor(sizePx)
        val effectiveIdentity = if (identity.isNotEmpty()) {
            identity
        } else {
            "bytes_${artworkBytes.contentHashCode()}"
        }
        val cacheKey = "${bucketSize}_$effectiveIdentity"

        getCachedBitmap(cacheKey)?.let { return it }
        if (!primaryUri.isNullOrBlank()) {
            getCachedBitmap("${bucketSize}_$primaryUri")?.let { cached ->
                putCachedBitmap(cacheKey, cached)
                return cached
            }
        }
        if (!fallbackUri.isNullOrBlank()) {
            getCachedBitmap("${bucketSize}_$fallbackUri")?.let { cached ->
                putCachedBitmap(cacheKey, cached)
                return cached
            }
        }

        // Reuse an already-running decode for the same key if one is active
        inFlightDecodes[cacheKey]?.let { existing ->
            if (existing.isActive) {
                return try {
                    existing.await()
                } catch (_: Throwable) {
                    null
                }
            }
        }

        // Execute within caller's structured concurrency scope on decodeDispatcher so that
        // if a row scrolls off-screen during a 120Hz fling while queued for a permit, it cancels
        // immediately before touching ContentResolver or MediaMetadataRetriever.
        return withContext(decodeDispatcher) {
            getCachedBitmap(cacheKey)?.let { return@withContext it }
            val deferred = inFlightDecodes.computeIfAbsent(cacheKey) {
                decodeScope.async {
                    try {
                        decodeMultiSourceArtwork(
                            appContext = context.applicationContext,
                            primaryUri = primaryUri,
                            fallbackUri = fallbackUri,
                            fallbackPath = fallbackPath,
                            artworkBytes = artworkBytes,
                            targetSizePx = bucketSize,
                            effectiveIdentity = effectiveIdentity
                        )
                    } finally {
                        inFlightDecodes.remove(cacheKey)
                    }
                }
            }
            try {
                deferred.await()
            } catch (_: Throwable) {
                null
            }
        }
    }

    suspend fun prewarmArtwork(
        context: Context,
        uriStrings: Collection<String>,
        sizePx: Int = 144
    ) = withContext(decodeDispatcher) {
        for (uriString in uriStrings) {
            if (uriString.isBlank() || isKnownMissing(uriString)) continue
            val cacheKey = cacheKeyFor(uriString, sizePx)
            if (getCachedBitmap(cacheKey) != null) continue
            loadOrDecodeArtwork(context, uriString, sizePx)
            kotlinx.coroutines.yield()
        }
    }

    suspend fun prewarmArtworkSources(
        context: Context,
        sources: Collection<Triple<String?, String?, String?>>,
        sizePx: Int = 144
    ) = withContext(decodeDispatcher) {
        val bucketSize = bucketSizeFor(sizePx)
        for ((primaryUri, fallbackUri, fallbackPath) in sources) {
            val identity = buildSourceIdentity(primaryUri, fallbackUri, fallbackPath)
            if (identity.isEmpty() || isSourceKnownMissing(primaryUri, fallbackUri, fallbackPath)) continue
            if (getCachedBitmap("${bucketSize}_$identity") != null) continue
            if (!primaryUri.isNullOrBlank() && getCachedBitmap("${bucketSize}_$primaryUri") != null) continue
            loadOrDecodeArtwork(
                context = context,
                primaryUri = primaryUri,
                fallbackUri = fallbackUri,
                fallbackPath = fallbackPath,
                artworkBytes = null,
                sizePx = bucketSize
            )
            kotlinx.coroutines.yield()
        }
    }

    private fun decodeMultiSourceArtwork(
        appContext: Context,
        primaryUri: String?,
        fallbackUri: String?,
        fallbackPath: String?,
        artworkBytes: ByteArray?,
        targetSizePx: Int,
        effectiveIdentity: String
    ): ImageBitmap? {
        val cacheKey = "${targetSizePx}_$effectiveIdentity"
        getCachedBitmap(cacheKey)?.let { return it }

        val cleanPrimary = primaryUri?.trim()?.takeIf { it.isNotEmpty() }
        val cleanFallback = fallbackUri?.trim()?.takeIf { it.isNotEmpty() }
        val cleanPath = fallbackPath?.trim()?.takeIf { it.isNotEmpty() }

        var decodedBitmap: Bitmap? = null

        // 1. If Media3 / ExoPlayer already extracted embedded artworkData bytes for this track, decode them first
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            decodedBitmap = decodeSampledByteArray(artworkBytes, targetSizePx)
        }

        // 2. Try primary artwork URI (e.g. content://media/external/audio/albumart/{albumId})
        //    Skip if this exact primary URI was already proven missing on an earlier track.
        if (decodedBitmap == null && cleanPrimary != null && !missingPrimaryOnlyUris.contains(cleanPrimary)) {
            decodedBitmap = decodeFromUriString(appContext, cleanPrimary, targetSizePx)
            if (decodedBitmap == null && cleanPrimary != cleanFallback) {
                missingPrimaryOnlyUris.add(cleanPrimary)
            }
        }

        // 3. Try fallback audio contentUri (e.g. content://media/external/audio/media/{songId}) or file path
        //    This is the exact source Media3 / Android Notification uses when albumart/{albumId} is absent!
        if (decodedBitmap == null && cleanFallback != null && cleanFallback != cleanPrimary) {
            decodedBitmap = decodeFromAudioUriOrEmbedded(appContext, cleanFallback, cleanPath, targetSizePx)
        }

        // 4. If primaryUri itself was an audio URI or file path, also try embedded picture extraction
        if (decodedBitmap == null) {
            val candidateUri = cleanFallback ?: cleanPrimary
            if (candidateUri != null || cleanPath != null) {
                decodedBitmap = extractEmbeddedPictureBitmap(appContext, candidateUri, cleanPath, targetSizePx)
            }
        }

        return if (decodedBitmap != null && decodedBitmap.width > 0 && decodedBitmap.height > 0) {
            val imageBitmap = decodedBitmap.asImageBitmap()
            putCachedBitmap(cacheKey, imageBitmap)
            markVerified(effectiveIdentity)
            if (cleanPrimary != null) {
                putCachedBitmap("${targetSizePx}_$cleanPrimary", imageBitmap)
                markVerified(cleanPrimary)
            }
            if (cleanFallback != null) {
                putCachedBitmap("${targetSizePx}_$cleanFallback", imageBitmap)
                markVerified(cleanFallback)
            }
            imageBitmap
        } else {
            markMissing(effectiveIdentity)
            if (cleanFallback == null && cleanPath == null && cleanPrimary != null) {
                markMissing(cleanPrimary)
            }
            null
        }
    }

    private fun decodeFromUriString(
        appContext: Context,
        uriString: String,
        targetSizePx: Int
    ): Bitmap? {
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Throwable) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        val resolver = appContext.contentResolver

        // A. On Android 10+ (API 29+), try ContentResolver.loadThumbnail first for content://media URIs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && scheme == "content") {
            try {
                val thumb = resolver.loadThumbnail(uri, Size(targetSizePx, targetSizePx), null)
                if (thumb.width > 0 && thumb.height > 0) {
                    return scaleDownIfOversized(thumb, targetSizePx)
                }
            } catch (_: Throwable) {
                // Fall through to FileDescriptor / InputStream decode
            }
        }

        // B. If file:// URI or raw file path, decode from File directly
        if (scheme == "file" || scheme == null) {
            val path = uri.path ?: uriString
            val file = File(path)
            if (file.exists() && file.canRead()) {
                decodeSampledFile(file.absolutePath, targetSizePx)?.let { return it }
            }
        }

        // C. Try ContentResolver.openFileDescriptor (single FD open, supports bounds + decode without reopening stream)
        var fileNotFound = false
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                decodeSampledFileDescriptor(pfd.fileDescriptor, targetSizePx)?.let { return it }
            }
        } catch (_: java.io.FileNotFoundException) {
            fileNotFound = true
        } catch (_: Throwable) {
        }

        // D. Try ContentResolver.openInputStream (skip if openFileDescriptor already reported FileNotFoundException)
        if (!fileNotFound) {
            try {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    decodeSampledByteArray(bytes, targetSizePx)?.let { return it }
                }
            } catch (_: Throwable) {
            }
        }

        return null
    }

    private fun decodeFromAudioUriOrEmbedded(
        appContext: Context,
        audioUriString: String,
        filePath: String?,
        targetSizePx: Int
    ): Bitmap? {
        val uri = try {
            Uri.parse(audioUriString)
        } catch (_: Throwable) {
            null
        }

        // A. On Android 10+ (API 29+), MediaProvider.loadThumbnail on content://media/external/audio/media/{id}
        //    natively extracts embedded cover art or folder album art via the system media service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null && uri.scheme?.lowercase() == "content") {
            try {
                val thumb = appContext.contentResolver.loadThumbnail(uri, Size(targetSizePx, targetSizePx), null)
                if (thumb.width > 0 && thumb.height > 0) {
                    return scaleDownIfOversized(thumb, targetSizePx)
                }
            } catch (_: Throwable) {
            }
        }

        // B. Extract embedded ID3 / FLAC / MP4 cover art using MediaMetadataRetriever
        return extractEmbeddedPictureBitmap(appContext, audioUriString, filePath, targetSizePx)
    }

    private fun extractEmbeddedPictureBitmap(
        appContext: Context,
        audioUriString: String?,
        filePath: String?,
        targetSizePx: Int
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            var sourceSet = false

            if (!filePath.isNullOrBlank()) {
                val f = File(filePath)
                if (f.exists() && f.canRead()) {
                    try {
                        retriever.setDataSource(f.absolutePath)
                        sourceSet = true
                    } catch (_: Throwable) {
                    }
                }
            }

            if (!sourceSet && !audioUriString.isNullOrBlank()) {
                val uri = try {
                    Uri.parse(audioUriString)
                } catch (_: Throwable) {
                    null
                }
                if (uri != null) {
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "file" && !uri.path.isNullOrBlank()) {
                        try {
                            retriever.setDataSource(uri.path)
                            sourceSet = true
                        } catch (_: Throwable) {
                        }
                    }
                    if (!sourceSet && scheme == "content") {
                        try {
                            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                retriever.setDataSource(pfd.fileDescriptor)
                                sourceSet = true
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    if (!sourceSet) {
                        try {
                            retriever.setDataSource(appContext, uri)
                            sourceSet = true
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            if (!sourceSet) return null
            val embeddedBytes = retriever.embeddedPicture
            if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                decodeSampledByteArray(embeddedBytes, targetSizePx)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun decodeSampledByteArray(bytes: ByteArray, targetSizePx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(
                width = boundsOptions.outWidth,
                height = boundsOptions.outHeight,
                targetSizePx = targetSizePx
            )
            val preferredConfig = if (targetSizePx >= 512) {
                Bitmap.Config.ARGB_8888
            } else {
                Bitmap.Config.RGB_565
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = preferredConfig
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: if (preferredConfig != Bitmap.Config.ARGB_8888) {
                    val fallbackOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, fallbackOptions)
                } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSampledFileDescriptor(fd: FileDescriptor, targetSizePx: Int): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFileDescriptor(fd, null, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(
                width = boundsOptions.outWidth,
                height = boundsOptions.outHeight,
                targetSizePx = targetSizePx
            )
            val preferredConfig = if (targetSizePx >= 512) {
                Bitmap.Config.ARGB_8888
            } else {
                Bitmap.Config.RGB_565
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = preferredConfig
            }
            BitmapFactory.decodeFileDescriptor(fd, null, decodeOptions)
                ?: if (preferredConfig != Bitmap.Config.ARGB_8888) {
                    val fallbackOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeFileDescriptor(fd, null, fallbackOptions)
                } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSampledFile(filePath: String, targetSizePx: Int): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(
                width = boundsOptions.outWidth,
                height = boundsOptions.outHeight,
                targetSizePx = targetSizePx
            )
            val preferredConfig = if (targetSizePx >= 512) {
                Bitmap.Config.ARGB_8888
            } else {
                Bitmap.Config.RGB_565
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = preferredConfig
            }
            BitmapFactory.decodeFile(filePath, decodeOptions)
                ?: if (preferredConfig != Bitmap.Config.ARGB_8888) {
                    val fallbackOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeFile(filePath, fallbackOptions)
                } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, targetSizePx: Int): Int {
        var sampleSize = 1
        if (height > targetSizePx || width > targetSizePx) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / sampleSize) >= targetSizePx && (halfWidth / sampleSize) >= targetSizePx) {
                sampleSize *= 2
            }
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun scaleDownIfOversized(bitmap: Bitmap, targetSizePx: Int): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= targetSizePx * 2 || targetSizePx <= 0) return bitmap
        val ratio = targetSizePx.toFloat() / maxDim.toFloat()
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } catch (_: Throwable) {
            bitmap
        }
    }

    fun clear() {
        missingArtworkUris.clear()
        missingPrimaryOnlyUris.clear()
        verifiedArtworkUris.clear()
        inFlightDecodes.clear()
        fastReadCache.clear()
        synchronized(bitmapCache) {
            bitmapCache.evictAll()
        }
    }

    /**
     * Coil Interceptor that short-circuits known-missing album art URIs before touching
     * ContentResolver IPC, and records newly discovered missing URIs.
     */
    class NegativeCacheInterceptor : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            val uriString = request.data?.toString()
            if (!uriString.isNullOrBlank() && missingArtworkUris.contains(uriString)) {
                return ErrorResult(
                    drawable = null,
                    request = request,
                    throwable = missingArtworkException
                )
            }

            val result = chain.proceed(request)
            if (!uriString.isNullOrBlank()) {
                when (result) {
                    is SuccessResult -> markVerified(uriString)
                    is ErrorResult -> markMissing(uriString)
                }
            }
            return result
        }
    }
}

private class ArtworkLookupDescriptor(
    val primaryUriString: String?,
    val fallbackUriString: String?,
    val cleanPath: String?,
    val bytesHash: Int,
    val sourceIdentity: String,
    val targetBucket: Int,
    val cacheKey: String,
    val initialBitmap: ImageBitmap?,
    val initialExact: Boolean,
    val initialMissing: Boolean
)

/**
 * Reusable, zero-jank album artwork composable for list rows, album grids, mini player, and hero art.
 * - Immediately renders cached [ImageBitmap] synchronously on the first composition frame with zero
 *   coroutine launch or second recomposition when already in [ArtworkCacheManager].
 * - Resolves artwork from `artworkBytes`, `artworkUri`, `fallbackUri` (audio `contentUri`), and
 *   `fallbackPath` so tracks with embedded ID3/MP4/FLAC artwork display the exact same cover art
 *   shown in the Android notification panel and lock screen.
 * - Immediately renders [fallback] with zero overhead when all sources are null or known-missing.
 */
@Composable
fun FastArtworkImage(
    artworkUri: Any?,
    cacheKeyPrefix: String,
    sizePx: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallbackUri: Any? = null,
    fallbackPath: String? = null,
    artworkBytes: ByteArray? = null,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit
) {
    // Single remembered lookup descriptor per row to minimize per-frame composition allocations at 120Hz
    val descriptor = remember(artworkUri, fallbackUri, fallbackPath, sizePx, artworkBytes) {
        val primaryStr = when (artworkUri) {
            null -> null
            is String -> artworkUri.takeIf { it.isNotBlank() }
            else -> artworkUri.toString().takeIf { it.isNotBlank() }
        }
        val fallbackStr = when (fallbackUri) {
            null -> null
            is String -> fallbackUri.takeIf { it.isNotBlank() }
            else -> fallbackUri.toString().takeIf { it.isNotBlank() }
        }
        val pathStr = fallbackPath?.takeIf { it.isNotBlank() }
        val bHash = if (artworkBytes != null && artworkBytes.isNotEmpty()) artworkBytes.contentHashCode() else 0
        val baseIdentity = ArtworkCacheManager.buildSourceIdentity(primaryStr, fallbackStr, pathStr)
        val identity = if (baseIdentity.isNotEmpty()) baseIdentity else if (bHash != 0) "bytes_$bHash" else ""
        val bucket = ArtworkCacheManager.bucketSizeFor(sizePx)
        val key = if (identity.isEmpty()) "" else "${bucket}_$identity"

        val missing = ArtworkCacheManager.isSourceKnownMissing(
            primaryUri = primaryStr,
            fallbackUri = fallbackStr,
            fallbackPath = pathStr,
            hasEmbeddedBytes = bHash != 0
        )
        val (bmp, exact) = if (key.isEmpty() || missing) {
            null to false
        } else {
            ArtworkCacheManager.getBestAvailableBitmap(
                primaryUri = primaryStr,
                fallbackUri = fallbackStr,
                fallbackPath = pathStr,
                sizePx = bucket
            )
        }
        ArtworkLookupDescriptor(
            primaryUriString = primaryStr,
            fallbackUriString = fallbackStr,
            cleanPath = pathStr,
            bytesHash = bHash,
            sourceIdentity = identity,
            targetBucket = bucket,
            cacheKey = key,
            initialBitmap = bmp,
            initialExact = exact,
            initialMissing = missing
        )
    }

    var loadedBitmap by remember(descriptor.cacheKey) { mutableStateOf(descriptor.initialBitmap) }
    var isExactResolution by remember(descriptor.cacheKey) { mutableStateOf(descriptor.initialExact) }
    var isMissing by remember(descriptor.sourceIdentity) { mutableStateOf(descriptor.initialMissing) }

    // Decode on background thread when not yet decoded at target resolution
    if ((!isExactResolution || loadedBitmap == null) && !isMissing && descriptor.sourceIdentity.isNotEmpty()) {
        val context = LocalContext.current
        LaunchedEffect(descriptor.cacheKey, descriptor.bytesHash) {
            val decoded = ArtworkCacheManager.loadOrDecodeArtwork(
                context = context,
                primaryUri = descriptor.primaryUriString,
                fallbackUri = descriptor.fallbackUriString,
                fallbackPath = descriptor.cleanPath,
                artworkBytes = artworkBytes,
                sizePx = descriptor.targetBucket
            )
            if (decoded != null) {
                loadedBitmap = decoded
                isExactResolution = true
                isMissing = false
            } else if (loadedBitmap == null) {
                isMissing = true
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val bitmap = loadedBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            fallback()
        }
    }
}
