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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import com.example.data.model.Album
import com.example.data.model.Song
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * High-performance two-tier (lock-free memory + downsampled disk thumbnail) artwork cache and
 * multi-source decoder modeled after Glide's EngineKey deduplication, `DiskCacheStrategy.RESOURCE`,
 * `Bitmap.prepareToDraw()`, and RecyclerView off-screen request cancellation.
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
    private val fastReadCache = ConcurrentHashMap<String, ImageBitmap>(384)

    // Give memory cache up to 1/6th of heap (up to 48MB) so large song/album libraries stay resident in RAM
    private val maxCacheKb: Int = ((Runtime.getRuntime().maxMemory() / 1024L) / 6L)
        .toInt()
        .coerceIn(6144, 49152)

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

    private class InFlightDecodeEntry(
        val deferred: Deferred<ImageBitmap?>,
        val subscribers: AtomicInteger = AtomicInteger(0)
    )

    // Dedicated high-priority pool for visible UI items vs low-priority pool for lookahead prewarming
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val uiDecodeDispatcher = Dispatchers.IO.limitedParallelism(4)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val prewarmDispatcher = Dispatchers.IO.limitedParallelism(2)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val diskWriteDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val decodeScope = CoroutineScope(SupervisorJob() + uiDecodeDispatcher)
    private val diskWriteScope = CoroutineScope(SupervisorJob() + diskWriteDispatcher)
    private val inFlightDecodes = ConcurrentHashMap<String, InFlightDecodeEntry>()
    private val activeUiDecodeCount = AtomicInteger(0)

    @Volatile
    private var diskCacheDir: File? = null

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
        hasEmbeddedBytes: Boolean = false,
        precomputedIdentity: String? = null
    ): Boolean {
        if (hasEmbeddedBytes) return false
        val identity = precomputedIdentity ?: buildSourceIdentity(primaryUri, fallbackUri, fallbackPath)
        if (identity.isEmpty()) return true
        if (missingArtworkUris.contains(identity)) return true
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
     * bucket size (e.g. 256px or 144px thumbnail) so Now Playing (`512px`) or Album cards can display
     * an instant preview on frame 1 while the target bucket decode finishes in the background.
     */
    fun getBestAvailableBitmap(
        primaryUri: String?,
        fallbackUri: String? = null,
        fallbackPath: String? = null,
        sizePx: Int,
        precomputedIdentity: String? = null,
        precomputedCacheKey: String? = null
    ): Pair<ImageBitmap?, Boolean> {
        val identity = precomputedIdentity ?: buildSourceIdentity(primaryUri, fallbackUri, fallbackPath)
        if (identity.isNotEmpty() && missingArtworkUris.contains(identity)) {
            return null to false
        }

        val targetBucket = bucketSizeFor(sizePx)
        val exactKey = precomputedCacheKey?.takeIf { it.isNotEmpty() }
            ?: if (identity.isNotEmpty()) "${targetBucket}_$identity" else ""

        // 1. Check exact bucket size first (lock-free ConcurrentHashMap reads)
        if (exactKey.isNotEmpty()) {
            fastReadCache[exactKey]?.let { return it to true }
        }
        if (!primaryUri.isNullOrBlank() && primaryUri != identity) {
            fastReadCache["${targetBucket}_$primaryUri"]?.let { cached ->
                if (exactKey.isNotEmpty()) {
                    fastReadCache[exactKey] = cached
                }
                return cached to true
            }
        }
        if (!fallbackUri.isNullOrBlank() && fallbackUri != primaryUri && fallbackUri != identity) {
            fastReadCache["${targetBucket}_$fallbackUri"]?.let { cached ->
                if (exactKey.isNotEmpty()) {
                    fastReadCache[exactKey] = cached
                }
                return cached to true
            }
        }

        if (fastReadCache.isEmpty()) {
            return null to false
        }

        // 2. If requesting a larger/different size, check if another bucket is already cached
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
            sizePx = sizePx,
            isPrewarm = false
        )
    }

    suspend fun loadOrDecodeArtwork(
        context: Context,
        primaryUri: String?,
        fallbackUri: String? = null,
        fallbackPath: String? = null,
        artworkBytes: ByteArray? = null,
        sizePx: Int,
        isPrewarm: Boolean = false
    ): ImageBitmap? {
        val hasBytes = artworkBytes != null && artworkBytes.isNotEmpty()
        val identity = buildSourceIdentity(primaryUri, fallbackUri, fallbackPath)
        if (identity.isEmpty() && !hasBytes) return null
        if (isSourceKnownMissing(primaryUri, fallbackUri, fallbackPath, hasBytes, identity)) return null

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

        val dispatcher = if (isPrewarm) prewarmDispatcher else uiDecodeDispatcher
        if (!isPrewarm) {
            activeUiDecodeCount.incrementAndGet()
        }
        return try {
            withContext(dispatcher) {
                getCachedBitmap(cacheKey)?.let { return@withContext it }
                if (!primaryUri.isNullOrBlank()) {
                    getCachedBitmap("${bucketSize}_$primaryUri")?.let { cached ->
                        putCachedBitmap(cacheKey, cached)
                        return@withContext cached
                    }
                }
                awaitDeduplicatedDecode(cacheKey) {
                    decodeMultiSourceArtwork(
                        appContext = context.applicationContext,
                        primaryUri = primaryUri,
                        fallbackUri = fallbackUri,
                        fallbackPath = fallbackPath,
                        artworkBytes = artworkBytes,
                        targetSizePx = bucketSize,
                        effectiveIdentity = effectiveIdentity
                    )
                }
            }
        } finally {
            if (!isPrewarm) {
                activeUiDecodeCount.decrementAndGet()
            }
        }
    }

    /**
     * Reference-counted in-flight decode deduplication.
     * - If 8 visible rows request the same key, they share 1 background decode.
     * - If all visible callers scroll off-screen and their `LaunchedEffect`s cancel,
     *   `subscribers` drops to 0 and the underlying decode job is immediately cancelled
     *   (equivalent to `Glide.clear(viewHolder.imageView)` in `RecyclerView.onViewRecycled`).
     */
    private suspend fun awaitDeduplicatedDecode(
        key: String,
        decodeBlock: suspend () -> ImageBitmap?
    ): ImageBitmap? {
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = inFlightDecodes.compute(key) { _, existing ->
                if (existing != null && existing.deferred.isActive) {
                    existing.subscribers.incrementAndGet()
                    existing
                } else {
                    val newDeferred = decodeScope.async {
                        try {
                            decodeBlock()
                        } finally {
                            inFlightDecodes.remove(key)
                        }
                    }
                    InFlightDecodeEntry(newDeferred, AtomicInteger(1))
                }
            }!!

            try {
                return entry.deferred.await()
            } catch (ce: CancellationException) {
                val remaining = entry.subscribers.decrementAndGet()
                if (remaining <= 0) {
                    inFlightDecodes.remove(key, entry)
                    entry.deferred.cancel()
                }
                // Re-check if our own caller coroutine was cancelled (scrolled off-screen)
                currentCoroutineContext().ensureActive()
                // If caller is still active but the shared deferred was cancelled by another path, retry once
            } catch (_: Throwable) {
                return null
            }
        }
    }

    suspend fun prewarmArtwork(
        context: Context,
        uriStrings: Collection<String>,
        sizePx: Int = 144
    ) = withContext(prewarmDispatcher) {
        for (uriString in uriStrings) {
            currentCoroutineContext().ensureActive()
            while (activeUiDecodeCount.get() > 0) {
                delay(16L)
            }
            if (uriString.isBlank() || isKnownMissing(uriString)) continue
            val cacheKey = cacheKeyFor(uriString, sizePx)
            if (getCachedBitmap(cacheKey) != null) continue
            loadOrDecodeArtwork(
                context = context,
                primaryUri = uriString,
                fallbackUri = null,
                fallbackPath = null,
                artworkBytes = null,
                sizePx = sizePx,
                isPrewarm = true
            )
            yield()
        }
    }

    suspend fun prewarmArtworkSources(
        context: Context,
        sources: Collection<Triple<String?, String?, String?>>,
        sizePx: Int = 144
    ) = withContext(prewarmDispatcher) {
        val bucketSize = bucketSizeFor(sizePx)
        for ((primaryUri, fallbackUri, fallbackPath) in sources) {
            currentCoroutineContext().ensureActive()
            // Yield to visible on-screen decodes during active scrolling
            while (activeUiDecodeCount.get() > 0) {
                delay(16L)
            }
            val identity = buildSourceIdentity(primaryUri, fallbackUri, fallbackPath)
            if (identity.isEmpty() || isSourceKnownMissing(primaryUri, fallbackUri, fallbackPath, false, identity)) continue
            if (getCachedBitmap("${bucketSize}_$identity") != null) continue
            if (!primaryUri.isNullOrBlank() && getCachedBitmap("${bucketSize}_$primaryUri") != null) continue
            loadOrDecodeArtwork(
                context = context,
                primaryUri = primaryUri,
                fallbackUri = fallbackUri,
                fallbackPath = fallbackPath,
                artworkBytes = null,
                sizePx = bucketSize,
                isPrewarm = true
            )
            yield()
        }
    }

    private suspend fun decodeMultiSourceArtwork(
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
        val primaryCacheKey = if (cleanPrimary != null) "${targetSizePx}_$cleanPrimary" else null

        if (primaryCacheKey != null) {
            getCachedBitmap(primaryCacheKey)?.let { cached ->
                putCachedBitmap(cacheKey, cached)
                return cached
            }
        }

        currentCoroutineContext().ensureActive()

        // 0. Check two-tier disk thumbnail cache first (Glide DiskCacheStrategy.RESOURCE equivalent)
        if (artworkBytes == null || artworkBytes.isEmpty()) {
            val diskBitmap = readDiskCachedThumbnail(appContext, cacheKey)
                ?: (if (primaryCacheKey != null && primaryCacheKey != cacheKey && !missingPrimaryOnlyUris.contains(cleanPrimary)) {
                    readDiskCachedThumbnail(appContext, primaryCacheKey)
                } else null)

            if (diskBitmap != null && diskBitmap.width > 0 && diskBitmap.height > 0) {
                try {
                    diskBitmap.prepareToDraw()
                } catch (_: Throwable) {
                }
                val imageBitmap = diskBitmap.asImageBitmap()
                putCachedBitmap(cacheKey, imageBitmap)
                markVerified(effectiveIdentity)
                if (cleanPrimary != null && !missingPrimaryOnlyUris.contains(cleanPrimary)) {
                    putCachedBitmap("${targetSizePx}_$cleanPrimary", imageBitmap)
                    markVerified(cleanPrimary)
                }
                if (cleanFallback != null) {
                    putCachedBitmap("${targetSizePx}_$cleanFallback", imageBitmap)
                    markVerified(cleanFallback)
                }
                return imageBitmap
            }
        }

        var decodedBitmap: Bitmap? = null
        var decodedFromPrimary = false

        // 1. If Media3 / ExoPlayer already extracted embedded artworkData bytes for this track, decode them first
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            decodedBitmap = decodeSampledByteArray(artworkBytes, targetSizePx)
        }

        // 2. Try primary artwork URI (e.g. content://media/external/audio/albumart/{albumId})
        //    Deduplicate by primaryCacheKey so multiple visible songs from the same album share 1 decode!
        if (decodedBitmap == null && cleanPrimary != null && !missingPrimaryOnlyUris.contains(cleanPrimary)) {
            currentCoroutineContext().ensureActive()
            if (cleanPrimary != cleanFallback && primaryCacheKey != null) {
                val primaryImageBitmap = awaitDeduplicatedDecode("primary_$primaryCacheKey") {
                    getCachedBitmap(primaryCacheKey)?.let { return@awaitDeduplicatedDecode it }
                    val bmp = decodeFromUriString(appContext, cleanPrimary, targetSizePx)
                    if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                        try {
                            bmp.prepareToDraw()
                        } catch (_: Throwable) {
                        }
                        val img = bmp.asImageBitmap()
                        putCachedBitmap(primaryCacheKey, img)
                        markVerified(cleanPrimary)
                        scheduleDiskCacheWrite(appContext, primaryCacheKey, bmp)
                        img
                    } else {
                        missingPrimaryOnlyUris.add(cleanPrimary)
                        null
                    }
                }
                if (primaryImageBitmap != null) {
                    putCachedBitmap(cacheKey, primaryImageBitmap)
                    markVerified(effectiveIdentity)
                    if (cleanFallback != null) {
                        putCachedBitmap("${targetSizePx}_$cleanFallback", primaryImageBitmap)
                        markVerified(cleanFallback)
                    }
                    return primaryImageBitmap
                }
            } else {
                decodedBitmap = decodeFromUriString(appContext, cleanPrimary, targetSizePx)
                if (decodedBitmap != null) {
                    decodedFromPrimary = true
                }
            }
        }

        // 3. Try fallback audio contentUri (e.g. content://media/external/audio/media/{songId}) or file path
        //    This is the exact source Media3 / Android Notification uses when albumart/{albumId} is absent!
        if (decodedBitmap == null && cleanFallback != null && cleanFallback != cleanPrimary) {
            currentCoroutineContext().ensureActive()
            decodedBitmap = decodeFromAudioUriOrEmbedded(appContext, cleanFallback, cleanPath, targetSizePx)
        }

        // 4. If primaryUri itself was an audio URI or file path, also try embedded picture extraction
        if (decodedBitmap == null) {
            currentCoroutineContext().ensureActive()
            val candidateUri = cleanFallback ?: cleanPrimary
            if (candidateUri != null || cleanPath != null) {
                decodedBitmap = extractEmbeddedPictureBitmap(appContext, candidateUri, cleanPath, targetSizePx)
            }
        }

        return if (decodedBitmap != null && decodedBitmap.width > 0 && decodedBitmap.height > 0) {
            // Prepare bitmap on background decode thread so RenderThread uploads texture asynchronously
            // without stalling 120Hz UI frames on first draw (Glide Bitmap.prepareToDraw technique)
            try {
                decodedBitmap.prepareToDraw()
            } catch (_: Throwable) {
            }
            val imageBitmap = decodedBitmap.asImageBitmap()
            putCachedBitmap(cacheKey, imageBitmap)
            markVerified(effectiveIdentity)
            if (cleanPrimary != null && (decodedFromPrimary || artworkBytes != null)) {
                putCachedBitmap("${targetSizePx}_$cleanPrimary", imageBitmap)
                markVerified(cleanPrimary)
            }
            if (cleanFallback != null) {
                putCachedBitmap("${targetSizePx}_$cleanFallback", imageBitmap)
                markVerified(cleanFallback)
            }
            scheduleDiskCacheWrite(appContext, cacheKey, decodedBitmap)
            imageBitmap
        } else {
            markMissing(effectiveIdentity)
            if (cleanFallback == null && cleanPath == null && cleanPrimary != null) {
                markMissing(cleanPrimary)
            }
            null
        }
    }

    private fun getOrCreateDiskCacheDir(appContext: Context): File? {
        val existing = diskCacheDir
        if (existing != null && existing.exists()) return existing
        return try {
            val dir = File(appContext.cacheDir, "artwork_thumbs_v1")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            diskCacheDir = dir
            dir
        } catch (_: Throwable) {
            null
        }
    }

    private fun diskCacheFileForKey(dir: File, key: String): File {
        // Fast 64-bit FNV-1a hash + length suffix to avoid filename collisions and filesystem path limits
        var hash = -0x340d631b7bdddcdbL
        for (i in 0 until key.length) {
            hash = hash xor key[i].code.toLong()
            hash *= 0x100000001b3L
        }
        val hex = java.lang.Long.toUnsignedString(hash, 16)
        return File(dir, "thumb_${hex}_${key.length}.jpg")
    }

    private fun readDiskCachedThumbnail(appContext: Context, key: String): Bitmap? {
        val dir = getOrCreateDiskCacheDir(appContext) ?: return null
        val file = diskCacheFileForKey(dir, key)
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            val isHero = key.startsWith("512_")
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = if (isHero) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Throwable) {
            null
        }
    }

    private fun scheduleDiskCacheWrite(appContext: Context, key: String, bitmap: Bitmap) {
        diskWriteScope.launch {
            try {
                val dir = getOrCreateDiskCacheDir(appContext) ?: return@launch
                val targetFile = diskCacheFileForKey(dir, key)
                if (targetFile.exists() && targetFile.length() > 0L) return@launch
                val tmpFile = File(dir, "${targetFile.name}.tmp")
                FileOutputStream(tmpFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
                    out.flush()
                }
                if (tmpFile.exists() && tmpFile.length() > 0L) {
                    tmpFile.renameTo(targetFile)
                } else {
                    tmpFile.delete()
                }
            } catch (_: Throwable) {
            }
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
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: if (preferredConfig != Bitmap.Config.ARGB_8888) {
                    val fallbackOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, fallbackOptions)
                } else null
            decoded?.let { scaleDownIfOversized(it, targetSizePx) }
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
            val decoded = BitmapFactory.decodeFileDescriptor(fd, null, decodeOptions)
                ?: if (preferredConfig != Bitmap.Config.ARGB_8888) {
                    val fallbackOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeFileDescriptor(fd, null, fallbackOptions)
                } else null
            decoded?.let { scaleDownIfOversized(it, targetSizePx) }
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
            val decoded = BitmapFactory.decodeFile(filePath, decodeOptions)
                ?: if (preferredConfig != Bitmap.Config.ARGB_8888) {
                    val fallbackOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeFile(filePath, fallbackOptions)
                } else null
            decoded?.let { scaleDownIfOversized(it, targetSizePx) }
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
        if (maxDim <= (targetSizePx * 3) / 2 || targetSizePx <= 0) return bitmap
        val ratio = targetSizePx.toFloat() / maxDim.toFloat()
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } catch (_: Throwable) {
            bitmap
        }
    }

    /**
     * Clears only negative/missing artwork records so newly added album art can be discovered on rescan
     * WITHOUT evicting already-decoded [ImageBitmap] thumbnails from memory or disk.
     */
    fun clearNegativeCacheOnly() {
        missingArtworkUris.clear()
        missingPrimaryOnlyUris.clear()
    }

    fun clear() {
        missingArtworkUris.clear()
        missingPrimaryOnlyUris.clear()
        verifiedArtworkUris.clear()
        inFlightDecodes.values.forEach { it.deferred.cancel() }
        inFlightDecodes.clear()
        fastReadCache.clear()
        synchronized(bitmapCache) {
            bitmapCache.evictAll()
        }
        try {
            diskCacheDir?.listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    internal fun activeInFlightDecodeCount(): Int = inFlightDecodes.size

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
    val needsAsyncDecode: Boolean
)

/**
 * Reusable, zero-jank album artwork composable for list rows, album grids, mini player, and hero art.
 * - Immediately renders cached [ImageBitmap] synchronously on the first composition frame with zero
 *   coroutine launch or second recomposition when already in [ArtworkCacheManager].
 * - Cancels in-flight background decoding immediately if the item scrolls off-screen during a 120Hz fling.
 * - Uses a single [MutableState] slot when async decoding is needed, and zero state writes when cached.
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
    precomputedIdentity: String? = null,
    precomputedCacheKey: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit
) {
    val descriptor = remember(
        artworkUri,
        fallbackUri,
        fallbackPath,
        sizePx,
        artworkBytes,
        precomputedIdentity,
        precomputedCacheKey
    ) {
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
        val baseIdentity = precomputedIdentity?.takeIf { it.isNotEmpty() }
            ?: ArtworkCacheManager.buildSourceIdentity(primaryStr, fallbackStr, pathStr)
        val identity = if (baseIdentity.isNotEmpty()) baseIdentity else if (bHash != 0) "bytes_$bHash" else ""
        val bucket = ArtworkCacheManager.bucketSizeFor(sizePx)
        val key = if (bHash == 0 && !precomputedCacheKey.isNullOrEmpty()) {
            precomputedCacheKey
        } else if (identity.isEmpty()) {
            ""
        } else {
            "${bucket}_$identity"
        }

        val missing = ArtworkCacheManager.isSourceKnownMissing(
            primaryUri = primaryStr,
            fallbackUri = fallbackStr,
            fallbackPath = pathStr,
            hasEmbeddedBytes = bHash != 0,
            precomputedIdentity = identity
        )
        val (bmp, exact) = if (key.isEmpty() || missing) {
            null to false
        } else {
            ArtworkCacheManager.getBestAvailableBitmap(
                primaryUri = primaryStr,
                fallbackUri = fallbackStr,
                fallbackPath = pathStr,
                sizePx = bucket,
                precomputedIdentity = identity,
                precomputedCacheKey = key
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
            needsAsyncDecode = !exact && !missing && identity.isNotEmpty()
        )
    }

    var asyncBitmap by remember(descriptor.cacheKey, descriptor.bytesHash) {
        mutableStateOf(descriptor.initialBitmap)
    }

    if (descriptor.needsAsyncDecode) {
        val context = LocalContext.current
        LaunchedEffect(descriptor.cacheKey, descriptor.bytesHash) {
            val decoded = ArtworkCacheManager.loadOrDecodeArtwork(
                context = context,
                primaryUri = descriptor.primaryUriString,
                fallbackUri = descriptor.fallbackUriString,
                fallbackPath = descriptor.cleanPath,
                artworkBytes = artworkBytes,
                sizePx = descriptor.targetBucket,
                isPrewarm = false
            )
            if (decoded != null && asyncBitmap !== decoded) {
                asyncBitmap = decoded
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val bitmap = if (descriptor.needsAsyncDecode) asyncBitmap else descriptor.initialBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                filterQuality = FilterQuality.Low
            )
        } else {
            fallback()
        }
    }
}

/**
 * Viewport-aware lookahead artwork preloader for [LazyListState] (Compose equivalent of
 * Glide's `RecyclerViewPreloader`). Preloads upcoming song thumbnails ahead of the scroll
 * viewport on a low-priority background dispatcher without competing with active on-screen decodes.
 */
@Composable
fun LazyListSongArtworkPreloader(
    listState: LazyListState,
    songs: List<Song>,
    preloadAheadCount: Int = 12,
    headerItemCount: Int = 1
) {
    if (songs.isEmpty()) return
    val context = LocalContext.current
    LaunchedEffect(listState, songs) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisible ->
                val startSongIndex = (firstVisible - headerItemCount).coerceAtLeast(0)
                val endSongIndex = (startSongIndex + preloadAheadCount + 6).coerceAtMost(songs.size)
                if (startSongIndex >= endSongIndex) return@collect
                val slice = ArrayList<Triple<String?, String?, String?>>(endSongIndex - startSongIndex)
                for (i in startSongIndex until endSongIndex) {
                    val s = songs[i]
                    if (ArtworkCacheManager.getCachedBitmap(s.thumbCacheKey) == null &&
                        !ArtworkCacheManager.isSourceKnownMissing(
                            primaryUri = s.albumArtUriString,
                            fallbackUri = s.contentUriString,
                            fallbackPath = s.path,
                            precomputedIdentity = s.artworkSourceIdentity
                        )
                    ) {
                        slice.add(Triple(s.albumArtUriString, s.contentUriString, s.path))
                    }
                }
                if (slice.isNotEmpty()) {
                    ArtworkCacheManager.prewarmArtworkSources(
                        context = context,
                        sources = slice,
                        sizePx = 144
                    )
                }
            }
    }
}

/**
 * Viewport-aware lookahead artwork preloader for [LazyGridState] album grids.
 */
@Composable
fun LazyGridAlbumArtworkPreloader(
    gridState: LazyGridState,
    albums: List<Album>,
    preloadAheadCount: Int = 10
) {
    if (albums.isEmpty()) return
    val context = LocalContext.current
    LaunchedEffect(gridState, albums) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisible ->
                val startIdx = firstVisible.coerceAtLeast(0)
                val endIdx = (startIdx + preloadAheadCount + 6).coerceAtMost(albums.size)
                if (startIdx >= endIdx) return@collect
                val slice = ArrayList<Triple<String?, String?, String?>>(endIdx - startIdx)
                for (i in startIdx until endIdx) {
                    val a = albums[i]
                    if (ArtworkCacheManager.getCachedBitmap(a.albumCacheKey) == null &&
                        !ArtworkCacheManager.isSourceKnownMissing(
                            primaryUri = a.artworkUriString,
                            fallbackUri = a.fallbackAudioUriString,
                            fallbackPath = a.fallbackAudioPath,
                            precomputedIdentity = a.artworkSourceIdentity
                        )
                    ) {
                        slice.add(Triple(a.artworkUriString, a.fallbackAudioUriString, a.fallbackAudioPath))
                    }
                }
                if (slice.isNotEmpty()) {
                    ArtworkCacheManager.prewarmArtworkSources(
                        context = context,
                        sources = slice,
                        sizePx = 256
                    )
                }
            }
    }
}
