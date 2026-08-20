# Room uses generated implementations discovered at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.** { *; }

# Glance AppWidget receivers and their generated support classes are manifest/runtime entry points.
-keep class androidx.glance.** { *; }

# Actions are serialized in Room through Gson.
-keep class com.shortcuts.app.data.Action { *; }
-keep class com.shortcuts.app.data.ActionType { *; }
-keepattributes Signature,*Annotation*

# MediaPipe's task runtime loads native-backed classes by name.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Kotlin serialization can load generated serializers reflectively when present.
-keepclassmembers class **$Companion { kotlinx.serialization.KSerializer serializer(...); }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
