package com.haoverlay.coverscreen.network

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.haoverlay.coverscreen.data.model.HaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Protocol-level tests for the realtime client.
 *
 * The subscription shape is a battery concern, not a cosmetic one: an unfiltered
 * `subscribe_events` for `state_changed` asks Home Assistant to push every state change in the
 * entire instance to the phone.
 */
class HomeAssistantWebSocketTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    /** Messages the client sent to the server. */
    private val received = LinkedBlockingQueue<String>()

    private var serverSocket: WebSocket? = null

    @Before
    fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = MockWebServer()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"auth_required","ha_version":"2026.9.0"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received.put(text)
                val type = JsonParser.parseString(text).asJsonObject.get("type")?.asString
                if (type == "auth") {
                    webSocket.send("""{"type":"auth_ok","ha_version":"2026.9.0"}""")
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
    }

    @After
    fun teardown() {
        try { server.shutdown() } catch (e: Exception) { /* already down */ }
        scope.cancel()
    }

    private fun config() = HaConfig(
        baseUrl = server.url("/").toString().removeSuffix("/"),
        accessToken = "test-token",
        useWebSocket = true
    )

    private fun nextMessage(): String? = received.poll(5, TimeUnit.SECONDS)

    // --- lifecycle ---------------------------------------------------------------------

    @Test
    fun `start does not open a connection on its own`() {
        val client = HomeAssistantWebSocket(config(), scope)
        client.updateTrackedEntities(listOf("light.a"))

        client.start()
        Thread.sleep(500)

        // The watchdog re-sends ACTION_START every 15 minutes. If start() dialled, that alone
        // would hold a socket open around the clock regardless of the cover screen.
        assertThat(server.requestCount).isEqualTo(0)
        client.stop()
    }

    @Test
    fun `resume opens a connection`() {
        val client = HomeAssistantWebSocket(config(), scope)
        client.updateTrackedEntities(listOf("light.a"))

        client.resume()

        assertThat(nextMessage()).isNotNull()   // the auth frame
        assertThat(server.requestCount).isEqualTo(1)
        client.stop()
    }

    // --- subscription shape ------------------------------------------------------------

    @Test
    fun `subscribes only to the tracked entities`() {
        val client = HomeAssistantWebSocket(config(), scope)
        client.updateTrackedEntities(listOf("lock.front", "switch.door", "binary_sensor.hall"))
        client.resume()

        val auth = JsonParser.parseString(nextMessage()).asJsonObject
        assertThat(auth.get("type").asString).isEqualTo("auth")

        val sub = JsonParser.parseString(nextMessage()).asJsonObject
        assertThat(sub.get("type").asString).isEqualTo("subscribe_trigger")

        val trigger = sub.getAsJsonObject("trigger")
        assertThat(trigger.get("platform").asString).isEqualTo("state")

        val entities = trigger.getAsJsonArray("entity_id").map { it.asString }
        assertThat(entities).containsExactly("binary_sensor.hall", "lock.front", "switch.door")

        client.stop()
    }

    @Test
    fun `never sends an unfiltered state_changed subscription up front`() {
        val client = HomeAssistantWebSocket(config(), scope)
        client.updateTrackedEntities(listOf("lock.front"))
        client.resume()

        nextMessage()                              // auth
        val sub = nextMessage().orEmpty()

        assertThat(sub).doesNotContain("subscribe_events")
        assertThat(sub).doesNotContain("state_changed")
        client.stop()
    }

    @Test
    fun `with no tracked entities it subscribes to nothing rather than to everything`() {
        val client = HomeAssistantWebSocket(config(), scope)
        client.resume()

        val auth = JsonParser.parseString(nextMessage()).asJsonObject
        assertThat(auth.get("type").asString).isEqualTo("auth")

        // Nothing further should be sent.
        assertThat(received.poll(1, TimeUnit.SECONDS)).isNull()
        client.stop()
    }

    @Test
    fun `falls back to the unfiltered subscription when the server rejects the filtered one`() {
        val client = HomeAssistantWebSocket(config(), scope)
        client.updateTrackedEntities(listOf("lock.front"))
        client.resume()

        nextMessage()                                                     // auth
        val sub = JsonParser.parseString(nextMessage()).asJsonObject      // subscribe_trigger
        val subId = sub.get("id").asInt

        // An older Home Assistant without subscribe_trigger answers with an error result.
        serverSocket?.send(
            """{"id":$subId,"type":"result","success":false,"error":{"code":"unknown_command"}}"""
        )

        val fallback = JsonParser.parseString(nextMessage()).asJsonObject
        assertThat(fallback.get("type").asString).isEqualTo("subscribe_events")
        assertThat(fallback.get("event_type").asString).isEqualTo("state_changed")

        client.stop()
    }
}
