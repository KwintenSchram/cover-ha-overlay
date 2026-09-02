package com.haoverlay.coverscreen.data.model

import com.google.gson.annotations.SerializedName
import java.net.URI

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

    /** Host portion of [cleanBaseUrl], or an empty string when it cannot be parsed. */
    val host: String
        get() = try {
            URI(cleanBaseUrl).host.orEmpty()
        } catch (e: Exception) {
            ""
        }

    /**
     * True when this configuration would send the bearer token over unencrypted HTTP to a host
     * that is not on the local network.
     *
     * `usesCleartextTraffic` has to stay enabled because most Home Assistant installs are plain
     * HTTP on the LAN, and Android's network-security-config cannot express CIDR ranges -- so
     * the check lives here and is surfaced in the setup UI instead of being enforced by the
     * platform.
     */
    val isCleartextToPublicHost: Boolean
        get() = cleanBaseUrl.startsWith("http://") &&
                host.isNotBlank() &&
                !isPrivateHost(host)

    companion object {
        private val PRIVATE_IPV4 = Regex(
            "^(10\\.|127\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.)"
        )

        private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain")

        private val ULA_IPV6_PREFIXES = listOf("fc", "fd", "fe8", "fe9", "fea", "feb")

        /** RFC1918 / loopback / link-local / mDNS hosts that are safe to reach over cleartext. */
        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h.isBlank()) return false
            if (h == "localhost" || h == "::1") return true
            if (PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
            if (PRIVATE_IPV4.containsMatchIn(h)) return true
            // IPv6 unique-local (fc00::/7) and link-local (fe80::/10) literals.
            if (h.contains(":") && ULA_IPV6_PREFIXES.any { h.startsWith(it) }) return true
            return false
        }
    }
}
