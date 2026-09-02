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

    @Test
    fun cleartextToPrivateHostsIsAllowed() {
        val privateHosts = listOf(
            "http://192.168.1.50:8123",
            "http://10.0.0.4:8123",
            "http://172.16.5.9:8123",
            "http://172.31.255.1:8123",
            "http://127.0.0.1:8123",
            "http://localhost:8123",
            "http://homeassistant.local:8123",
            "http://ha.lan:8123"
        )
        for (url in privateHosts) {
            val config = HaConfig(baseUrl = url, accessToken = "t")
            assertFalse("expected private: $url", config.isCleartextToPublicHost)
        }
    }

    @Test
    fun cleartextToPublicHostIsFlagged() {
        val publicHosts = listOf(
            "http://home.example.com:8123",
            "http://8.8.8.8:8123",
            "http://172.32.0.1:8123",
            "http://192.169.1.1:8123"
        )
        for (url in publicHosts) {
            val config = HaConfig(baseUrl = url, accessToken = "t")
            assertTrue("expected public: $url", config.isCleartextToPublicHost)
        }
    }

    @Test
    fun httpsIsNeverFlaggedAsCleartext() {
        val config = HaConfig(baseUrl = "https://xyz.ui.nabu.casa", accessToken = "t")
        assertFalse(config.isCleartextToPublicHost)
    }

    @Test
    fun hostnameStartingLikeIpv6PrefixIsNotTreatedAsPrivate() {
        // "fc"/"fd" prefixes only mean unique-local when the host is an IPv6 literal.
        val config = HaConfig(baseUrl = "http://fcbarcelona.com", accessToken = "t")
        assertTrue(config.isCleartextToPublicHost)
    }
}
