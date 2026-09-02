package com.haoverlay.coverscreen.data.model

/**
 * Diagnostic information about a detected physical/virtual display.
 */
data class DisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val flags: Int,
    val state: Int,
    val isCoverScreen: Boolean,
    val detectionReason: String
) {
    val resolutionText: String
        get() = "${width}×${height} (${densityDpi}dpi)"

    val stateText: String
        get() = when (state) {
            1 -> "STATE_OFF"
            2 -> "STATE_ON"
            3 -> "STATE_DOZE"
            4 -> "STATE_DOZE_SUSPEND"
            5 -> "STATE_VR"
            6 -> "STATE_ON_SUSPEND"
            else -> "UNKNOWN ($state)"
        }
}
