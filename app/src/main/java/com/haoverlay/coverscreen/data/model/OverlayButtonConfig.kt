package com.haoverlay.coverscreen.data.model

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * Configuration for a single quick-action button rendered in the overlay.
 */
data class OverlayButtonConfig(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("entityId")
    val entityId: String = "",

    @SerializedName("domain")
    val domain: String = extractDomain(entityId),

    @SerializedName("service")
    val service: String = defaultServiceForDomain(extractDomain(entityId)),

    @SerializedName("serviceDataJson")
    val serviceDataJson: String? = null,

    @SerializedName("iconName")
    val iconName: String = defaultIconForDomain(extractDomain(entityId)),

    @SerializedName("label")
    val label: String = "",

    @SerializedName("customColorHex")
    val customColorHex: String? = null,

    @SerializedName("order")
    val order: Int = 0,

    @SerializedName("showState")
    val showState: Boolean = true,

    @SerializedName("guardSensorEntityId")
    val guardSensorEntityId: String? = null,

    @SerializedName("guardTriggerState")
    val guardTriggerState: String = "on",

    @SerializedName("guardConfirmationWindowMs")
    val guardConfirmationWindowMs: Long = 10000L,

    @SerializedName("requireConfirmationWhenLocked")
    val requireConfirmationWhenLocked: Boolean = false,

    @SerializedName("targetLockEntityId")
    val targetLockEntityId: String? = null
) {
    companion object {
        fun extractDomain(entityId: String): String {
            return if (entityId.contains(".")) {
                entityId.substringBefore(".")
            } else {
                "light"
            }
        }

        fun defaultServiceForDomain(domain: String): String {
            return when (domain.lowercase()) {
                "light", "switch", "input_boolean", "fan", "siren" -> "toggle"
                "cover" -> "toggle"
                "lock" -> "lock"
                "button", "input_button" -> "press"
                "scene", "script" -> "turn_on"
                "automation" -> "trigger"
                "media_player" -> "media_play_pause"
                "climate" -> "set_hvac_mode"
                else -> "toggle"
            }
        }

        fun defaultIconForDomain(domain: String): String {
            return when (domain.lowercase()) {
                "light" -> "lightbulb"
                "switch", "input_boolean" -> "power"
                "cover" -> "blinds"
                "lock" -> "lock"
                "button", "input_button" -> "touch"
                "scene" -> "sparkles"
                "script" -> "code"
                "automation" -> "bolt"
                "media_player" -> "music"
                "climate" -> "thermostat"
                "fan" -> "fan"
                "camera" -> "camera"
                "vacuum" -> "vacuum"
                "garage" -> "garage"
                else -> "power"
            }
        }
    }
}
