# HOMEFLIX keeps release builds unminified for now (see app/build.gradle.kts), so these
# rules are only here to keep kotlinx.serialization and Media3 safe if minification is enabled.
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn kotlinx.serialization.**
-dontwarn org.jetbrains.annotations.**

-keepclassmembers class **$$serializer {
    *;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
