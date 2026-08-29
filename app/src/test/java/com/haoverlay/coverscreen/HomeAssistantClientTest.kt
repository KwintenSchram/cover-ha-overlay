package com.haoverlay.coverscreen

import com.haoverlay.coverscreen.data.model.HaConfig
import com.haoverlay.coverscreen.network.HaResult
import com.haoverlay.coverscreen.network.HomeAssistantClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HomeAssistantClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: HomeAssistantClient
    private lateinit var config: HaConfig

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        val baseUrl = mockServer.url("").toString().removeSuffix("/")
        config = HaConfig(
            baseUrl = baseUrl,
            accessToken = "mock_secret_token"
        )
        val okHttpClient = OkHttpClient.Builder().build()
        client = HomeAssistantClient(config, okHttpClient)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun testSuccessfulConnection() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"message": "API running.", "version": "2024.12.0"}""")
        )

        val result = client.testConnection()
        assertTrue(result.isSuccess)
        assertEquals("API running.", result.message)
        assertEquals("2024.12.0", result.serverVersion)

        val recordedRequest = mockServer.takeRequest()
        assertEquals("/api/", recordedRequest.path)
        assertEquals("Bearer mock_secret_token", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun testUnauthorizedConnection() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"message": "401: Unauthorized"}""")
        )

        val result = client.testConnection()
        assertFalse(result.isSuccess)
        assertTrue(result.message.contains("401 Unauthorized"))
    }

    @Test
    fun testCallService() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    [
                        {
                            "entity_id": "light.living_room",
                            "state": "on",
                            "attributes": {"friendly_name": "Living Room Light"}
                        }
                    ]
                """.trimIndent())
        )

        val result = client.callService(
            domain = "light",
            service = "toggle",
            entityId = "light.living_room",
            serviceDataJson = """{"brightness": 180}"""
        )

        assertTrue(result is HaResult.Success)
        val states = (result as HaResult.Success).data
        assertEquals(1, states.size)
        assertEquals("light.living_room", states[0].entityId)
        assertEquals("on", states[0].state)

        val request = mockServer.takeRequest()
        assertEquals("/api/services/light/toggle", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("light.living_room"))
        assertTrue(body.contains("180"))
    }

    @Test
    fun testFetchEntityState() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"entity_id": "switch.coffee_maker", "state": "off"}""")
        )

        val result = client.fetchEntityState("switch.coffee_maker")
        assertTrue(result is HaResult.Success)
        val state = (result as HaResult.Success).data
        assertEquals("switch.coffee_maker", state.entityId)
        assertEquals("off", state.state)

        val request = mockServer.takeRequest()
        assertEquals("/api/states/switch.coffee_maker", request.path)
    }
}
