# Pulse Music 🎵

A professional, secure, lightweight, 100% offline music player for Android built with modern Jetpack Compose, Material 3, AndroidX Media3 (ExoPlayer), and Room.

Designed from the ground up for privacy, reliability, battery efficiency, and zero network tracking.

---

## 🌟 Key Features

- **100% Offline Audio Playback:** Completely functional without an internet connection. No network permissions required (`android.permission.INTERNET` is omitted).
- **Background Playback & Lock-Screen Controls:** Powered by AndroidX Media3 `MediaSessionService` with lock-screen notification controls and system media compatibility.
- **Audio Focus & Headset Disconnect:** Automatically pauses audio when incoming calls occur or when headphones/Bluetooth headsets disconnect (`ACTION_AUDIO_BECOMING_NOISY`). Graceful ducking for navigation prompts.
- **10-Second Seek Buttons:** Clearly accessible `-10s` and `+10s` buttons with safe boundary clamping (`max(0, min(newPosition, duration))`).
- **Comprehensive Media Organization:**
  - **Songs:** Full library view with search and multi-criteria sorting (Title, Artist, Duration, Recently Added).
  - **Albums:** Visual grid with album artwork and dedicated album track views.
  - **Artists:** Artist groupings with discography drill-down.
  - **Folders:** Direct folder-based navigation matching your device's filesystem hierarchy.
  - **Favorites:** Instant heart toggle backed by local Room database.
  - **History:** Chronological playback history of recently played tracks.
- **Fast Local Search:** Real-time search across titles, artists, and albums supporting international character sets (including Bengali and English) with zero server transmission.
- **Mini Player & Full Now Playing:**
  - Persistent floating mini player with real-time progress bar.
  - Full-screen player with large artwork, playback controls, shuffle, repeat (Off/All/One), and interactive playback queue management.
- **Custom Adaptive Icon:** Beautiful adaptive launcher icon with vibrant glowing headphones and sonic equalizer waves.
- **Theme Support:** Material 3 dynamic color theming, with explicit System Default, Light, and Dark modes.

---

## 🔒 Security & Privacy Design

1. **Zero Network Transmission:** The app does not declare or request `android.permission.INTERNET`. Your music files, metadata, and history never leave your device.
2. **Minimal Permissions:**
   - **Android 13+ (API 33+):** Requests only `READ_MEDIA_AUDIO` and `POST_NOTIFICATIONS` for media playback controls.
   - **Android 10-12 (API 29-32):** Requests scoped `READ_EXTERNAL_STORAGE` capped at `maxSdkVersion="32"`.
   - **No `MANAGE_EXTERNAL_STORAGE`:** Strict adherence to Android modern storage practices using `MediaStore`.
3. **No Trackers or Analytics:** Zero SDKs for ads, telemetry, crash collection, or external logging.
4. **File Safety:** Read-only access to audio media via `ContentResolver`. The app never renames, moves, modifies, or deletes your music files.

---

## 📱 Supported Android Versions

- **Minimum SDK:** Android 7.0 (API 24)
- **Target SDK:** Android 15 / 16 (API 36)
- **Tested & Optimized For:** Android 10, Android 11, Android 12, Android 13, Android 14, Android 15+

---

## 📁 Project Structure

```
PulseMusic/
├── .github/
│   └── workflows/
│       └── build-apk.yml               # Automated GitHub Actions workflow to build debug APK
├── app/
│   ├── build.gradle.kts                # Application module Gradle script
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # Clean manifest with minimal permissions & services
│       │   ├── java/com/example/
│       │   │   ├── PulseMusicApp.kt    # Application singleton container
│       │   │   ├── MainActivity.kt     # Jetpack Compose UI root & permission handling
│       │   │   ├── data/
│       │   │   │   ├── local/          # Room DB (PulseDatabase, MusicDao, Entities, DataStore)
│       │   │   │   ├── model/          # Song, Album, Artist, Folder, enums
│       │   │   │   └── repository/     # MediaRepository (MediaStore scanner)
│       │   │   ├── player/             # Media3 ExoPlayer, MediaSession, PlayerUiState, Controller
│       │   │   │   └── service/        # PlaybackService (MediaSessionService)
│       │   │   └── ui/
│       │   │       ├── components/     # MiniPlayer, SongItemRow, Cards, SearchBar
│       │   │       ├── screens/        # Home, Songs, Albums, Artists, Folders, NowPlaying, Settings
│       │   │       └── theme/          # Material 3 Color, Theme, Typography
│       │   └── res/                    # Adaptive icon drawables, strings, themes
│       └── test/                       # Local JVM Robolectric and unit tests
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar          # Official Gradle Wrapper jar
│       └── gradle-wrapper.properties   # Gradle 9.3.1 distribution config
├── gradlew                             # Unix Gradle Wrapper script (executable)
├── gradlew.bat                         # Windows Gradle Wrapper script
├── build.gradle.kts                    # Root build script
├── settings.gradle.kts                 # Root settings script
├── gradle.properties                   # JVM & AndroidX build flags
└── README.md                           # Documentation
```

---

## 🛠️ How to Build Locally

### Prerequisites
- JDK 17 or JDK 21
- Android SDK with platform tools

### Build Steps

1. **Verify Gradle Wrapper:**
   ```bash
   chmod +x ./gradlew
   ./gradlew --version
   ```

2. **Assemble Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```

3. **Output APK Location:**
   The compiled APK will be located at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Run Unit & Robolectric Tests:**
   ```bash
   ./gradlew testDebugUnitTest
   ```

---

## 🚀 Building with GitHub Actions

This repository includes a ready-to-run GitHub Actions workflow located at:
`.github/workflows/build-apk.yml`

### What the Workflow Does:
1. Checks out the repository.
2. Configures Temurin JDK 17 with Gradle caching.
3. Explicitly verifies that `gradlew` and `gradle/wrapper/gradle-wrapper.jar` exist.
4. Sets execute permissions (`chmod +x ./gradlew`).
5. Executes `./gradlew assembleDebug --stacktrace`.
6. Uploads the generated APK as an artifact named **`OfflineMusicPlayer-debug-apk`**.

### Downloading the APK from GitHub Actions:
1. Push this project to GitHub.
2. Go to the **Actions** tab in your repository.
3. Select the latest **"Build Debug APK"** run.
4. Under **Artifacts**, download **`OfflineMusicPlayer-debug-apk`**.

---

## ❓ Troubleshooting

| Issue | Resolution |
|---|---|
| No songs appear on first launch | Tap **Grant Access** on the permission screen. Ensure your device has audio files (.mp3, .m4a, .flac, .wav) stored in `Music/` or `Download/`. |
| Permission was permanently denied | Tap **Open App Settings** in the permission view and enable the Music & Audio permission for Pulse Music. |
| Playback stops when swiping app from recents | If playback is paused when removing the app from recents, the service stops to conserve battery. Playback actively running will continue playing. |
| Album art is missing for certain tracks | Some audio files do not have embedded ID3 album artwork. Pulse Music displays a clean vector fallback in such cases. |
