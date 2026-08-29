package com.haoverlay.coverscreen

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.haoverlay.coverscreen.data.model.OverlayButtonConfig
import org.junit.Assert.*
import org.junit.Test

class OverlayButtonConfigTest {

    @Test
    fun testDomainExtraction() {
        assertEquals("light", OverlayButtonConfig.extractDomain("light.living_room"))
        assertEquals("switch", OverlayButtonConfig.extractDomain("switch.heater"))
        assertEquals("cover", OverlayButtonConfig.extractDomain("cover.living_room_blind"))
        assertEquals("lock", OverlayButtonConfig.extractDomain("lock.front_door"))
        assertEquals("scene", OverlayButtonConfig.extractDomain("scene.good_night"))
        assertEquals("light", OverlayButtonConfig.extractDomain("invalid_no_dot"))
    }

    @Test
    fun testDefaultServiceForDomain() {
        assertEquals("toggle", OverlayButtonConfig.defaultServiceForDomain("light"))
        assertEquals("toggle", OverlayButtonConfig.defaultServiceForDomain("switch"))
        assertEquals("lock", OverlayButtonConfig.defaultServiceForDomain("lock"))
        assertEquals("press", OverlayButtonConfig.defaultServiceForDomain("button"))
        assertEquals("turn_on", OverlayButtonConfig.defaultServiceForDomain("scene"))
        assertEquals("trigger", OverlayButtonConfig.defaultServiceForDomain("automation"))
    }

    @Test
    fun testDefaultIconForDomain() {
        assertEquals("lightbulb", OverlayButtonConfig.defaultIconForDomain("light"))
        assertEquals("power", OverlayButtonConfig.defaultIconForDomain("switch"))
        assertEquals("lock", OverlayButtonConfig.defaultIconForDomain("lock"))
        assertEquals("blinds", OverlayButtonConfig.defaultIconForDomain("cover"))
        assertEquals("sparkles", OverlayButtonConfig.defaultIconForDomain("scene"))
        assertEquals("thermostat", OverlayButtonConfig.defaultIconForDomain("climate"))
    }

    @Test
    fun testListSerialization() {
        val gson = Gson()
        val buttons = listOf(
            OverlayButtonConfig(
                entityId = "light.kitchen",
                domain = "light",
                service = "toggle",
                label = "Kitchen Light",
                order = 0
            ),
            OverlayButtonConfig(
                entityId = "lock.front_door",
                domain = "lock",
                service = "unlock",
                label = "Front Door",
                customColorHex = "#10B981",
                order = 1
            )
        )

        val json = gson.toJson(buttons)
        val type = object : TypeToken<List<OverlayButtonConfig>>() {}.type
        val parsed: List<OverlayButtonConfig> = gson.fromJson(json, type)

        assertEquals(2, parsed.size)
        assertEquals("light.kitchen", parsed[0].entityId)
        assertEquals("Kitchen Light", parsed[0].label)
        assertEquals("#10B981", parsed[1].customColorHex)
    }
}
