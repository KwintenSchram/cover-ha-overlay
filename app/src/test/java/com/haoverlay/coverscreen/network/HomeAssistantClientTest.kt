package com.haoverlay.coverscreen.network

import com.google.common.truth.Truth.assertThat
import com.haoverlay.coverscreen.data.model.HaConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class HomeAssistantClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: HomeAssistantClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val config = HaConfig(
            baseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            accessToken = "test-token-12345",
            useWebSocket = false
        )
        client = HomeAssistantClient(config)
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `testConnection returns success on 200 OK`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"message": "API running."}""")
        )

        val result = client.testConnection()
        assertThat(result.isSuccess).isTrue()
        assertThat(result.message).contains("API running")

        val request = mockWebServer.takeRequest()
        assertThat(request.path).isEqualTo("/api/")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token-12345")
    }

    @Test
    fun `testConnection returns error on 401 Unauthorized`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"message": "401: Unauthorized"}""")
        )

        val result = client.testConnection()
        assertThat(result.isSuccess).isFalse()
        assertThat(result.message).contains("401")
    }

    @Test
    fun `callService sends correct POST payload and headers`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[{"entity_id": "light.living_room", "state": "on"}]""")
        )

        val result = client.callService(
            domain = "light",
            service = "toggle",
            entityId = "light.living_room"
        )

        assertThat(result).isInstanceOf(HaResult.Success::class.java)
        val success = result as HaResult.Success
        assertThat(success.data).hasSize(1)
        assertThat(success.data[0].entityId).isEqualTo("light.living_room")
        assertThat(success.data[0].state).isEqualTo("on")

        val request = mockWebServer.takeRequest()
        assertThat(request.path).isEqualTo("/api/services/light/toggle")
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.body.readUtf8()).contains("\"entity_id\":\"light.living_room\"")
    }

    @Test
    fun `fetchEntities returns parsed entity list`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    [
                        {"entity_id": "light.living_room", "state": "on", "attributes": {"friendly_name": "Living Room Light"}},
                        {"entity_id": "lock.front_door", "state": "locked", "attributes": {"friendly_name": "Front Door Lock"}}
                    ]
                """.trimIndent())
        )

        val result = client.fetchEntities()
        assertThat(result).isInstanceOf(HaResult.Success::class.java)
        val entities = (result as HaResult.Success).data
        assertThat(entities).hasSize(2)
        assertThat(entities[0].entityId).isEqualTo("light.living_room")
        assertThat(entities[0].friendlyName).isEqualTo("Living Room Light")
        assertThat(entities[1].entityId).isEqualTo("lock.front_door")
        assertThat(entities[1].state).isEqualTo("locked")
    }

    @Test
    fun `fetchEntityState returns single entity state`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"entity_id": "binary_sensor.door", "state": "off", "attributes": {"friendly_name": "Door Sensor"}}""")
        )

        val result = client.fetchEntityState("binary_sensor.door")
        assertThat(result).isInstanceOf(HaResult.Success::class.java)
        val entity = (result as HaResult.Success).data
        assertThat(entity.entityId).isEqualTo("binary_sensor.door")
        assertThat(entity.state).isEqualTo("off")
    }

    @Test
    fun `unconfigured client returns error immediately`() = runBlocking {
        val unconfiguredClient = HomeAssistantClient(HaConfig(baseUrl = "", accessToken = ""))
        val result = unconfiguredClient.testConnection()
        assertThat(result.isSuccess).isFalse()
        assertThat(result.message).contains("Invalid URL")
    }
}
