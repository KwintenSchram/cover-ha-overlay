package com.haoverlay.coverscreen.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.haoverlay.coverscreen.data.model.HaConfig
import com.haoverlay.coverscreen.data.model.HaEntityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class HaResult<out T> {
    data class Success<out T>(val data: T) : HaResult<T>()
    data class Error(val message: String, val statusCode: Int? = null, val throwable: Throwable? = null) : HaResult<Nothing>()
}

data class ConnectionTestResult(
    val isSuccess: Boolean,
    val message: String,
    val serverVersion: String? = null,
    val latencyMs: Long = 0
)

class HomeAssistantClient(
    private var config: HaConfig,
    customClient: OkHttpClient? = null
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = customClient ?: OkHttpClient.Builder()
        .connectTimeout(config.connectionTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun updateConfig(newConfig: HaConfig) {
        this.config = newConfig
    }

    /**
     * Test connection to Home Assistant endpoint.
     */
    suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (!config.isValid) {
            return@withContext ConnectionTestResult(
                isSuccess = false,
                message = "Invalid URL or missing Access Token"
            )
        }

        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url("${config.cleanBaseUrl}/api/")
            .header("Authorization", "Bearer ${config.accessToken.trim()}")
            .header("Content-Type", "application/json")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    val json = try {
                        JsonParser.parseString(bodyString).asJsonObject
                    } catch (e: Exception) {
                        null
                    }
                    val msg = json?.get("message")?.asString ?: "Connected"
                    ConnectionTestResult(
                        isSuccess = true,
                        message = msg,
                        serverVersion = json?.get("version")?.asString,
                        latencyMs = latency
                    )
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "401 Unauthorized - Invalid Access Token"
                        403 -> "403 Forbidden - Token lacks permissions"
                        404 -> "404 Not Found - HA API endpoint missing"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    ConnectionTestResult(
                        isSuccess = false,
                        message = errorMsg,
                        latencyMs = latency
                    )
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            ConnectionTestResult(
                isSuccess = false,
                message = "Connection failed: ${e.localizedMessage ?: e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error testing connection", e)
            ConnectionTestResult(
                isSuccess = false,
                message = "Error: ${e.localizedMessage ?: e.message}"
            )
        }
    }

    /**
     * Fetch all entities and their states from Home Assistant.
     */
    suspend fun fetchEntities(): HaResult<List<HaEntityState>> = withContext(Dispatchers.IO) {
        if (!config.isValid) {
            return@withContext HaResult.Error("Home Assistant is not configured")
        }

        val request = Request.Builder()
            .url("${config.cleanBaseUrl}/api/states")
            .header("Authorization", "Bearer ${config.accessToken.trim()}")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: "[]"
                    val type = object : TypeToken<List<HaEntityState>>() {}.type
                    val states: List<HaEntityState> = gson.fromJson(bodyString, type) ?: emptyList()
                    HaResult.Success(states)
                } else {
                    HaResult.Error("HTTP ${response.code}: ${response.message}", response.code)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch entities", e)
            HaResult.Error(e.localizedMessage ?: "Network error", throwable = e)
        }
    }

    /**
     * Fetch a single entity state.
     */
    suspend fun fetchEntityState(entityId: String): HaResult<HaEntityState> = withContext(Dispatchers.IO) {
        if (!config.isValid || entityId.isBlank()) {
            return@withContext HaResult.Error("Invalid parameters")
        }

        val request = Request.Builder()
            .url("${config.cleanBaseUrl}/api/states/$entityId")
            .header("Authorization", "Bearer ${config.accessToken.trim()}")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    val state = gson.fromJson(bodyString, HaEntityState::class.java)
                    HaResult.Success(state)
                } else {
                    HaResult.Error("HTTP ${response.code}: ${response.message}", response.code)
                }
            }
        } catch (e: Exception) {
            HaResult.Error(e.localizedMessage ?: "Network error", throwable = e)
        }
    }

    /**
     * Execute a service call in Home Assistant.
     * e.g. domain = "light", service = "toggle", entityId = "light.kitchen"
     */
    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        serviceDataJson: String? = null
    ): HaResult<List<HaEntityState>> = withContext(Dispatchers.IO) {
        if (!config.isValid) {
            return@withContext HaResult.Error("Home Assistant is not configured")
        }

        val payload = JsonObject()
        if (entityId.isNotBlank()) {
            payload.addProperty("entity_id", entityId)
        }

        if (!serviceDataJson.isNullOrBlank()) {
            try {
                val customData = JsonParser.parseString(serviceDataJson).asJsonObject
                for ((key, value) in customData.entrySet()) {
                    payload.add(key, value)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse custom serviceDataJson: $serviceDataJson", e)
            }
        }

        val url = "${config.cleanBaseUrl}/api/services/$domain/$service"
        val body = payload.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.accessToken.trim()}")
            .post(body)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: "[]"
                    val type = object : TypeToken<List<HaEntityState>>() {}.type
                    val updatedStates: List<HaEntityState> = try {
                        gson.fromJson(bodyString, type) ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    HaResult.Success(updatedStates)
                } else {
                    val errorBody = response.body?.string()
                    val msg = "Service call failed (HTTP ${response.code}): ${errorBody ?: response.message}"
                    Log.e(TAG, msg)
                    HaResult.Error(msg, response.code)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling service $domain.$service", e)
            HaResult.Error(e.localizedMessage ?: "Network error calling service", throwable = e)
        }
    }

    companion object {
        private const val TAG = "HomeAssistantClient"
    }
}
