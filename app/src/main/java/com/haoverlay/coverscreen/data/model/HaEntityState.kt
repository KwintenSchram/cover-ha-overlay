package com.haoverlay.coverscreen.data.model

import com.google.gson.annotations.SerializedName

/**
 * Model representing an entity's current state in Home Assistant.
 */
data class HaEntityState(
    @SerializedName("entity_id")
    val entityId: String = "",

    @SerializedName("state")
    val state: String = "",

    @SerializedName("attributes")
    val attributes: Map<String, Any?> = emptyMap(),

    @SerializedName("last_changed")
    val lastChanged: String? = null,

    @SerializedName("last_updated")
    val lastUpdated: String? = null
) {
    val friendlyName: String
        get() = attributes["friendly_name"] as? String ?: entityId

    val isOn: Boolean
        get() = state.equals("on", ignoreCase = true) ||
                state.equals("open", ignoreCase = true) ||
                state.equals("unlocked", ignoreCase = true) ||
                state.equals("playing", ignoreCase = true) ||
                state.equals("active", ignoreCase = true) ||
                state.equals("home", ignoreCase = true)

    val isUnavailable: Boolean
        get() = state.equals("unavailable", ignoreCase = true) ||
                state.equals("unknown", ignoreCase = true)
}
