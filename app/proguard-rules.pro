# R8 / release shrinker rules for Arisu Companion.
#
# Filament ships its own consumer rules inside its AARs (classes touched from
# JNI are annotated @UsedBy* there), so nothing is needed for the renderer.
#
# Obfuscation is off on purpose: the app is not obfuscation-sensitive (the
# only reflection targets framework classes, never app classes), and keeping
# original names makes the release APK statically verifiable without a device.
-dontobfuscate

# ---------------------------------------------------------------- serialization
# kotlinx.serialization looks serializers up through generated code and the
# Companion object; R8 can otherwise strip or rename what the compiler emits
# for @Serializable types stored in SharedPreferences.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.arisu.companion.** {
    *** Companion;
    *** serializer(...);
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.arisu.companion.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation @kotlinx.serialization.Serializable class com.arisu.companion.**

# ------------------------------------------------------------------------ Room
# Room resolves its generated implementation by class name.
-keep class com.arisu.companion.database.** { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ------------------------------------------------------------- services/overlay
# Instantiated by the framework from the manifest; keep names readable in
# crash reports and keep the manifest-declared entry points intact.
-keepnames class com.arisu.companion.MainActivity
-keepnames class com.arisu.companion.ArisuApplication
-keepnames class com.arisu.companion.overlay.FloatingPetService
-keepnames class com.arisu.companion.overlay.BootCompletedReceiver
-keepclassmembers class com.arisu.companion.overlay.BootCompletedReceiver {
    public void onReceive(android.content.Context, android.content.Intent);
}
