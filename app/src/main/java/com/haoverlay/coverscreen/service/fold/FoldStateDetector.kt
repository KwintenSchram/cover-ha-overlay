package com.haoverlay.coverscreen.service.fold

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.haoverlay.coverscreen.service.display.CoverDisplayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DeviceFoldState {
    FOLDED,       // Device closed -> Cover display is active
    UNFOLDED,     // Device open -> Main display is active
    HALF_FOLDED,  // Flex mode
    UNKNOWN
}

/**
 * Monitors fold state and display power state transitions using a hybrid of
 * Samsung fold broadcasts, display power state listeners, and screen receivers.
 */
class FoldStateDetector(
    private val context: Context,
    private val coverDisplayManager: CoverDisplayManager,
    private val scope: CoroutineScope
) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _foldState = MutableStateFlow(DeviceFoldState.UNKNOWN)
    val foldState: StateFlow<DeviceFoldState> = _foldState.asStateFlow()

    private val _isCoverDisplayActive = MutableStateFlow(false)
    val isCoverDisplayActive: StateFlow<Boolean> = _isCoverDisplayActive.asStateFlow()

    private var isListening = false

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "Received broadcast action: $action")

            when (action) {
                // Samsung fold state broadcast
                "com.samsung.intent.action.FOLD_STATE",
                "com.samsung.android.motion.FOLDING_ACTION",
                "com.samsung.intent.action.SE_FOLD_STATE" -> {
                    val isFolded = intent.getBooleanExtra("fold_state", false) ||
                            intent.getBooleanExtra("sem_fold_state", false) ||
                            intent.getIntExtra("fold_state", 0) == 1
                    handleFoldBroadcast(isFolded)
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_PRESENT -> {
                    evaluateCurrentState()
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            evaluateCurrentState()
        }

        override fun onDisplayRemoved(displayId: Int) {
            evaluateCurrentState()
        }

        override fun onDisplayChanged(displayId: Int) {
            evaluateCurrentState()
        }
    }

    fun start() {
        if (isListening) return
        isListening = true

        val filter = IntentFilter().apply {
            addAction("com.samsung.intent.action.FOLD_STATE")
            addAction("com.samsung.android.motion.FOLDING_ACTION")
            addAction("com.samsung.intent.action.SE_FOLD_STATE")
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        // NOT_EXPORTED: every action in this filter is a protected system broadcast, which the
        // platform still delivers. Registering as EXPORTED let any installed app fake
        // com.samsung.intent.action.FOLD_STATE and force the overlay to attach on demand.
        ContextCompat.registerReceiver(
            context,
            broadcastReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        displayManager.registerDisplayListener(displayListener, null)
        evaluateCurrentState()
    }

    fun stop() {
        if (!isListening) return
        isListening = false

        try {
            context.unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering fold broadcast receiver", e)
        }

        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering fold display listener", e)
        }
    }

    private fun handleFoldBroadcast(isFolded: Boolean) {
        val newState = if (isFolded) DeviceFoldState.FOLDED else DeviceFoldState.UNFOLDED
        Log.i(TAG, "Samsung fold broadcast indicates state: $newState")
        _foldState.value = newState
        evaluateCurrentState()
    }

    /**
     * Determines whether the cover display is currently the active, awake display.
     */
    fun evaluateCurrentState() {
        val coverDisplay = coverDisplayManager.findCoverDisplay()
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        if (coverDisplay != null) {
            // When device is folded, Samsung turns coverDisplay ON (state == STATE_ON) and defaultDisplay OFF
            val coverOn = coverDisplay.state == Display.STATE_ON
            val defaultOff = defaultDisplay?.state != Display.STATE_ON

            val isCoverActive = coverOn || (coverDisplay.state != Display.STATE_OFF && defaultOff)
            _isCoverDisplayActive.value = isCoverActive

            val currentFold = if (coverOn && defaultOff) {
                DeviceFoldState.FOLDED
            } else if (defaultDisplay?.state == Display.STATE_ON) {
                DeviceFoldState.UNFOLDED
            } else {
                _foldState.value
            }
            _foldState.value = currentFold

            Log.d(TAG, "Cover display state: ${coverDisplay.state}, default display state: ${defaultDisplay?.state}, isCoverActive: $isCoverActive")
        } else {
            // If no cover display detected (e.g. standard phone or emulator)
            val isScreenOn = powerManager.isInteractive
            _isCoverDisplayActive.value = isScreenOn
            _foldState.value = DeviceFoldState.FOLDED
        }
    }

    companion object {
        private const val TAG = "FoldStateDetector"
    }
}
