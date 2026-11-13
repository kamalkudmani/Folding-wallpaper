# kotlinx.serialization ships its own consumer rules. Keep the serializable pack model intact
# so manifests written by one build stay readable by the next.
-keep,includedescriptorclasses class com.foldmotion.core.**$$serializer { *; }
-keepclassmembers class com.foldmotion.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.foldmotion.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The wallpaper service is referenced from the manifest and from the apply intent.
-keep class com.foldmotion.wallpapers.engine.FoldWallpaperService { *; }
