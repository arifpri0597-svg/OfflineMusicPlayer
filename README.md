# PureTune (Pulse Music) 🎵

A fast, secure, lightweight, 100% offline music player for Android built with Jetpack Compose, Material 3, AndroidX Media3 (ExoPlayer), and Room.

Designed from the ground up for privacy, smooth 60Hz/90Hz/120Hz+ adaptive rendering, battery efficiency, and zero network tracking.

---

## 🌟 Key Features

- **100% Offline Audio Playback:** Completely functional without an internet connection. No network permissions are declared (`android.permission.INTERNET` is omitted).
- **Background Playback & Lock-Screen Controls:** Powered by AndroidX Media3 `MediaSessionService` with lock-screen notification controls, headset buttons, and system media integration.
- **Audio-Reactive Neon Visualizer:** Smooth, zero-allocation PCM audio visualizer on the Now Playing screen that responds to real-time music amplitude, bass, and beat transients.
- **Playback Speed & 10-Second Seek Controls:** Adjustable playback speed (`0.5x` to `2.0x`) and instant `-10s` / `+10s` seek buttons with boundary clamping.
- **Built-in Sleep Timer:** Configurable sleep timer (`5`, `10`, `15`, `30`, `45`, `60` minutes, or **End of current track**) integrated directly with background Media3 playback.
- **File Manager "Open With" Support:** Play audio files directly from external file managers via `content://` and `file://` intents with strict path-traversal and URI validation.
- **Comprehensive Media Organization:**
  - **Songs:** Full library view with real-time search and multi-criteria sorting (Title, Artist, Duration, Date Added).
  - **Playlists:** Custom local playlists with search, track management, shuffle, and instant playback backed by Room.
  - **Albums:** Visual grid with album artwork and dedicated album track views.
  - **Artists:** Artist groupings with discography drill-down.
  - **Folders:** Direct folder-based navigation matching your device's storage hierarchy.
  - **Favorites:** Instant heart toggle backed by local Room database.
- **Playback State Persistence:** Automatically saves and restores your current song, queue, playback position, shuffle/repeat modes, and playback speed across app restarts.
- **Automatic High-Refresh-Rate Policy:** Internal adaptive frame-rate policy that targets a 60 FPS minimum and scales smoothly to 90Hz, 120Hz, and higher supported display refresh rates.

---

## 🔒 Security & Privacy Design

1. **Zero Network Transmission:** The app does not declare or request `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`. Your audio files, playlists, favorites, and playback state never leave your device.
2. **Minimal Least-Privilege Permissions:**
   - **Android 13+ (API 33+):** Requests only `READ_MEDIA_AUDIO` and `POST_NOTIFICATIONS` for media playback notifications.
   - **Android 10–12 (API 29–32):** Requests scoped `READ_EXTERNAL_STORAGE` capped at `android:maxSdkVersion="32"`.
   - **Foreground Playback:** Uses `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` strictly for active media playback.
   - **No `MANAGE_EXTERNAL_STORAGE` or Write Permissions:** Uses read-only `MediaStore` and `ContentResolver` APIs.
3. **No Trackers, Ads, or Analytics:** Zero third-party SDKs for advertising, telemetry, crash reporting, or remote logging.
4. **Hardened External URI Validation:** Incoming `ACTION_VIEW` / `ACTION_OPEN_DOCUMENT` intents are restricted to read-only permissions (`FLAG_GRANT_READ_URI_PERMISSION`) and canonicalized to block path traversal into private app directories.
5. **Cloud Backup Disabled:** `android:allowBackup="false"` is enforced in `AndroidManifest.xml`.

For vulnerability reporting instructions, see [SECURITY.md](SECURITY.md).

---

## 📱 Supported Android Versions

- **Minimum SDK:** Android 5.0 (API 21)
- **Target / Compile SDK:** API 36

---

## 🛠️ How to Build Locally

### Prerequisites
- JDK 21
- Android SDK with Build-Tools and Platform API 36

### Build & Test Commands

1. **Run Unit & Robolectric Tests:**
   ```bash
   ./gradlew testDebugUnitTest
   ```

2. **Assemble & Verify Release APK:**
   ```bash
   ./gradlew assembleRelease verifyReleaseApk
   ```

3. **Run Release Lint Vital Checks:**
   ```bash
   ./gradlew lintVitalRelease
   ```

4. **Output APK Locations:**
   - Release APK: `app/build/outputs/apk/release/app-release.apk`
   - Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## 🔐 Release Signing with Environment Variables / GitHub Secrets

No release keystores (`*.jks`, `*.keystore`) or signing passwords are stored in this repository.

To sign a production release build using your own upload/release keystore, provide the following environment variables (or configure them as **GitHub Actions Secrets**):

- `KEYSTORE_PATH` (or `RELEASE_KEYSTORE_BASE64` in GitHub Actions)
- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

When these environment variables are not set (such as on pull requests or local development builds), the build automatically falls back to standard local verification signing so CI and contributors can build and test out of the box.

