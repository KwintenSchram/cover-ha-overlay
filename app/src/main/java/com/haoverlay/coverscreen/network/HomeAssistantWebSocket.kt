package com.haoverlay.coverscreen.network

import android.util.Log
import com.google.gson.Gson
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

    fun start() {
        isPaused = false
        if (webSocket == null && canConnect()) {
            connect()
        }
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
                    subscribeStateChanges(ws)
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
                    if (eventObj != null && eventObj.get("event_type")?.asString == "state_changed") {
                        val dataObj = eventObj.getAsJsonObject("data")
                        val newStateObj = dataObj?.getAsJsonObject("new_state")
                        if (newStateObj != null) {
                            val entityState = gson.fromJson(newStateObj, HaEntityState::class.java)
                            _stateUpdates.tryEmit(entityState)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling websocket message: $text", e)
        }
    }

    private fun subscribeStateChanges(ws: WebSocket) {
        val id = messageIdCounter.getAndIncrement()
        val subMsg = JsonObject().apply {
            addProperty("id", id)
            addProperty("type", "subscribe_events")
            addProperty("event_type", "state_changed")
        }
        ws.send(subMsg.toString())
    }

    companion object {
        private const val TAG = "HaWebSocket"

        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 300_000L   // 5 minutes
        private const val MAX_BACKOFF_EXPONENT = 8    // 2s -> 512s, clamped by MAX_BACKOFF_MS
        private const val JITTER_FRACTION = 0.25
    }
}
