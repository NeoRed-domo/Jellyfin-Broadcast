# Jellyfin Broadcast — ProGuard rules
# minifyEnabled=false means ProGuard is not active for this build,
# but the file is required by the Gradle configuration reference.

# Preserve Jellyfin SDK model classes (serialization)
-keep class org.jellyfin.sdk.model.** { *; }

# Preserve Ktor server classes
-keep class io.ktor.** { *; }

# Preserve ZXing classes
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
