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

    lateinit var database: PulseDatabase
        private set

    lateinit var mediaRepository: MediaRepository
        private set

    lateinit var audioScanRepository: AudioScanRepository
        private set

    lateinit var playbackStateRepository: PlaybackStateRepository
        private set

    lateinit var userPreferences: UserPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        database = PulseDatabase.getInstance(this)
        mediaRepository = MediaRepository(this)
        audioScanRepository = AudioScanRepository(this, database.musicDao())
        playbackStateRepository = PlaybackStateRepository(this, database.musicDao())
        userPreferences = UserPreferences(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("album_art_cache"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .allowHardware(true)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
}
