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

# Keep all @Serializable annotated classes (covers nav keys + ImportPreferences)
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
    <fields>;
}

# --- Hilt: consumer rules from hilt-android AAR cover the generated components ---
# Do not duplicate Hilt keep rules here; they are shipped as R8 consumer rules in the AAR.

# --- Timber: strip debug logging in release builds ---
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
}

# --- Kotlin metadata: required by several AndroidX libraries ---
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }
