# R8 / ProGuard rules for Cover HA Overlay.
#
# Release builds now run with `isMinifyEnabled = true`, so everything reached only through
# reflection (i.e. every Gson-serialised model) has to be kept explicitly.

# --- Reflection metadata Gson depends on ---------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# --- Config / wire models --------------------------------------------------------------------
# HaConfig, HaEntityState, OverlayButtonConfig, OverlaySettings, DisplayInfo and the enums
# (DockPosition, IconSize, BackgroundStyle, OverlayOrientation, TargetDisplayMode) are all
# read back from persisted JSON, so their field and constant names must survive.
-keep class com.haoverlay.coverscreen.data.model.** { *; }

# Kotlin enums are matched by name during Gson deserialisation.
-keepclassmembers enum com.haoverlay.coverscreen.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Any field explicitly named for the wire format must keep that name.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Gson internals ---------------------------------------------------------------------------
# Anonymous TypeToken subclasses carry their generic type in the class signature; without this
# R8 erases it and List<OverlayButtonConfig> deserialises as List<LinkedTreeMap>.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# --- OkHttp / Okio -----------------------------------------------------------------------------
-dontwarn okio.**
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Views instantiated from code with a non-default constructor -------------------------------
-keep class com.haoverlay.coverscreen.service.CoverOverlayView { *; }
