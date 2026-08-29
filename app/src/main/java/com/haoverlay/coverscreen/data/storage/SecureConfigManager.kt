package com.haoverlay.coverscreen.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.haoverlay.coverscreen.data.model.HaConfig
import com.haoverlay.coverscreen.data.model.OverlayButtonConfig
import com.haoverlay.coverscreen.data.model.OverlaySettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages encrypted and persistent storage for Home Assistant credentials,
 * overlay buttons, and overlay layout preferences.
 */
class SecureConfigManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to standard prefs", e)
        appContext.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
    }

    private val _haConfigFlow = MutableStateFlow(getHaConfig())
    val haConfigFlow: StateFlow<HaConfig> = _haConfigFlow.asStateFlow()

    private val _buttonsFlow = MutableStateFlow(getButtons())
    val buttonsFlow: StateFlow<List<OverlayButtonConfig>> = _buttonsFlow.asStateFlow()

    private val _settingsFlow = MutableStateFlow(getOverlaySettings())
    val settingsFlow: StateFlow<OverlaySettings> = _settingsFlow.asStateFlow()

    // --- Home Assistant Configuration ---

    fun getHaConfig(): HaConfig {
        val json = prefs.getString(KEY_HA_CONFIG, null)
        return if (!json.isNullOrBlank()) {
            try {
                gson.fromJson(json, HaConfig::class.java) ?: getDefaultHaConfig()
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing HaConfig", e)
                getDefaultHaConfig()
            }
        } else {
            getDefaultHaConfig()
        }
    }

    fun saveHaConfig(config: HaConfig) {
        val json = gson.toJson(config)
        prefs.edit().putString(KEY_HA_CONFIG, json).apply()
        _haConfigFlow.value = config
    }

    // --- Overlay Buttons Configuration ---

    fun getButtons(): List<OverlayButtonConfig> {
        val json = prefs.getString(KEY_BUTTONS, null)
        return if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<OverlayButtonConfig>>() {}.type
                gson.fromJson<List<OverlayButtonConfig>>(json, type) ?: getDefaultButtons()
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing buttons JSON", e)
                getDefaultButtons()
            }
        } else {
            getDefaultButtons()
        }
    }

    fun saveButtons(buttons: List<OverlayButtonConfig>) {
        val sorted = buttons.sortedBy { it.order }
        val json = gson.toJson(sorted)
        prefs.edit().putString(KEY_BUTTONS, json).apply()
        _buttonsFlow.value = sorted
    }

    fun addButton(button: OverlayButtonConfig) {
        val current = getButtons().toMutableList()
        val order = if (current.isEmpty()) 0 else current.maxOf { it.order } + 1
        current.add(button.copy(order = order))
        saveButtons(current)
    }

    fun updateButton(button: OverlayButtonConfig) {
        val current = getButtons().toMutableList()
        val index = current.indexOfFirst { it.id == button.id }
        if (index != -1) {
            current[index] = button
            saveButtons(current)
        }
    }

    fun removeButton(buttonId: String) {
        val current = getButtons().filterNot { it.id == buttonId }
        saveButtons(current)
    }

    fun reorderButtons(buttons: List<OverlayButtonConfig>) {
        val reindexed = buttons.mapIndexed { index, button ->
            button.copy(order = index)
        }
        saveButtons(reindexed)
    }

    // --- Overlay Settings ---

    fun getOverlaySettings(): OverlaySettings {
        val json = prefs.getString(KEY_SETTINGS, null)
        return if (!json.isNullOrBlank()) {
            try {
                gson.fromJson(json, OverlaySettings::class.java) ?: OverlaySettings()
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing OverlaySettings", e)
                OverlaySettings()
            }
        } else {
            OverlaySettings()
        }
    }

    fun saveOverlaySettings(settings: OverlaySettings) {
        val json = gson.toJson(settings)
        prefs.edit().putString(KEY_SETTINGS, json).apply()
        _settingsFlow.value = settings
    }

    fun updateCustomPosition(x: Int, y: Int) {
        val current = getOverlaySettings()
        saveOverlaySettings(current.copy(customX = x, customY = y))
    }

    fun setServiceEnabled(enabled: Boolean) {
        val current = getOverlaySettings()
        saveOverlaySettings(current.copy(isServiceEnabled = enabled))
    }

    private fun getDefaultHaConfig(): HaConfig {
        return HaConfig(
            baseUrl = "http://homeassistant.local:8123",
            accessToken = "",
            useWebSocket = true,
            pollIntervalSeconds = 10
        )
    }

    private fun getDefaultButtons(): List<OverlayButtonConfig> {
        return listOf(
            OverlayButtonConfig(
                entityId = "light.living_room",
                domain = "light",
                service = "toggle",
                iconName = "lightbulb",
                label = "Living Room",
                customColorHex = "#F59E0B",
                order = 0
            ),
            OverlayButtonConfig(
                entityId = "switch.fan",
                domain = "switch",
                service = "toggle",
                iconName = "fan",
                label = "Fan",
                customColorHex = "#3B82F6",
                order = 1
            ),
            OverlayButtonConfig(
                entityId = "lock.front_door",
                domain = "lock",
                service = "lock",
                iconName = "lock",
                label = "Front Door",
                customColorHex = "#EF4444",
                order = 2,
                showState = true
            )
        )
    }

    companion object {
        private const val TAG = "SecureConfigManager"
        private const val ENCRYPTED_PREFS_FILE = "ha_overlay_secure_prefs"
        private const val FALLBACK_PREFS_FILE = "ha_overlay_prefs"

        private const val KEY_HA_CONFIG = "key_ha_config"
        private const val KEY_BUTTONS = "key_buttons"
        private const val KEY_SETTINGS = "key_settings"

        @Volatile
        private var INSTANCE: SecureConfigManager? = null

        fun getInstance(context: Context): SecureConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureConfigManager(context).also { INSTANCE = it }
            }
        }
    }
}
