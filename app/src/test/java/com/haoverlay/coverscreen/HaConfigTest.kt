package com.haoverlay.coverscreen

import com.google.gson.Gson
import com.haoverlay.coverscreen.data.model.HaConfig
import org.junit.Assert.*
import org.junit.Test

class HaConfigTest {

    @Test
    fun testCleanBaseUrlTrailingSlash() {
        val config = HaConfig(baseUrl = "http://192.168.1.50:8123/", accessToken = "test_token")
        assertEquals("http://192.168.1.50:8123", config.cleanBaseUrl)
    }

    @Test
    fun testCleanBaseUrlWhitespace() {
        val config = HaConfig(baseUrl = "  https://my-ha.nabu.casa/  ", accessToken = "test_token")
        assertEquals("https://my-ha.nabu.casa", config.cleanBaseUrl)
    }

    @Test
    fun testValidity() {
        val invalidUrl = HaConfig(baseUrl = "ftp://invalid.com", accessToken = "abc")
        assertFalse(invalidUrl.isValid)

        val missingToken = HaConfig(baseUrl = "http://192.168.1.1:8123", accessToken = "")
        assertFalse(missingToken.isValid)

        val validHttp = HaConfig(baseUrl = "http://192.168.1.1:8123", accessToken = "secret_token")
        assertTrue(validHttp.isValid)

        val validHttps = HaConfig(baseUrl = "https://nabu.casa", accessToken = "secret_token")
        assertTrue(validHttps.isValid)
    }

    @Test
    fun testJsonSerialization() {
        val gson = Gson()
        val original = HaConfig(
            baseUrl = "http://homeassistant.local:8123",
            accessToken = "my_token",
            useWebSocket = true,
            pollIntervalSeconds = 15
        )

        val json = gson.toJson(original)
        val deserialized = gson.fromJson(json, HaConfig::class.java)

        assertEquals(original.baseUrl, deserialized.baseUrl)
        assertEquals(original.accessToken, deserialized.accessToken)
        assertEquals(original.useWebSocket, deserialized.useWebSocket)
        assertEquals(original.pollIntervalSeconds, deserialized.pollIntervalSeconds)
    }
}
