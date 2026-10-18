package com.example

import android.app.Application
import com.example.data.local.PulseDatabase
import com.example.data.local.UserPreferences
import com.example.data.repository.AudioScanRepository
import com.example.data.repository.MediaRepository

class PulseMusicApp : Application() {

    lateinit var database: PulseDatabase
        private set

    lateinit var mediaRepository: MediaRepository
        private set

    lateinit var audioScanRepository: AudioScanRepository
        private set

    lateinit var userPreferences: UserPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        database = PulseDatabase.getInstance(this)
        mediaRepository = MediaRepository(this)
        audioScanRepository = AudioScanRepository(this, database.musicDao())
        userPreferences = UserPreferences(this)
    }
}
