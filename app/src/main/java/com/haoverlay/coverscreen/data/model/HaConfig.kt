package com.haoverlay.coverscreen.data.model

import com.google.gson.annotations.SerializedName

/**
 * Configuration for connecting to a Home Assistant instance.
 */
data class HaConfig(
    @SerializedName("baseUrl")
    val baseUrl: String = "",

    @SerializedName("accessToken")
    val accessToken: String = "",

    @SerializedName("useWebSocket")
    val useWebSocket: Boolean = true,

    @SerializedName("pollIntervalSeconds")
    val pollIntervalSeconds: Int = 10,

    @SerializedName("connectionTimeoutSeconds")
    val connectionTimeoutSeconds: Int = 5
) {
    val cleanBaseUrl: String
        get() {
            var url = baseUrl.trim()
            if (url.endsWith("/")) {
                url = url.substring(0, url.length - 1)
            }
            return url
        }

    val isValid: Boolean
        get() = cleanBaseUrl.isNotBlank() && 
                (cleanBaseUrl.startsWith("http://") || cleanBaseUrl.startsWith("https://")) &&
                accessToken.isNotBlank()
}
