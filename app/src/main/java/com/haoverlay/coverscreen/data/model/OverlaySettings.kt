package com.haoverlay.coverscreen.data.model

import com.google.gson.annotations.SerializedName

enum class DockPosition(val displayName: String) {
    TOP_LEFT("Top Left"),
    TOP_CENTER("Top Center"),
    TOP_RIGHT("Top Right"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_CENTER("Bottom Center"),
    BOTTOM_RIGHT("Bottom Right"),
    CENTER_LEFT("Center Left"),
    CENTER_RIGHT("Center Right"),
    CUSTOM("Custom Position")
}

enum class OverlayOrientation(val displayName: String) {
    HORIZONTAL("Horizontal Row"),
    VERTICAL("Vertical Column")
}

enum class IconSize(val dp: Int, val displayName: String) {
    COMPACT(36, "Compact (36dp)"),
    NORMAL(46, "Normal (46dp)"),
    LARGE(54, "Large (54dp)"),
    EXTRA_LARGE(62, "Extra Large (62dp)")
}

enum class BackgroundStyle(val displayName: String) {
    PILL_DARK("Dark Translucent Pill"),
    PILL_LIGHT("Light Translucent Pill"),
    MINIMAL("Subtle Card"),
    TRANSPARENT("Transparent (Icons Only)")
}

enum class TargetDisplayMode(val displayName: String) {
    AUTO_DETECT_COVER("Auto-Detect Samsung Cover Screen"),
    DEFAULT_DISPLAY("Main / Default Screen"),
    SPECIFIC_ID("Specific Display ID")
}

data class OverlaySettings(
    @SerializedName("dockPosition")
    val dockPosition: DockPosition = DockPosition.BOTTOM_RIGHT,

    @SerializedName("customX")
    val customX: Int = 16,

    @SerializedName("customY")
    val customY: Int = 16,

    @SerializedName("orientation")
    val orientation: OverlayOrientation = OverlayOrientation.HORIZONTAL,

    @SerializedName("iconSize")
    val iconSize: IconSize = IconSize.NORMAL,

    @SerializedName("iconSpacingDp")
    val iconSpacingDp: Int = 8,

    @SerializedName("backgroundStyle")
    val backgroundStyle: BackgroundStyle = BackgroundStyle.PILL_DARK,

    @SerializedName("backgroundOpacity")
    val backgroundOpacity: Float = 0.82f,

    @SerializedName("cornerRadiusDp")
    val cornerRadiusDp: Int = 24,

    @SerializedName("hapticFeedbackEnabled")
    val hapticFeedbackEnabled: Boolean = true,

    @SerializedName("debounceDelayMs")
    val debounceDelayMs: Long = 600L,

    @SerializedName("targetDisplayMode")
    val targetDisplayMode: TargetDisplayMode = TargetDisplayMode.AUTO_DETECT_COVER,

    @SerializedName("targetDisplayId")
    val targetDisplayId: Int = -1,

    @SerializedName("autoStartOnBoot")
    val autoStartOnBoot: Boolean = true,

    @SerializedName("isServiceEnabled")
    val isServiceEnabled: Boolean = true,

    @SerializedName("allowDragReposition")
    val allowDragReposition: Boolean = true,

    @SerializedName("showButtonLabels")
    val showButtonLabels: Boolean = true
)
