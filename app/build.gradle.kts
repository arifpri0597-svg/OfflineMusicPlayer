import java.util.Base64
import java.util.zip.ZipFile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.pulsemusic.kmply"
    minSdk = 24
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
      signingConfigs.getByName("debugConfig").apply {
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
        enableV4Signing = false
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

composeCompiler {
  stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

// Offline music player dependencies: Core Jetpack Compose, Media3, Room, Coil, DataStore
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.datastore.preferences)

  // Media3 ExoPlayer & MediaSession
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.common)

  // Local persistence: Room
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  "ksp"(libs.androidx.room.compiler)

  // Image loading for album art
  implementation(libs.coil.compose)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  // debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

androidComponents {
  onVariants { variant ->
    variant.signingConfig.apply {
      enableV1Signing.set(true)
      enableV2Signing.set(true)
      enableV3Signing.set(true)
      enableV4Signing.set(false)
    }
  }
}

abstract class EnsureDebugKeystoreTask : DefaultTask() {
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val base64KeystoreFile: ConfigurableFileCollection

  @get:OutputFile
  abstract val debugKeystoreFile: RegularFileProperty

  @TaskAction
  fun ensureKeystore() {
    val targetFile = debugKeystoreFile.get().asFile
    if (targetFile.exists() && targetFile.length() > 0L) {
      return
    }
    targetFile.parentFile?.mkdirs()

    val base64File = base64KeystoreFile.files.firstOrNull { it.exists() && it.length() > 0L }
    if (base64File != null) {
      val cleanBase64 = base64File.readText().replace("\\s".toRegex(), "")
      targetFile.writeBytes(Base64.getDecoder().decode(cleanBase64))
      return
    }

    val userAndroidDebugKeystore =
        File(System.getProperty("user.home"), ".android/debug.keystore")
    if (userAndroidDebugKeystore.exists() && userAndroidDebugKeystore.length() > 0L) {
      userAndroidDebugKeystore.copyTo(targetFile, overwrite = true)
      return
    }

    val javaHome = System.getProperty("java.home")
    val keytoolFile = File(javaHome, "bin/keytool")
    val keytoolBin = if (keytoolFile.exists()) keytoolFile.absolutePath else "keytool"
    val process =
        ProcessBuilder(
                keytoolBin,
                "-genkeypair",
                "-v",
                "-keystore",
                targetFile.absolutePath,
                "-storepass",
                "android",
                "-alias",
                "androiddebugkey",
                "-keypass",
                "android",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-startdate",
                "2020/01/01 00:00:00",
                "-validity",
                "10000",
                "-dname",
                "CN=Android Debug,O=Android,C=US")
            .redirectErrorStream(true)
            .start()
    val exitCode = process.waitFor()
    if (exitCode != 0 || !targetFile.exists()) {
      throw GradleException(
          "Failed to generate standard Android debug.keystore (exit code $exitCode)")
    }
  }
}

val ensureDebugKeystore =
    tasks.register<EnsureDebugKeystoreTask>("ensureDebugKeystore") {
      base64KeystoreFile.from(rootProject.file("debug.keystore.base64"))
      debugKeystoreFile.set(rootProject.layout.projectDirectory.file("debug.keystore"))
      outputs.upToDateWhen {
        val file = debugKeystoreFile.get().asFile
        file.exists() && file.length() > 0L
      }
    }

tasks
    .matching { it.name == "validateSigningDebug" || it.name == "packageDebug" }
    .configureEach { dependsOn(ensureDebugKeystore) }

abstract class VerifyDebugApkTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val apkFileProperty: RegularFileProperty

  @TaskAction
  fun verifyApk() {
    val apkFile = apkFileProperty.get().asFile
    if (!apkFile.exists() || apkFile.length() == 0L) {
      throw GradleException("APK not found or empty at ${apkFile.absolutePath}")
    }
    println("APK_VERIFIED_PATH=${apkFile.absolutePath} (${apkFile.length()} bytes)")

    ZipFile(apkFile).use { zip ->
      val entries = zip.entries().asSequence().toList()
      val hasManifest = entries.any { it.name == "AndroidManifest.xml" }
      val hasDex = entries.any { it.name == "classes.dex" }
      val hasResources = entries.any { it.name == "resources.arsc" }
      if (!hasManifest || !hasDex || !hasResources) {
        throw GradleException("APK is missing core entries (AndroidManifest.xml, classes.dex, or resources.arsc)")
      }
      val abis =
          entries
              .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
              .map { it.name.split("/")[1] }
              .distinct()
              .sorted()
      println("APK_VERIFIED_ENTRIES=${entries.size}, ABIS=$abis")
    }

    val sdkRoot =
        System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: "/opt/android/sdk"
    val buildToolsDir = File(sdkRoot, "build-tools").listFiles()?.sortedBy { it.name }?.lastOrNull()
    if (buildToolsDir != null) {
      val apksigner = File(buildToolsDir, "apksigner")
      if (apksigner.exists()) {
        val proc =
            ProcessBuilder(
                    apksigner.absolutePath,
                    "verify",
                    "--min-sdk-version",
                    "21",
                    "--verbose",
                    "--print-certs",
                    apkFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        val summary = out.lines().take(18).joinToString(" | ")
        println("APKSIGNER_SUMMARY (exit=$code): $summary")
        if (code != 0) throw GradleException("apksigner verify failed:\n$out")
      }
      val aapt2 = File(buildToolsDir, "aapt2")
      if (aapt2.exists()) {
        val proc =
            ProcessBuilder(aapt2.absolutePath, "dump", "badging", apkFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        val summary = out.lines().take(5).joinToString(" | ")
        println("AAPT2_BADGING_SUMMARY (exit=$code): $summary")
        if (code != 0) throw GradleException("aapt2 dump badging failed:\n$out")
      }
      val zipalign = File(buildToolsDir, "zipalign")
      if (zipalign.exists()) {
        val proc =
            ProcessBuilder(zipalign.absolutePath, "-c", "4", apkFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        val code = proc.waitFor()
        println("ZIPALIGN_CHECK (exit=$code)")
        if (code != 0) throw GradleException("zipalign check failed")
      }
    }
  }
}

tasks.register<VerifyDebugApkTask>("verifyDebugApk") {
  dependsOn("assembleDebug")
  apkFileProperty.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
}

