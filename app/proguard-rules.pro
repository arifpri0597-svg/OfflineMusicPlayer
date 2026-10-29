# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Room database, entities, DAOs, generated implementations, and query models
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.example.data.local.** { *; }
-keep class com.example.data.model.** { *; }

# Keep Media3 PlaybackService, MediaSessionService, and ExoPlayer reflection entrypoints
-keep class com.example.player.service.PlaybackService { *; }
-keep class com.example.player.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep ViewModel and UI state models safe from aggressive field stripping
-keep class com.example.viewmodel.** { *; }

# Keep Kotlin Coroutines ServiceLoader entrypoints (MainDispatcherFactory / CoroutineExceptionHandler)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Keep Kotlin metadata and coroutines for Room / DataStore / Media3 interoperability
-keep class kotlin.Metadata { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-dontwarn kotlinx.coroutines.**
