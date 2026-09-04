package com.haoverlay.coverscreen.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.haoverlay.coverscreen.data.model.HaConfig
import com.haoverlay.coverscreen.data.model.HaEntityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages a persistent WebSocket connection to Home Assistant for real-time state updates.
 * Designed with battery efficiency: pauses connection when cover screen is inactive/asleep.
 */
class HomeAssistantWebSocket(
    private var config: HaConfig,
    private val scope: CoroutineScope
) {
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isPaused = false

    /**
     * Set when Home Assistant answers `auth_invalid`. HA closes the socket straight afterwards,
     * which used to trip onClosed -> reconnect -> auth fails -> repeat, forever, every 5s.
     * Once the token is rejected we stop until the configuration changes.
     */
    private var isAuthRejected = false
    private var reconnectAttempts = 0

    /**
     * Entities this client actually cares about.
     *
     * The subscription used to be an unfiltered `subscribe_events` for `state_changed`, which asks
     * Home Assistant to push *every* state change in the whole instance -- every sensor, power
     * meter and thermostat reading -- to a phone that displays three buttons. Over mobile data
     * that alone kept the radio busy.
     */
    private var trackedEntityIds: List<String> = emptyList()

    /** Id of the outstanding subscribe request, so its result can be matched. */
    private var subscriptionId: Int? = null

    /** Set once we have fallen back to the unfiltered subscription on an older Home Assistant. */
    private var usingLegacySubscription = false

    private val messageIdCounter = AtomicInteger(1)
    private var reconnectJob: Job? = null

    private val _stateUpdates = MutableSharedFlow<HaEntityState>(extraBufferCapacity = 64)
    val stateUpdates: SharedFlow<HaEntityState> = _stateUpdates.asSharedFlow()

    private val _connectionStatus = MutableSharedFlow<Boolean>(replay = 1)
    val connectionStatus: SharedFlow<Boolean> = _connectionStatus.asSharedFlow()

    /** Emits true when the access token was rejected, so callers can surface it to the user. */
    private val _authRejected = MutableSharedFlow<Boolean>(replay = 1)
    val authRejected: SharedFlow<Boolean> = _authRejected.asSharedFlow()

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sets the entities to subscribe to. Re-subscribes immediately when already connected.
     * With an empty list the client subscribes to nothing at all rather than to everything.
     */
    fun updateTrackedEntities(entityIds: List<String>) {
        val normalised = entityIds.filter { it.isNotBlank() }.distinct().sorted()
        if (normalised == trackedEntityIds) return
        trackedEntityIds = normalised
        if (isConnected) {
            webSocket?.let { subscribeToTrackedEntities(it) }
        }
    }

    fun updateConfig(newConfig: HaConfig) {
        val changed = config.cleanBaseUrl != newConfig.cleanBaseUrl || config.accessToken != newConfig.accessToken
        this.config = newConfig
        if (changed) {
            // New credentials deserve a fresh attempt even if the previous token was rejected.
            isAuthRejected = false
            reconnectAttempts = 0
            _authRejected.tryEmit(false)
            if (isConnected || !isPaused) {
                reconnect()
            }
        }
    }

    /**
     * Allows connecting, but does not connect by itself.
     *
     * This used to clear [isPaused] and dial immediately. The watchdog re-sends ACTION_START every
     * 15 minutes, so that reconnected the socket regardless of whether the cover screen was awake
     * -- and because isCoverDisplayActive is a StateFlow whose value had not *changed*, nothing
     * ever emitted to pause it again. The socket then stayed up indefinitely. Connecting is now
     * driven solely by [resume] and [pause], which follow the cover screen.
     */
    fun start() {
        // Intentionally does not connect. See resume().
    }

    fun pause() {
        isPaused = true
        disconnect()
    }

    fun resume() {
        isPaused = false
        if (canConnect()) {
            connect()
        }
    }

    fun stop() {
        isPaused = true
        reconnectJob?.cancel()
        reconnectJob = null
        disconnect()
    }

    private fun canConnect(): Boolean =
        config.isValid && config.useWebSocket && !isAuthRejected

    private fun connect() {
        if (isPaused || !canConnect()) return

        val wsUrl = config.cleanBaseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/api/websocket"

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, createListener())
    }

    private fun disconnect() {
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing websocket", e)
        }
        webSocket = null
        isConnected = false
        subscriptionId = null
        _connectionStatus.tryEmit(false)
    }

    /**
     * Schedules a reconnect using exponential backoff with jitter.
     *
     * The previous implementation retried on a flat 5s timer with no ceiling and no give-up
     * condition, so an unreachable server or a revoked token meant a network round trip every
     * five seconds indefinitely.
     */
    private fun reconnect() {
        disconnect()
        if (isPaused || isAuthRejected) return

        val attempt = reconnectAttempts.coerceAtMost(MAX_BACKOFF_EXPONENT)
        val backoffMs = (INITIAL_BACKOFF_MS shl attempt).coerceAtMost(MAX_BACKOFF_MS)
        val jitterMs = (backoffMs * JITTER_FRACTION * Math.random()).toLong()
        val delayMs = backoffMs + jitterMs
        reconnectAttempts++

        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "WebSocket reconnect attempt #" + reconnectAttempts + " in " + delayMs + "ms")
            delay(delayMs)
            if (isActive && !isPaused && !isAuthRejected) {
                connect()
            }
        }
    }

    private fun createListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                isConnected = false
                _connectionStatus.tryEmit(false)
                reconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                isConnected = false
                _connectionStatus.tryEmit(false)
                reconnect()
            }
        }
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString ?: return

            when (type) {
                "auth_required" -> {
                    val authMsg = JsonObject().apply {
                        addProperty("type", "auth")
                        addProperty("access_token", config.accessToken.trim())
                    }
                    ws.send(authMsg.toString())
                }
                "auth_ok" -> {
                    Log.i(TAG, "WebSocket authentication successful")
                    isConnected = true
                    isAuthRejected = false
                    reconnectAttempts = 0
                    _connectionStatus.tryEmit(true)
                    _authRejected.tryEmit(false)
                    subscribeToTrackedEntities(ws)
                }
                "auth_invalid" -> {
                    Log.e(
                        TAG,
                        "WebSocket authentication rejected: ${json.get("message")?.asString}. " +
                            "Halting reconnects until the credentials change."
                    )
                    isConnected = false
                    isAuthRejected = true
                    reconnectJob?.cancel()
                    reconnectJob = null
                    _connectionStatus.tryEmit(false)
                    _authRejected.tryEmit(true)
                }
                "event" -> {
                    val eventObj = json.getAsJsonObject("event")

                    // subscribe_trigger delivers the new state under variables.trigger.to_state.
                    val toState = eventObj
                        ?.getAsJsonObject("variables")
                        ?.getAsJsonObject("trigger")
                        ?.getAsJsonObject("to_state")

                    val newStateObj = toState
                        ?: if (eventObj?.get("event_type")?.asString == "state_changed") {
                            // Unfiltered fallback shape.
                            eventObj.getAsJsonObject("data")?.getAsJsonObject("new_state")
                        } else {
                            null
                        }

                    if (newStateObj != null) {
                        val entityState = gson.fromJson(newStateObj, HaEntityState::class.java)
                        // On the fallback path everything in the instance arrives, so drop what
                        // this client does not display rather than waking collectors for it.
                        if (trackedEntityIds.isEmpty() || entityState.entityId in trackedEntityIds) {
                            _stateUpdates.tryEmit(entityState)
                        }
                    }
                }

                "result" -> {
                    val resultId = json.get("id")?.asInt
                    val succeeded = json.get("success")?.asBoolean ?: true
                    if (resultId != null && resultId == subscriptionId && !succeeded &&
                        !usingLegacySubscription
                    ) {
                        subscribeAllStateChanges(ws)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling websocket message: $text", e)
        }
    }

    /**
     * Subscribes to state changes for [trackedEntityIds] only, using a `state` trigger so that
     * Home Assistant does the filtering server-side and nothing else crosses the network.
     */
    private fun subscribeToTrackedEntities(ws: WebSocket) {
        if (trackedEntityIds.isEmpty()) {
            Log.d(TAG, "No tracked entities; not subscribing")
            subscriptionId = null
            return
        }

        val id = messageIdCounter.getAndIncrement()
        subscriptionId = id
        val message = JsonObject().apply {
            addProperty("id", id)
            addProperty("type", "subscribe_trigger")
            add("trigger", JsonObject().apply {
                addProperty("platform", "state")
                add("entity_id", JsonArray().apply { trackedEntityIds.forEach { add(it) } })
            })
        }
        Log.i(TAG, "Subscribing to ${trackedEntityIds.size} entities")
        ws.send(message.toString())
    }

    /**
     * Unfiltered fallback for Home Assistant versions without `subscribe_trigger`. Every state
     * change in the instance arrives and is filtered on the device, so this is the expensive path
     * and is only taken when the server rejects the filtered subscription.
     */
    private fun subscribeAllStateChanges(ws: WebSocket) {
        usingLegacySubscription = true
        val id = messageIdCounter.getAndIncrement()
        subscriptionId = id
        val message = JsonObject().apply {
            addProperty("id", id)
            addProperty("type", "subscribe_events")
            addProperty("event_type", "state_changed")
        }
        Log.w(TAG, "subscribe_trigger unavailable; falling back to unfiltered state_changed")
        ws.send(message.toString())
    }

    companion object {
        private const val TAG = "HaWebSocket"

        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 300_000L   // 5 minutes
        private const val MAX_BACKOFF_EXPONENT = 8    // 2s -> 512s, clamped by MAX_BACKOFF_MS
        private const val JITTER_FRACTION = 0.25
    }
}
