package com.haoverlay.coverscreen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.haoverlay.coverscreen.R
import com.haoverlay.coverscreen.data.model.DockPosition
import com.haoverlay.coverscreen.data.model.HaEntityState
import com.haoverlay.coverscreen.data.model.OverlayButtonConfig
import com.haoverlay.coverscreen.data.model.OverlaySettings
import com.haoverlay.coverscreen.data.storage.SecureConfigManager
import com.haoverlay.coverscreen.network.HaResult
import com.haoverlay.coverscreen.network.HomeAssistantClient
import com.haoverlay.coverscreen.network.HomeAssistantWebSocket
import com.haoverlay.coverscreen.network.StatePollingManager
import com.haoverlay.coverscreen.service.display.CoverDisplayManager
import com.haoverlay.coverscreen.service.fold.DeviceFoldState
import com.haoverlay.coverscreen.service.fold.FoldStateDetector
import com.haoverlay.coverscreen.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Core Foreground Service that manages the lifecycle of the cover screen quick-control overlay.
 */
class CoverOverlayService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var configManager: SecureConfigManager
    private lateinit var coverDisplayManager: CoverDisplayManager
    private lateinit var foldStateDetector: FoldStateDetector
    private lateinit var haClient: HomeAssistantClient
    private lateinit var haWebSocket: HomeAssistantWebSocket
    private lateinit var pollingManager: StatePollingManager

    private var currentWindowManager: WindowManager? = null
    private var overlayView: CoverOverlayView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var isOverlayAttached = false
    private var isForcedPreview = false

    private val confirmationPendingButtons = ConcurrentHashMap<String, Long>()
    private val cachedEntityStates = ConcurrentHashMap<String, HaEntityState>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CoverOverlayService onCreate")

        configManager = SecureConfigManager.getInstance(this)
        coverDisplayManager = CoverDisplayManager(this)
        foldStateDetector = FoldStateDetector(this, coverDisplayManager, serviceScope)

        val initialHaConfig = configManager.getHaConfig()
        haClient = HomeAssistantClient(initialHaConfig)
        haWebSocket = HomeAssistantWebSocket(initialHaConfig, serviceScope)
        pollingManager = StatePollingManager(haClient, serviceScope)

        coverDisplayManager.start()
        foldStateDetector.start()

        startForegroundNotification()
        observeConfigChanges()
        observeFoldAndDisplayState()
        observeNetworkStateUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START -> {
                configManager.setServiceEnabled(true)
                haWebSocket.start()
                pollingManager.startPolling()
                foldStateDetector.evaluateCurrentState()
                updateOverlayVisibility()
            }
            ACTION_STOP -> {
                configManager.setServiceEnabled(false)
                detachOverlay()
                haWebSocket.stop()
                pollingManager.stopPolling()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_REFRESH_CONFIG -> {
                val newConfig = configManager.getHaConfig()
                haClient.updateConfig(newConfig)
                haWebSocket.updateConfig(newConfig)
                updateOverlayVisibility()
            }
            ACTION_TEST_OVERLAY -> {
                isForcedPreview = intent?.getBooleanExtra(EXTRA_FORCED_PREVIEW, false) ?: false
                updateOverlayVisibility()
            }
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        createNotificationChannel()

        val settingsIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val settingsPendingIntent = PendingIntent.getActivity(
            this, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CoverOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(settingsPendingIntent)
            .addAction(R.drawable.ic_power, getString(R.string.action_stop), stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun observeConfigChanges() {
        serviceScope.launch {
            configManager.haConfigFlow.collectLatest { haConfig ->
                haClient.updateConfig(haConfig)
                haWebSocket.updateConfig(haConfig)
            }
        }

        serviceScope.launch {
            configManager.buttonsFlow.collectLatest { buttons ->
                overlayView?.updateButtons(buttons)
                val entityIds = mutableListOf<String>()
                for (btn in buttons) {
                    if (btn.entityId.isNotBlank()) entityIds.add(btn.entityId)
                    btn.guardSensorEntityId?.let { if (it.isNotBlank()) entityIds.add(it) }
                    btn.targetLockEntityId?.let { if (it.isNotBlank()) entityIds.add(it) }
                }
                pollingManager.updateTrackedEntities(entityIds.distinct(), configManager.getHaConfig().pollIntervalSeconds)
            }
        }

        serviceScope.launch {
            configManager.settingsFlow.collectLatest { settings ->
                overlayView?.updateSettings(settings)
                repositionOverlay(settings)
            }
        }
    }

    private fun observeFoldAndDisplayState() {
        serviceScope.launch {
            foldStateDetector.isCoverDisplayActive.collectLatest { isActive ->
                Log.d(TAG, "isCoverDisplayActive changed: $isActive")
                if (isActive) {
                    haWebSocket.resume()
                    pollingManager.startPolling()
                } else {
                    haWebSocket.pause()
                    pollingManager.stopPolling()
                }
                updateOverlayVisibility()
            }
        }

        serviceScope.launch {
            foldStateDetector.foldState.collectLatest { foldState ->
                Log.d(TAG, "foldState changed: $foldState")
                updateOverlayVisibility()
            }
        }
    }

    private fun observeNetworkStateUpdates() {
        serviceScope.launch {
            haWebSocket.stateUpdates.collect { state ->
                cachedEntityStates[state.entityId] = state
                overlayView?.updateEntityState(state.entityId, state)
            }
        }

        serviceScope.launch {
            pollingManager.polledStateUpdates.collect { state ->
                cachedEntityStates[state.entityId] = state
                overlayView?.updateEntityState(state.entityId, state)
            }
        }
    }

    /**
     * Determines whether the overlay should be attached to the target display.
     */
    private fun updateOverlayVisibility() {
        val settings = configManager.getOverlaySettings()
        if (!settings.isServiceEnabled) {
            detachOverlay()
            return
        }

        val isCoverActive = foldStateDetector.isCoverDisplayActive.value
        val isFolded = foldStateDetector.foldState.value == DeviceFoldState.FOLDED

        val shouldShow = isForcedPreview || (isCoverActive && isFolded)

        Log.d(TAG, "updateOverlayVisibility: shouldShow=$shouldShow, isCoverActive=$isCoverActive, isFolded=$isFolded, isForcedPreview=$isForcedPreview")

        if (shouldShow) {
            attachOverlay(settings)
        } else {
            detachOverlay()
        }
    }

    private fun attachOverlay(settings: OverlaySettings) {
        if (isOverlayAttached && overlayView != null) {
            return
        }

        val targetDisplay = coverDisplayManager.resolveTargetDisplay(settings)
        if (targetDisplay == null || (!isForcedPreview && targetDisplay.displayId == Display.DEFAULT_DISPLAY)) {
            Log.i(TAG, "Not attaching overlay: strictly dedicated to cover screen (targetDisplayId=${targetDisplay?.displayId})")
            return
        }

        try {
            val displayContext = coverDisplayManager.createDisplayContextFor(targetDisplay)
            currentWindowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val buttons = configManager.getButtons()
            overlayView = CoverOverlayView(
                context = displayContext,
                settings = settings,
                buttons = buttons,
                onButtonClick = { button -> handleButtonClick(button) },
                onPositionChanged = { newX, newY -> handleDragPositionChanged(newX, newY) }
            )

            windowLayoutParams = createLayoutParams(settings, displayContext)
            currentWindowManager?.addView(overlayView, windowLayoutParams)
            isOverlayAttached = true
            Log.i(TAG, "Overlay successfully attached to display #${targetDisplay.displayId} (${targetDisplay.name})")

            // Initial state poll for all buttons
            serviceScope.launch {
                for (button in buttons) {
                    if (button.entityId.isNotBlank()) {
                        when (val res = haClient.fetchEntityState(button.entityId)) {
                            is HaResult.Success -> overlayView?.updateEntityState(button.entityId, res.data)
                            else -> Unit
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay window", e)
            isOverlayAttached = false
        }
    }

    private fun detachOverlay() {
        if (!isOverlayAttached && overlayView == null) return

        try {
            if (overlayView != null && currentWindowManager != null) {
                currentWindowManager?.removeViewImmediate(overlayView)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing overlay view", e)
        } finally {
            overlayView = null
            currentWindowManager = null
            isOverlayAttached = false
            Log.i(TAG, "Overlay detached")
        }
    }

    private fun repositionOverlay(settings: OverlaySettings) {
        val view = overlayView ?: return
        val wm = currentWindowManager ?: return
        val params = windowLayoutParams ?: return

        applyPositionToLayoutParams(params, settings, view.context)
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Error updating window layout params", e)
        }
    }

    private fun createLayoutParams(settings: OverlaySettings, context: Context): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        applyPositionToLayoutParams(params, settings, context)
        return params
    }

    private fun applyPositionToLayoutParams(
        params: WindowManager.LayoutParams,
        settings: OverlaySettings,
        context: Context
    ) {
        val margin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            16f,
            context.resources.displayMetrics
        ).toInt()

        when (settings.dockPosition) {
            DockPosition.TOP_LEFT -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = margin
                params.y = margin
            }
            DockPosition.TOP_CENTER -> {
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.x = 0
                params.y = margin
            }
            DockPosition.TOP_RIGHT -> {
                params.gravity = Gravity.TOP or Gravity.END
                params.x = margin
                params.y = margin
            }
            DockPosition.BOTTOM_LEFT -> {
                params.gravity = Gravity.BOTTOM or Gravity.START
                params.x = margin
                params.y = margin
            }
            DockPosition.BOTTOM_CENTER -> {
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.x = 0
                params.y = margin
            }
            DockPosition.BOTTOM_RIGHT -> {
                params.gravity = Gravity.BOTTOM or Gravity.END
                params.x = margin
                params.y = margin
            }
            DockPosition.CENTER_LEFT -> {
                params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                params.x = margin
                params.y = 0
            }
            DockPosition.CENTER_RIGHT -> {
                params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                params.x = margin
                params.y = 0
            }
            DockPosition.CUSTOM -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = settings.customX
                params.y = settings.customY
            }
        }
    }

    private fun handleDragPositionChanged(newX: Int, newY: Int) {
        val view = overlayView ?: return
        val wm = currentWindowManager ?: return
        val params = windowLayoutParams ?: return

        params.gravity = Gravity.TOP or Gravity.START
        params.x = newX
        params.y = newY
        try {
            wm.updateViewLayout(view, params)
            configManager.updateCustomPosition(newX, newY)
        } catch (e: Exception) {
            Log.w(TAG, "Error moving overlay via drag", e)
        }
    }

    private fun handleButtonClick(button: OverlayButtonConfig) {
        val now = System.currentTimeMillis()
        val pendingExpiry = confirmationPendingButtons[button.id] ?: 0L

        // Check if button is currently in an active confirmation window
        if (now < pendingExpiry) {
            Log.i(TAG, "Confirmation tap received within window for '${button.label}'. Proceeding with execution.")
            confirmationPendingButtons.remove(button.id)
            executeServiceCall(button)
            return
        }

        // Check 1: Guard Sensor Check (e.g. Hallway optical sensor for dog safety)
        if (!button.guardSensorEntityId.isNullOrBlank()) {
            val guardSensorId = button.guardSensorEntityId
            serviceScope.launch {
                val sensorState = cachedEntityStates[guardSensorId]?.state ?: withContext(Dispatchers.IO) {
                    val res = haClient.fetchEntityState(guardSensorId)
                    if (res is HaResult.Success) {
                        cachedEntityStates[guardSensorId] = res.data
                        res.data.state
                    } else {
                        "off"
                    }
                }

                if (sensorState.equals(button.guardTriggerState, ignoreCase = true)) {
                    Log.w(TAG, "Guard sensor '$guardSensorId' is '$sensorState'! Triggering warning vibration & ${button.guardConfirmationWindowMs}ms confirmation window.")
                    confirmationPendingButtons[button.id] = System.currentTimeMillis() + button.guardConfirmationWindowMs
                    overlayView?.triggerWarningVibration()
                    overlayView?.flashButtonConfirmation(button.id, button.guardConfirmationWindowMs)
                    return@launch
                }

                // Sensor is safe -> check lock guard
                checkLockGuardAndExecute(button)
            }
            return
        }

        checkLockGuardAndExecute(button)
    }

    private fun checkLockGuardAndExecute(button: OverlayButtonConfig) {
        // Check 2: Locked State Confirmation Guard (e.g. Unlatch door when locked)
        if (button.requireConfirmationWhenLocked) {
            val lockEntityId = button.targetLockEntityId?.ifBlank { null } ?: button.entityId
            serviceScope.launch {
                val lockState = cachedEntityStates[lockEntityId]?.state ?: withContext(Dispatchers.IO) {
                    val res = haClient.fetchEntityState(lockEntityId)
                    if (res is HaResult.Success) {
                        cachedEntityStates[lockEntityId] = res.data
                        res.data.state
                    } else {
                        "unlocked"
                    }
                }

                if (lockState.equals("locked", ignoreCase = true)) {
                    val confirmWindowMs = 8000L // 8 seconds confirmation window
                    Log.w(TAG, "Lock entity '$lockEntityId' is LOCKED! Requiring confirmation tap before unlatching.")
                    confirmationPendingButtons[button.id] = System.currentTimeMillis() + confirmWindowMs
                    overlayView?.triggerWarningVibration()
                    overlayView?.flashButtonConfirmation(button.id, confirmWindowMs)
                    return@launch
                }

                // Door is already unlocked -> proceed immediately
                executeServiceCall(button)
            }
            return
        }

        executeServiceCall(button)
    }

    private fun executeServiceCall(button: OverlayButtonConfig) {
        Log.i(TAG, "Executing HA service call: ${button.label} (${button.domain}.${button.service} -> ${button.entityId})")
        overlayView?.setButtonLoading(button.id, true)

        serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                haClient.callService(
                    domain = button.domain,
                    service = button.service,
                    entityId = button.entityId,
                    serviceDataJson = button.serviceDataJson
                )
            }

            overlayView?.setButtonLoading(button.id, false)

            when (result) {
                is HaResult.Success -> {
                    Log.d(TAG, "Service call success for ${button.entityId}")
                    overlayView?.flashButtonSuccess(button.id)
                    val matchingState = result.data.firstOrNull { it.entityId == button.entityId }
                    if (matchingState != null) {
                        cachedEntityStates[button.entityId] = matchingState
                        overlayView?.updateEntityState(button.entityId, matchingState)
                    } else {
                        val stateRes = haClient.fetchEntityState(button.entityId)
                        if (stateRes is HaResult.Success) {
                            cachedEntityStates[button.entityId] = stateRes.data
                            overlayView?.updateEntityState(button.entityId, stateRes.data)
                        }
                    }
                }
                is HaResult.Error -> {
                    Log.e(TAG, "Service call failed: ${result.message}")
                    overlayView?.flashButtonError(button.id)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CoverOverlayService onDestroy")
        detachOverlay()
        coverDisplayManager.stop()
        foldStateDetector.stop()
        haWebSocket.stop()
        pollingManager.stopPolling()
        serviceJob.cancel()
    }

    companion object {
        private const val TAG = "CoverOverlayService"
        const val CHANNEL_ID = "ha_cover_overlay_channel"
        const val NOTIFICATION_ID = 9481

        const val ACTION_START = "com.haoverlay.coverscreen.ACTION_START"
        const val ACTION_STOP = "com.haoverlay.coverscreen.ACTION_STOP"
        const val ACTION_REFRESH_CONFIG = "com.haoverlay.coverscreen.ACTION_REFRESH_CONFIG"
        const val ACTION_TEST_OVERLAY = "com.haoverlay.coverscreen.ACTION_TEST_OVERLAY"
        const val EXTRA_FORCED_PREVIEW = "extra_forced_preview"

        fun start(context: Context) {
            val intent = Intent(context, CoverOverlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CoverOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun refresh(context: Context) {
            val intent = Intent(context, CoverOverlayService::class.java).apply {
                action = ACTION_REFRESH_CONFIG
            }
            context.startService(intent)
        }

        fun toggleTestPreview(context: Context, enabled: Boolean) {
            val intent = Intent(context, CoverOverlayService::class.java).apply {
                action = ACTION_TEST_OVERLAY
                putExtra(EXTRA_FORCED_PREVIEW, enabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
