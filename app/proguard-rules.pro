# Keep OkHttp & Gson models
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.haoverlay.coverscreen.data.model.** { *; }
-dontwarn okio.**
-dontwarn okhttp3.**
