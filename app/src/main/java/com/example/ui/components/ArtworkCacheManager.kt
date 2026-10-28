package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
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
 * Why this is critical for 60/90/120Hz scrolling performance:
 * 1. Every MediaStore audio row has a non-null `ALBUM_ID`, producing a synthetic URI like
 *    `content://media/external/audio/albumart/{albumId}` even when the audio file has no art.
 *    Negative caching avoids repeated cross-process Binder IPC `FileNotFoundException`s.
 * 2. Even when artwork is cached in Coil, `AsyncImage` launches a coroutine on every item enter
 *    and triggers a state transition recomposition. By storing downsampled `RGB_565` [ImageBitmap]s
 *    directly in an in-memory [LruCache] and deduplicating concurrent decodes per URI, visible rows
 *    render synchronously on their very first composition frame with zero coroutine launch and
 *    zero second recomposition pass.
 */
object ArtworkCacheManager {
    private val missingArtworkUris: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val verifiedArtworkUris: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val maxCacheKb: Int = ((Runtime.getRuntime().maxMemory() / 1024L) / 8L)
        .toInt()
        .coerceIn(4096, 32768)

    private val bitmapCache = object : LruCache<String, ImageBitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            // RGB_565 uses 2 bytes per pixel
            return ((value.width * value.height * 2) / 1024).coerceAtLeast(1)
        }
    }

    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val decodeScope = CoroutineScope(SupervisorJob() + decodeDispatcher)
    private val inFlightDecodes = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    private val missingArtworkException = NoSuchElementException("Cached missing album artwork")

    fun isKnownMissing(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return true
        return missingArtworkUris.contains(uriString)
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
        }
    }

    fun getCachedBitmap(cacheKey: String): ImageBitmap? {
        synchronized(bitmapCache) {
            return bitmapCache.get(cacheKey)
        }
    }

    private fun putCachedBitmap(cacheKey: String, bitmap: ImageBitmap) {
        synchronized(bitmapCache) {
            bitmapCache.put(cacheKey, bitmap)
        }
    }

    fun cacheKeyFor(uriString: String, sizePx: Int): String {
        val bucketSize = if (sizePx <= 160) 144 else 256
        return "${bucketSize}_$uriString"
    }

    suspend fun loadOrDecodeArtwork(
        context: Context,
        uriString: String,
        sizePx: Int
    ): ImageBitmap? {
        if (uriString.isBlank() || missingArtworkUris.contains(uriString)) return null
        val bucketSize = if (sizePx <= 160) 144 else 256
        val cacheKey = "${bucketSize}_$uriString"

        getCachedBitmap(cacheKey)?.let { return it }

        val deferred = inFlightDecodes.computeIfAbsent(cacheKey) {
            decodeScope.async {
                try {
                    decodeSampledBitmap(context.applicationContext, uriString, bucketSize)
                } finally {
                    inFlightDecodes.remove(cacheKey)
                }
            }
        }
        return try {
            deferred.await()
        } catch (_: Throwable) {
            null
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
        }
    }

    private fun decodeSampledBitmap(
        appContext: Context,
        uriString: String,
        targetSizePx: Int
    ): ImageBitmap? {
        if (missingArtworkUris.contains(uriString)) return null
        val cacheKey = "${targetSizePx}_$uriString"
        getCachedBitmap(cacheKey)?.let { return it }

        return try {
            val uri = Uri.parse(uriString)
            val resolver = appContext.contentResolver

            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: run {
                markMissing(uriString)
                return null
            }

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                markMissing(uriString)
                return null
            }

            var sampleSize = 1
            val halfWidth = boundsOptions.outWidth / 2
            val halfHeight = boundsOptions.outHeight / 2
            while ((halfWidth / sampleSize) >= targetSizePx && (halfHeight / sampleSize) >= targetSizePx) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }

            if (decoded != null) {
                val imageBitmap = decoded.asImageBitmap()
                putCachedBitmap(cacheKey, imageBitmap)
                markVerified(uriString)
                imageBitmap
            } else {
                markMissing(uriString)
                null
            }
        } catch (_: Throwable) {
            markMissing(uriString)
            null
        }
    }

    fun clear() {
        missingArtworkUris.clear()
        verifiedArtworkUris.clear()
        inFlightDecodes.clear()
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

/**
 * Reusable, zero-jank album artwork composable for list rows, album grids, mini player, and hero art.
 * - Immediately renders cached [ImageBitmap] synchronously on the first composition frame with zero
 *   coroutine launch or second recomposition when already in [ArtworkCacheManager].
 * - Immediately renders [fallback] with zero overhead when the URI is null or known-missing.
 * - Deduplicates concurrent background decodes when multiple songs from the same album scroll into view.
 */
@Composable
fun FastArtworkImage(
    artworkUri: Any?,
    cacheKeyPrefix: String,
    sizePx: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit
) {
    val uriString = remember(artworkUri) {
        when (artworkUri) {
            null -> null
            is String -> artworkUri.takeIf { it.isNotBlank() }
            else -> artworkUri.toString().takeIf { it.isNotBlank() }
        }
    }

    val cacheKey = remember(uriString, sizePx) {
        if (uriString == null) "" else ArtworkCacheManager.cacheKeyFor(uriString, sizePx)
    }

    // Fast synchronous lookup on composition frame
    val initialBitmap = remember(cacheKey) {
        if (cacheKey.isEmpty()) null else ArtworkCacheManager.getCachedBitmap(cacheKey)
    }
    val initialMissing = remember(uriString) {
        uriString == null || ArtworkCacheManager.isKnownMissing(uriString)
    }

    var loadedBitmap by remember(cacheKey) { mutableStateOf(initialBitmap) }
    var isMissing by remember(uriString) { mutableStateOf(initialMissing) }

    if (loadedBitmap == null && !isMissing && uriString != null) {
        val context = LocalContext.current
        LaunchedEffect(cacheKey) {
            val decoded = ArtworkCacheManager.loadOrDecodeArtwork(
                context = context,
                uriString = uriString,
                sizePx = sizePx
            )
            if (decoded != null) {
                loadedBitmap = decoded
            } else {
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

