# Frameport ProGuard / R8 rules
# R8 full mode is enabled via android.enableR8.fullMode=true in gradle.properties.
# These rules supplement the consumer rules supplied by each AAR dependency.

# --- Native bridge: keep JNI entry points and all members of the native bridge package ---
-keep class dev.po4yka.frameport.nativebridge.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Room: keep database subclasses and annotated entities / DAOs ---
# Room generates _Impl classes via KSP; R8 must not remove or rename them.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# --- kotlinx.serialization: required under R8 full mode ---
# Generated $$serializer companions are looked up by name at runtime.
# Without these rules the serializers are pruned and ImportPreferences / @Serializable
# navigation destinations throw SerializationException at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class dev.po4yka.frameport.**$$serializer { *; }
-keepclassmembers class dev.po4yka.frameport.** {
    *** Companion;
}
-keepclasseswithmembers class dev.po4yka.frameport.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable annotated classes scoped to this app (covers nav keys + ImportPreferences).
# Narrowed from 'class *' to 'dev.po4yka.frameport.**' to avoid retaining third-party
# @Serializable classes that ship their own consumer rules.
-keep @kotlinx.serialization.Serializable class dev.po4yka.frameport.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class dev.po4yka.frameport.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
    <fields>;
}

# --- Hilt: consumer rules from hilt-android AAR cover the generated components ---
# Do not duplicate Hilt keep rules here; they are shipped as R8 consumer rules in the AAR.

# --- Timber: strip verbose and debug log calls in release builds ---
# v() and d() are removed entirely by R8 (-assumenosideeffects treats them as side-effect-free).
# w(), e(), and wtf() are intentionally retained in release: they indicate warnings and errors
# that are useful for diagnosing production issues without exposing user-identifying data.
# i() is also retained so informational lifecycle events remain visible in release logs.
# This is the correct privacy-first posture: verbose/debug output is stripped, structured
# warning/error output is preserved for triage.
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
}

# --- Kotlin metadata: required by several AndroidX libraries ---
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }
