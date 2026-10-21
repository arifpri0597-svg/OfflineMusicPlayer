# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Room database entities, DAOs, and projection models
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class com.example.data.local.** { *; }
-keepclassmembers class com.example.data.model.** { *; }

# Keep Media3 PlaybackService and ExoPlayer constructors
-keep class com.example.player.service.PlaybackService { *; }
-dontwarn androidx.media3.**

