# Add project specific ProGuard rules here.
-keepattributes *Annotation*

# --- Media3 / ExoPlayer ---
# Renderers, decoders and the session are discovered reflectively; R8 must not
# strip or rename them, or playback fails only in the minified release build.
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- Room ---
# Entities and DAOs are resolved by generated code; keep their members.
-keep class com.cinemax.nexus.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# --- Keep line numbers so release crash logs stay readable ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**

# Room looks up generated database implementations by name and calls their public no-arg constructor.
-keep class com.cinemax.nexus.study.StudyDatabase_Impl { public <init>(); }
-keep class com.cinemax.nexus.data.db.AppDatabase_Impl { public <init>(); }
