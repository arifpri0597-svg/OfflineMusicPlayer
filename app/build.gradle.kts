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
  compileSdk = 36

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

  splits {
    abi {
      isEnable = false
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
    dex {
      useLegacyPackaging = true
    }
    jniLibs {
      useLegacyPackaging = true
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/*.version"
      excludes += "/META-INF/**/LICENSE.txt"
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

abstract class NormalizeAndSignDebugApkTask : DefaultTask() {
  @get:Internal
  abstract val apkDirProperty: DirectoryProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val keystoreFileProperty: RegularFileProperty

  @get:Internal
  abstract val dotBuildOutputsApkProperty: RegularFileProperty

  @TaskAction
  fun normalizeAndSign() {
    val apkFile = apkDirProperty.get().file("app-debug.apk").asFile
    if (!apkFile.exists() || apkFile.length() == 0L) {
      return
    }
    val keystoreFile = keystoreFileProperty.get().asFile
    val sdkRoot =
        System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: "/opt/android/sdk"
    val buildToolsDir = File(sdkRoot, "build-tools").listFiles()?.sortedBy { it.name }?.lastOrNull()
    if (buildToolsDir != null) {
      val zipalign = File(buildToolsDir, "zipalign")
      val apksigner = File(buildToolsDir, "apksigner")
      if (zipalign.exists() && apksigner.exists() && keystoreFile.exists()) {
        val alignedTmp = File(apkFile.parentFile, "app-debug-aligned.tmp.apk")
        if (alignedTmp.exists()) alignedTmp.delete()
        val alignProc =
            ProcessBuilder(
                    zipalign.absolutePath,
                    "-f",
                    "-p",
                    "4",
                    apkFile.absolutePath,
                    alignedTmp.absolutePath)
                .redirectErrorStream(true)
                .start()
        val alignOut = alignProc.inputStream.bufferedReader().readText()
        val alignCode = alignProc.waitFor()
        if (alignCode != 0 || !alignedTmp.exists()) {
          throw GradleException("zipalign failed (exit=$alignCode):\n$alignOut")
        }
        alignedTmp.copyTo(apkFile, overwrite = true)
        alignedTmp.delete()

        val signProc =
            ProcessBuilder(
                    apksigner.absolutePath,
                    "sign",
                    "--min-sdk-version",
                    "21",
                    "--v1-signing-enabled",
                    "true",
                    "--v2-signing-enabled",
                    "true",
                    "--v3-signing-enabled",
                    "true",
                    "--v4-signing-enabled",
                    "false",
                    "--ks",
                    keystoreFile.absolutePath,
                    "--ks-pass",
                    "pass:android",
                    "--key-pass",
                    "pass:android",
                    "--ks-key-alias",
                    "androiddebugkey",
                    apkFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        val signOut = signProc.inputStream.bufferedReader().readText()
        val signCode = signProc.waitFor()
        if (signCode != 0) {
          throw GradleException("apksigner sign failed (exit=$signCode):\n$signOut")
        }
        val v4Idsig = File(apkFile.parentFile, "app-debug.apk.idsig")
        if (v4Idsig.exists()) v4Idsig.delete()
      }
    }
    val dotApk = dotBuildOutputsApkProperty.orNull?.asFile
    if (dotApk != null) {
      runCatching {
        dotApk.parentFile?.mkdirs()
        if (!dotApk.exists() || dotApk.canonicalPath != apkFile.canonicalPath) {
          apkFile.copyTo(dotApk, overwrite = true)
        }
      }
    }
  }
}

val normalizeAndSignDebugApk =
    tasks.register<NormalizeAndSignDebugApkTask>("normalizeAndSignDebugApk") {
      dependsOn(ensureDebugKeystore)
      apkDirProperty.set(layout.buildDirectory.dir("outputs/apk/debug"))
      keystoreFileProperty.set(rootProject.layout.projectDirectory.file("debug.keystore"))
      dotBuildOutputsApkProperty.set(
          rootProject.layout.projectDirectory.file(".build-outputs/app-debug.apk"))
    }

tasks
    .matching { it.name == "validateSigningDebug" || it.name == "packageDebug" }
    .configureEach { dependsOn(ensureDebugKeystore) }

tasks
    .matching { it.name == "packageDebug" || it.name == "createDebugApkListingFileRedirect" }
    .configureEach { finalizedBy(normalizeAndSignDebugApk) }

tasks
    .matching { it.name == "assembleDebug" }
    .configureEach { dependsOn(normalizeAndSignDebugApk) }

abstract class VerifyDebugApkTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val apkFileProperty: RegularFileProperty

  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val dotBuildOutputsApkProperty: RegularFileProperty

  @get:OutputFile
  abstract val reportFileProperty: RegularFileProperty

  @TaskAction
  fun verifyApk() {
    val sb = StringBuilder()
    fun log(msg: String) {
      println(msg)
      sb.appendLine(msg)
    }

    val apkFile = apkFileProperty.get().asFile
    if (!apkFile.exists() || apkFile.length() == 0L) {
      throw GradleException("APK not found or empty at ${apkFile.absolutePath}")
    }
    log("APK_VERIFIED_PATH=${apkFile.absolutePath} (${apkFile.length()} bytes)")

    val dotApk = dotBuildOutputsApkProperty.orNull?.asFile
    if (dotApk != null && dotApk.exists()) {
      log("DOT_BUILD_OUTPUTS_APK=${dotApk.absolutePath} (${dotApk.length()} bytes)")
      runCatching {
        ZipFile(dotApk).use { z ->
          log("DOT_BUILD_OUTPUTS_APK_ENTRIES=${z.size()}")
        }
      }.onFailure {
        log("DOT_BUILD_OUTPUTS_APK_CORRUPT=${it.message}")
      }
    }

    ZipFile(apkFile).use { zip ->
      val entries = zip.entries().asSequence().toList()
      val hasManifest = entries.any { it.name == "AndroidManifest.xml" }
      val hasDex = entries.any { it.name == "classes.dex" }
      val hasResources = entries.any { it.name == "resources.arsc" }
      if (!hasManifest || !hasDex || !hasResources) {
        throw GradleException("APK is missing core entries (AndroidManifest.xml, classes.dex, or resources.arsc)")
      }
      // Verify every single ZIP entry stream can be read without CRC or EOF corruption
      val readBuf = ByteArray(16384)
      for (entry in entries) {
        if (!entry.isDirectory) {
          zip.getInputStream(entry).use { inp ->
            while (inp.read(readBuf) != -1) {
              // consume stream to trigger ZipFile CRC verification
            }
          }
        }
      }
      val resEntry = entries.firstOrNull { it.name == "resources.arsc" }
      log("RESOURCES_ARSC: method=${resEntry?.method} (0=STORED,8=DEFLATED), size=${resEntry?.size}, compressedSize=${resEntry?.compressedSize}")
      val soEntries = entries.filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
      val soMethods = soEntries.map { it.method }.distinct()
      log("SO_ENTRIES: count=${soEntries.size}, methods=$soMethods, names=${soEntries.map { it.name }}")
      val metaInfEntries = entries.filter { it.name.startsWith("META-INF/") }.map { it.name }
      log("META_INF_ENTRIES=$metaInfEntries")
      val abis =
          soEntries
              .map { it.name.split("/")[1] }
              .distinct()
              .sorted()
      log("APK_VERIFIED_ENTRIES=${entries.size}, ABIS=$abis")
    }

    val sdkRoot =
        System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: "/opt/android/sdk"
    val buildToolsDir = File(sdkRoot, "build-tools").listFiles()?.sortedBy { it.name }?.lastOrNull()
    if (buildToolsDir != null) {
      log("USING_BUILD_TOOLS=${buildToolsDir.absolutePath}")
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
        log("APKSIGNER_MIN21_FULL (exit=$code):\n$out")
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
        log("AAPT2_BADGING_FULL (exit=$code):\n$out")
        if (code != 0) throw GradleException("aapt2 dump badging failed:\n$out")

        val xmlProc =
            ProcessBuilder(
                    aapt2.absolutePath,
                    "dump",
                    "xmltree",
                    apkFile.absolutePath,
                    "--file",
                    "AndroidManifest.xml")
                .redirectErrorStream(true)
                .start()
        val xmlOut = xmlProc.inputStream.bufferedReader().readText()
        val xmlCode = xmlProc.waitFor()
        log("AAPT2_XMLTREE [AndroidManifest.xml] (exit=$xmlCode):\n$xmlOut")
        if (xmlCode != 0) throw GradleException("aapt2 dump xmltree failed:\n$xmlOut")
      }
      val zipalign = File(buildToolsDir, "zipalign")
      if (zipalign.exists()) {
        val proc =
            ProcessBuilder(zipalign.absolutePath, "-c", "-v", "4", apkFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        val zOut = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        val badAlignLines = zOut.lines().filter { it.contains("BAD") }
        log("ZIPALIGN_CHECK_4 (exit=$code, badLines=${badAlignLines.size}): $badAlignLines")
        if (code != 0) throw GradleException("zipalign check failed")
      }
    }
    val reportFile = reportFileProperty.get().asFile
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(sb.toString())
  }
}

tasks.register<VerifyDebugApkTask>("verifyDebugApk") {
  dependsOn("assembleDebug", normalizeAndSignDebugApk)
  apkFileProperty.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
  val dotApkFile = rootProject.file(".build-outputs/app-debug.apk")
  if (dotApkFile.exists()) {
    dotBuildOutputsApkProperty.set(dotApkFile)
  }
  reportFileProperty.set(layout.buildDirectory.file("apk-inspection.txt"))
}


