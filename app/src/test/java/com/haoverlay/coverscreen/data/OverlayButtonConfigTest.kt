package com.haoverlay.coverscreen.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.haoverlay.coverscreen.data.model.OverlayButtonConfig
import org.junit.Test

class OverlayButtonConfigTest {

    private val gson = Gson()

    @Test
    fun `extractDomain correctly extracts domain from entityId`() {
        assertThat(OverlayButtonConfig.extractDomain("light.kitchen")).isEqualTo("light")
        assertThat(OverlayButtonConfig.extractDomain("switch.porch_light")).isEqualTo("switch")
        assertThat(OverlayButtonConfig.extractDomain("lock.front_door")).isEqualTo("lock")
        assertThat(OverlayButtonConfig.extractDomain("binary_sensor.door")).isEqualTo("binary_sensor")
        assertThat(OverlayButtonConfig.extractDomain("invalid_entity")).isEqualTo("light")
    }

    @Test
    fun `defaultServiceForDomain returns correct service`() {
        assertThat(OverlayButtonConfig.defaultServiceForDomain("light")).isEqualTo("toggle")
        assertThat(OverlayButtonConfig.defaultServiceForDomain("switch")).isEqualTo("toggle")
        assertThat(OverlayButtonConfig.defaultServiceForDomain("lock")).isEqualTo("lock")
        assertThat(OverlayButtonConfig.defaultServiceForDomain("button")).isEqualTo("press")
        assertThat(OverlayButtonConfig.defaultServiceForDomain("script")).isEqualTo("turn_on")
        assertThat(OverlayButtonConfig.defaultServiceForDomain("scene")).isEqualTo("turn_on")
        assertThat(OverlayButtonConfig.defaultServiceForDomain("automation")).isEqualTo("trigger")
        assertThat(OverlayButtonConfig.defaultServiceForDomain("media_player")).isEqualTo("media_play_pause")
    }

    @Test
    fun `defaultIconForDomain returns expected icon names`() {
        assertThat(OverlayButtonConfig.defaultIconForDomain("light")).isEqualTo("lightbulb")
        assertThat(OverlayButtonConfig.defaultIconForDomain("switch")).isEqualTo("power")
        assertThat(OverlayButtonConfig.defaultIconForDomain("lock")).isEqualTo("lock")
        assertThat(OverlayButtonConfig.defaultIconForDomain("cover")).isEqualTo("blinds")
        assertThat(OverlayButtonConfig.defaultIconForDomain("fan")).isEqualTo("fan")
        assertThat(OverlayButtonConfig.defaultIconForDomain("climate")).isEqualTo("thermostat")
    }

    @Test
    fun `serialization and deserialization preserves guard and confirmation properties`() {
        val original = OverlayButtonConfig(
            entityId = "switch.door_remote",
            domain = "switch",
            service = "toggle",
            iconName = "door",
            label = "Door",
            customColorHex = "#3B82F6",
            order = 1,
            showState = true,
            guardSensorEntityId = "binary_sensor.hallway_light",
            guardTriggerState = "on",
            guardConfirmationWindowMs = 10000L,
            requireConfirmationWhenLocked = true,
            targetLockEntityId = "lock.front_door"
        )

        val json = gson.toJson(original)
        val deserialized = gson.fromJson(json, OverlayButtonConfig::class.java)

        assertThat(deserialized.entityId).isEqualTo("switch.door_remote")
        assertThat(deserialized.guardSensorEntityId).isEqualTo("binary_sensor.hallway_light")
        assertThat(deserialized.guardTriggerState).isEqualTo("on")
        assertThat(deserialized.guardConfirmationWindowMs).isEqualTo(10000L)
        assertThat(deserialized.requireConfirmationWhenLocked).isTrue()
        assertThat(deserialized.targetLockEntityId).isEqualTo("lock.front_door")
    }
}
