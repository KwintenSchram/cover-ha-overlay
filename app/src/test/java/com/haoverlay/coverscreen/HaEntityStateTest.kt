package com.haoverlay.coverscreen

import com.google.gson.Gson
import com.haoverlay.coverscreen.data.model.HaEntityState
import org.junit.Assert.*
import org.junit.Test

class HaEntityStateTest {

    private val gson = Gson()

    @Test
    fun testIsOnEvaluation() {
        val lightOn = HaEntityState(entityId = "light.bulb", state = "on")
        assertTrue(lightOn.isOn)

        val lightOff = HaEntityState(entityId = "light.bulb", state = "off")
        assertFalse(lightOff.isOn)

        val lockUnlocked = HaEntityState(entityId = "lock.front", state = "unlocked")
        assertTrue(lockUnlocked.isOn)

        val coverOpen = HaEntityState(entityId = "cover.garage", state = "open")
        assertTrue(coverOpen.isOn)
    }

    @Test
    fun testUnavailableEvaluation() {
        val unavail = HaEntityState(entityId = "sensor.temp", state = "unavailable")
        assertTrue(unavail.isUnavailable)

        val unknown = HaEntityState(entityId = "sensor.temp", state = "unknown")
        assertTrue(unknown.isUnavailable)

        val normal = HaEntityState(entityId = "light.living_room", state = "on")
        assertFalse(normal.isUnavailable)
    }

    @Test
    fun testFriendlyNameFallback() {
        val jsonWithFriendly = """
            {
                "entity_id": "light.desk",
                "state": "on",
                "attributes": {
                    "friendly_name": "Studio Desk Lamp",
                    "brightness": 200
                }
            }
        """.trimIndent()

        val parsed = gson.fromJson(jsonWithFriendly, HaEntityState::class.java)
        assertEquals("Studio Desk Lamp", parsed.friendlyName)
        assertEquals(200.0, parsed.attributes["brightness"])

        val stateWithoutFriendly = HaEntityState(entityId = "switch.ac", state = "off")
        assertEquals("switch.ac", stateWithoutFriendly.friendlyName)
    }
}
