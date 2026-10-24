package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.local.PulseDatabase
import com.example.data.local.UserPreferences
import com.example.data.repository.AudioScanRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.PlaybackStateRepository

class PulseMusicApp : Application(), ImageLoaderFactory {

    val database: PulseDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PulseDatabase.getInstance(this)
    }

    val mediaRepository: MediaRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MediaRepository(this)
    }

    val audioScanRepository: AudioScanRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioScanRepository(this, database.musicDao())
    }

    val playbackStateRepository: PlaybackStateRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackStateRepository(this, database.musicDao())
    }

    val userPreferences: UserPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UserPreferences(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    @OptIn( kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader {
        val ioDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(4)
        val decodeDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2)
        return ImageLoader.Builder(this)
            .components {
                add(com.example.ui.components.ArtworkCacheManager.NegativeCacheInterceptor())
            }
            .fetcherDispatcher(ioDispatcher)
            .decoderDispatcher(decodeDispatcher)
            .transformationDispatcher(decodeDispatcher)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("album_art_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .allowHardware(true)
            .allowRgb565(true)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }

    companion object {
        @Volatile
        var instance: PulseMusicApp? = null
            private set
    }
}
