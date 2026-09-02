package com.haoverlay.coverscreen.data.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.haoverlay.coverscreen.data.model.HaConfig
import com.haoverlay.coverscreen.data.model.OverlayButtonConfig
import com.haoverlay.coverscreen.data.model.OverlaySettings

/**
 * Serialises and applies whole-app configuration as JSON.
 *
 * Two properties matter here and neither is incidental:
 *
 * 1. **Import is a partial update, not a replace.** Keys absent from the payload keep their current
 *    value. Feeding Gson a partial object directly would silently reset every omitted field to its
 *    default -- so an `haConfig` block without `accessToken` would blank the stored token, and a
 *    `settings` block without `orientation` would flip a vertical dock back to horizontal. Patches
 *    are therefore overlaid onto the *current* JSON before deserialisation.
 *
 * 2. **Unknown keys are reported, not ignored.** Gson drops unrecognised fields silently, so a
 *    payload written against the wrong field names appears to succeed while changing nothing. Every
 *    unknown key is named in the result summary instead.
 */
object ConfigTransfer {

    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    private val compact = GsonBuilder().serializeNulls().create()

    sealed class Result {
        data class Success(val summary: String) : Result()
        data class Failure(val message: String) : Result()
    }

    // ---------------------------------------------------------------- export

    /**
     * Serialises the current configuration.
     *
     * The access token is omitted unless [includeToken] is set, so an exported file can be shared
     * or committed without leaking a credential.
     */
    fun export(manager: SecureConfigManager, includeToken: Boolean = false): String {
        val haConfigJson = compact.toJsonTree(manager.getHaConfig()).asJsonObject
        if (!includeToken) {
            haConfigJson.remove("accessToken")
        }

        val root = JsonObject().apply {
            addProperty("_comment", "Cover HA Overlay configuration. Import is a partial update: omitted keys keep their current value.")
            addProperty("version", CONFIG_FORMAT_VERSION)
            add("haConfig", haConfigJson)
            add("buttons", compact.toJsonTree(manager.getButtons()))
            add("settings", compact.toJsonTree(manager.getOverlaySettings()))
            if (!includeToken) {
                addProperty("_note_token", "accessToken omitted. Pass --extra include_token:b:true to include it.")
            }
        }
        return gson.toJson(root)
    }

    /** A canonical, fully-populated example so callers can discover exact field names. */
    fun schema(): String {
        val root = JsonObject().apply {
            addProperty("_comment", "Canonical field names. Every key is optional; omitted keys are left unchanged.")
            addProperty("version", CONFIG_FORMAT_VERSION)
            add("haConfig", compact.toJsonTree(HaConfig()))
            add("buttons", compact.toJsonTree(listOf(OverlayButtonConfig())))
            add("settings", compact.toJsonTree(OverlaySettings()))
            add("_enums", JsonObject().apply {
                addProperty("dockPosition", enumNames<com.haoverlay.coverscreen.data.model.DockPosition>())
                addProperty("orientation", enumNames<com.haoverlay.coverscreen.data.model.OverlayOrientation>())
                addProperty("iconSize", enumNames<com.haoverlay.coverscreen.data.model.IconSize>())
                addProperty("backgroundStyle", enumNames<com.haoverlay.coverscreen.data.model.BackgroundStyle>())
                addProperty("targetDisplayMode", enumNames<com.haoverlay.coverscreen.data.model.TargetDisplayMode>())
            })
        }
        return gson.toJson(root)
    }

    private inline fun <reified T : Enum<T>> enumNames(): String =
        enumValues<T>().joinToString(" | ") { it.name }

    // ---------------------------------------------------------------- import

    fun import(manager: SecureConfigManager, payload: String): Result {
        val root = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (e: Exception) {
            return Result.Failure("Payload is not a JSON object: ${e.message}")
        }

        val applied = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // --- haConfig -------------------------------------------------------
        root.getAsJsonObjectOrNull("haConfig")?.let { patch ->
            val current = compact.toJsonTree(manager.getHaConfig()).asJsonObject
            warnings += unknownKeys("haConfig", patch, current)
            val merged = overlay(current, patch)
            val config = try {
                compact.fromJson(merged, HaConfig::class.java)
            } catch (e: Exception) {
                return Result.Failure("haConfig could not be parsed: ${e.message}")
            } ?: return Result.Failure("haConfig could not be parsed")

            manager.saveHaConfig(config)
            val tokenNote = if (patch.has("accessToken")) "token replaced" else "token preserved"
            applied += "haConfig (baseUrl=${config.cleanBaseUrl}, $tokenNote)"
        }

        // --- buttons --------------------------------------------------------
        root.get("buttons")?.takeIf { it.isJsonArray }?.let { array ->
            val template = compact.toJsonTree(OverlayButtonConfig()).asJsonObject
            array.asJsonArray.forEachIndexed { index, element ->
                if (element.isJsonObject) {
                    warnings += unknownKeys("buttons[$index]", element.asJsonObject, template)
                }
            }
            val listType = object : TypeToken<List<OverlayButtonConfig>>() {}.type
            val buttons: List<OverlayButtonConfig> = try {
                compact.fromJson(array, listType)
            } catch (e: Exception) {
                return Result.Failure("buttons could not be parsed: ${e.message}")
            } ?: return Result.Failure("buttons could not be parsed")

            buttons.forEachIndexed { index, button ->
                if (button.entityId.isBlank()) warnings += "buttons[$index] has an empty entityId"
            }
            manager.saveButtons(buttons)
            applied += "buttons (${buttons.size})"
        }

        // --- settings -------------------------------------------------------
        root.getAsJsonObjectOrNull("settings")?.let { patch ->
            val current = compact.toJsonTree(manager.getOverlaySettings()).asJsonObject
            warnings += unknownKeys("settings", patch, current)
            val merged = overlay(current, patch)
            val settings = try {
                compact.fromJson(merged, OverlaySettings::class.java)
            } catch (e: Exception) {
                return Result.Failure("settings could not be parsed: ${e.message}")
            } ?: return Result.Failure("settings could not be parsed")

            // Gson turns an unrecognised enum constant into null, which would otherwise sit in a
            // non-null Kotlin field and blow up somewhere far away from the cause.
            nullEnumField(settings)?.let { field ->
                return Result.Failure(
                    "settings.$field has an unrecognised value. Call the 'schema' method to see the accepted constants."
                )
            }
            manager.saveOverlaySettings(settings)
            applied += "settings"
        }

        if (applied.isEmpty()) {
            return Result.Failure(
                "Nothing applied. Expected at least one of: haConfig, buttons, settings."
            )
        }

        val summary = buildString {
            append("Applied: ").append(applied.joinToString(", "))
            if (warnings.isNotEmpty()) {
                append("\nWarnings:\n").append(warnings.joinToString("\n") { "  - $it" })
            }
        }
        return Result.Success(summary)
    }

    // ---------------------------------------------------------------- helpers

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    /** Returns a copy of [base] with every entry of [patch] applied over it. */
    private fun overlay(base: JsonObject, patch: JsonObject): JsonObject {
        val merged = base.deepCopy()
        for ((key, value) in patch.entrySet()) {
            merged.add(key, value)
        }
        return merged
    }

    /** Names any key in [patch] that the model does not define, so typos surface instead of vanishing. */
    private fun unknownKeys(context: String, patch: JsonObject, template: JsonObject): List<String> =
        patch.keySet()
            .filter { !it.startsWith("_") && !template.has(it) }
            .map { "$context.$it is not a recognised field and was ignored" }

    // Gson constructs via Unsafe, bypassing Kotlin's constructor null-checks, so these
    // non-null-typed fields genuinely can hold null at runtime.
    @Suppress("SENSELESS_COMPARISON")
    private fun nullEnumField(settings: OverlaySettings): String? = when {
        settings.dockPosition == null -> "dockPosition"
        settings.orientation == null -> "orientation"
        settings.iconSize == null -> "iconSize"
        settings.backgroundStyle == null -> "backgroundStyle"
        settings.targetDisplayMode == null -> "targetDisplayMode"
        else -> null
    }

    const val CONFIG_FORMAT_VERSION = 1
}
