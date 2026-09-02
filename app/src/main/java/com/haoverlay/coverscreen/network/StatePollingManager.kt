package com.haoverlay.coverscreen.network

import android.util.Log
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

/**
 * Periodically polls the state of active overlay entities when WebSocket is disabled or reconnecting.
 */
class StatePollingManager(
    private val client: HomeAssistantClient,
    private val scope: CoroutineScope
) {
    private var pollingJob: Job? = null
    private var entityIdsToPoll: List<String> = emptyList()
    private var pollIntervalMs: Long = 10_000L
    private var isPollingActive = false

    private val _polledStateUpdates = MutableSharedFlow<HaEntityState>(extraBufferCapacity = 32)
    val polledStateUpdates: SharedFlow<HaEntityState> = _polledStateUpdates.asSharedFlow()

    fun updateTrackedEntities(entityIds: List<String>, intervalSeconds: Int) {
        this.entityIdsToPoll = entityIds.filter { it.isNotBlank() }.distinct()
        this.pollIntervalMs = (intervalSeconds.coerceAtLeast(3) * 1000).toLong()
        if (isPollingActive) {
            restartPolling()
        }
    }

    fun startPolling() {
        isPollingActive = true
        restartPolling()
    }

    fun stopPolling() {
        isPollingActive = false
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun restartPolling() {
        pollingJob?.cancel()
        if (!isPollingActive || entityIdsToPoll.isEmpty()) return

        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive && isPollingActive) {
                val tracked = entityIdsToPoll.toSet()

                // One /api/states call covers the whole set. This previously issued a sequential
                // GET /api/states/<id> per entity per tick, so six buttons meant six round trips
                // every interval while the cover screen was awake.
                when (val result = client.fetchEntities()) {
                    is HaResult.Success -> {
                        for (state in result.data) {
                            if (state.entityId in tracked) {
                                _polledStateUpdates.tryEmit(state)
                            }
                        }
                    }
                    is HaResult.Error -> {
                        Log.w(TAG, "Failed to poll entity states: ${result.message}")
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    companion object {
        private const val TAG = "StatePollingManager"
    }
}
