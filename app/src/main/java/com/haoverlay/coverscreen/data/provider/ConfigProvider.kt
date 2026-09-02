package com.haoverlay.coverscreen.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.haoverlay.coverscreen.data.storage.ConfigTransfer
import com.haoverlay.coverscreen.data.storage.SecureConfigManager

/**
 * Programmatic configuration channel for tooling (adb, CI, provisioning scripts, agents).
 *
 * ## Why this exists
 *
 * Configuration used to be settable by starting the exported [com.haoverlay.coverscreen.ui.MainActivity]
 * with a few string extras, or by dropping a file at `/data/local/tmp/restore_config.json`, which the
 * app read on *every* launch. That was convenient for automation and also a backdoor: it carried no
 * authentication whatsoever, so any installed app could rewrite the Home Assistant server and access
 * token, and anything able to write that path had a foothold that re-applied on every launch.
 *
 * Removing it closed the hole but left no supported way to configure the app without tapping through
 * the UI -- and the UI cannot even express every field of [com.haoverlay.coverscreen.data.model.OverlayButtonConfig]
 * (`showState`, `guardTriggerState` and `guardConfirmationWindowMs` have no controls).
 *
 * ## How this is safe
 *
 * This provider authenticates the *caller* rather than trusting ambient reachability: every entry
 * point rejects anything whose [Binder.getCallingUid] is not the adb shell or root. Installed apps
 * each run under their own UID and are refused, whatever permissions they hold. Callers that clear
 * the identity cannot spoof it either -- `getCallingUid()` returns the app's own UID for in-process
 * calls, which is likewise refused.
 *
 * Anyone holding adb shell already has far broader access to the device than this provider grants,
 * so it widens no boundary that was not already open.
 *
 * ## Usage
 *
 * Export the current configuration (never includes the access token):
 * ```
 * adb shell content call --uri content://com.haoverlay.coverscreen.config \
 *     --method export
 * ```
 *
 * Apply a configuration. Keys absent from the payload are left untouched, so this is a partial
 * update, not a replace -- in particular a payload whose `haConfig` omits `accessToken` keeps the
 * token already on the device.
 *
 * Pass the JSON base64-encoded. `content call --extra key:type:value` splits its argument on
 * colons, which every JSON document is full of, so a raw payload is rejected as a malformed
 * binding. Base64 contains no colons and survives both the host and the device shell intact:
 * ```
 * adb shell content call --uri content://com.haoverlay.coverscreen.config \
 *     --method import --extra payload_b64:s:"$(base64 -w0 config.json)"
 * ```
 *
 * Raw JSON is still accepted through [EXTRA_PAYLOAD] or `--arg` for callers that build the Bundle
 * directly instead of going through the `content` shell tool.
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val denial = rejectUntrustedCaller()
        if (denial != null) return denial

        val ctx = context ?: return errorBundle("Provider has no context")
        val manager = SecureConfigManager.getInstance(ctx)

        return when (method) {
            METHOD_EXPORT -> {
                val includeToken = extras?.getBoolean(EXTRA_INCLUDE_TOKEN, false) ?: false
                Bundle().apply {
                    putBoolean(KEY_SUCCESS, true)
                    putString(KEY_PAYLOAD, ConfigTransfer.export(manager, includeToken))
                }
            }

            METHOD_IMPORT -> {
                val payload = resolvePayload(arg, extras)
                    ?: return errorBundle(
                        "No payload. Pass --extra payload_b64:s:<base64 of the json>, " +
                            "or raw JSON in the 'payload' extra."
                    )
                when (val result = ConfigTransfer.import(manager, payload)) {
                    is ConfigTransfer.Result.Success -> Bundle().apply {
                        putBoolean(KEY_SUCCESS, true)
                        putString(KEY_SUMMARY, result.summary)
                    }
                    is ConfigTransfer.Result.Failure -> errorBundle(result.message)
                }
            }

            METHOD_SCHEMA -> Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putString(KEY_PAYLOAD, ConfigTransfer.schema())
            }

            else -> errorBundle("Unknown method '$method'. Use export, import or schema.")
        }
    }

    /**
     * Resolves the payload: base64 first, then raw JSON, then the positional argument.
     * Returns null when nothing usable was supplied.
     */
    private fun resolvePayload(arg: String?, extras: Bundle?): String? {
        extras?.getString(EXTRA_PAYLOAD_B64)?.takeIf { it.isNotBlank() }?.let { encoded ->
            return try {
                String(Base64.decode(encoded.trim(), Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "payload_b64 was not valid base64", e)
                null
            }
        }
        extras?.getString(EXTRA_PAYLOAD)?.takeIf { it.isNotBlank() }?.let { return it }
        return arg?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns a populated denial [Bundle] when the caller is not adb shell or root, or null when
     * the call may proceed.
     */
    private fun rejectUntrustedCaller(): Bundle? =
        when (val access = checkAccess(Binder.getCallingUid())) {
            is Access.Allowed -> null
            is Access.Denied -> {
                Log.w(TAG, access.message)
                errorBundle(access.message)
            }
        }

    private fun errorBundle(message: String) = Bundle().apply {
        putBoolean(KEY_SUCCESS, false)
        putString(KEY_ERROR, message)
    }

    // The provider exposes no table surface; everything goes through call().
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    /** Outcome of the caller check. */
    sealed class Access {
        data object Allowed : Access()
        data class Denied(val message: String) : Access()
    }

    companion object {
        private const val TAG = "ConfigProvider"

        const val AUTHORITY = "com.haoverlay.coverscreen.config"

        /**
         * The entire authorisation boundary for this provider, kept as a pure function so it can be
         * tested directly. The provider is exported so adb can reach it, which means nothing else
         * stands between an installed app and the Home Assistant credentials.
         */
        fun checkAccess(uid: Int): Access =
            if (uid == Process.SHELL_UID || uid == Process.ROOT_UID) {
                Access.Allowed
            } else {
                Access.Denied(
                    "Refused: configuration may only be set from adb shell or root (caller uid $uid)."
                )
            }

        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val METHOD_SCHEMA = "schema"

        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_PAYLOAD_B64 = "payload_b64"
        const val EXTRA_INCLUDE_TOKEN = "include_token"

        const val KEY_SUCCESS = "success"
        const val KEY_PAYLOAD = "payload"
        const val KEY_SUMMARY = "summary"
        const val KEY_ERROR = "error"
    }
}
