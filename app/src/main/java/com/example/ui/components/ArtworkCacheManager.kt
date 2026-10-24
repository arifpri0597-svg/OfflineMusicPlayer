package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Precision
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance in-memory cache of missing/failed and verified album art URIs.
 *
 * Why this is critical for real-device scrolling performance:
 * Every MediaStore audio row has a non-null `ALBUM_ID`, producing a synthetic URI like
 * `content://media/external/audio/albumart/{albumId}` even when the audio file has no embedded art.
 * Coil caches successful bitmaps in MemoryCache/DiskCache, but NEVER caches failed URIs
 * (`FileNotFoundException` from `ContentResolver.openInputStream`). Without negative caching,
 * scrolling a list of local MP3s triggers hundreds of repeated cross-process Binder IPC calls
 * to `MediaProvider` that throw `FileNotFoundException` on every scroll pass.
 */
object ArtworkCacheManager {
    private val missingArtworkUris: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val verifiedArtworkUris: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    fun clear() {
        missingArtworkUris.clear()
        verifiedArtworkUris.clear()
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
 * - Immediately renders [fallback] with zero AsyncImage/ContentResolver overhead when the URI is null
 *   or already known to have no artwork in [ArtworkCacheManager].
 * - Downsamples to [sizePx] x [sizePx] with [Precision.INEXACT] and caches in both memory and disk.
 * - Displays [fallback] cleanly until artwork is verified and loaded.
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
    val context = LocalContext.current
    val safeSizePx = sizePx.coerceAtLeast(64)
    val uriString = remember(artworkUri) {
        artworkUri?.toString()?.takeIf { it.isNotBlank() }
    }
    var isMissing by remember(uriString) {
        mutableStateOf(uriString == null || ArtworkCacheManager.isKnownMissing(uriString))
    }
    var isLoaded by remember(uriString) {
        mutableStateOf(uriString != null && ArtworkCacheManager.isVerified(uriString))
    }

    val imageRequest = remember(uriString, cacheKeyPrefix, safeSizePx) {
        if (uriString == null || ArtworkCacheManager.isKnownMissing(uriString)) {
            null
        } else {
            runCatching {
                val key = "${cacheKeyPrefix}_${safeSizePx}_$uriString"
                ImageRequest.Builder(context)
                    .data(artworkUri)
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(false)
                    .size(safeSizePx, safeSizePx)
                    .precision(Precision.INEXACT)
                    .build()
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (uriString == null || isMissing || imageRequest == null) {
            fallback()
        } else {
            if (!isLoaded) {
                fallback()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = {
                    isLoaded = true
                    ArtworkCacheManager.markVerified(uriString)
                },
                onError = {
                    isMissing = true
                    ArtworkCacheManager.markMissing(uriString)
                }
            )
        }
    }
}
