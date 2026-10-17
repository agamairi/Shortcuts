# Room uses generated implementations discovered at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.** { *; }

# Glance AppWidget receivers and their generated support classes are manifest/runtime entry points.
-keep class androidx.glance.** { *; }

# Actions are serialized in Room through Gson.
-keep class com.shortcuts.app.data.Action { *; }
-keep class com.shortcuts.app.data.ActionType { *; }
-keepattributes Signature,*Annotation*

# Engine v2 workflows (Automation.actionsJson) and the in-progress capture cache are serialized
# with Gson by field and enum-constant name. Renaming them would make release builds write
# unreadable JSON and fail to decode shortcuts saved by any other build.
-keep class com.shortcuts.app.automation.model.** { *; }
-keep class com.shortcuts.app.automation.core.tree.** { *; }
-keep class com.shortcuts.app.automation.capture.** { *; }
-keep class com.shortcuts.app.automation.android.capture.** { *; }
-keep class com.shortcuts.app.automation.diagnostics.** { *; }
-keep class com.shortcuts.app.automation.replay.ReplayFailure { *; }
-keep class com.shortcuts.app.service.PersistedRecorderSession { *; }
-keepclassmembers enum com.shortcuts.app.** { *; }

# MediaPipe's task runtime loads native-backed classes by name.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Kotlin serialization can load generated serializers reflectively when present.
-keepclassmembers class **$Companion { kotlinx.serialization.KSerializer serializer(...); }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# Strip debug, verbose, and info logs from release builds to prevent leaking sensitive user content
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
