package com.haoverlay.coverscreen.service.display

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.haoverlay.coverscreen.data.model.DisplayInfo
import com.haoverlay.coverscreen.data.model.OverlaySettings
import com.haoverlay.coverscreen.data.model.TargetDisplayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Discovers and tracks physical/virtual displays on Samsung Galaxy Z Flip and other foldables.
 * Accurately detects the sub/cover screen dynamically without hardcoded indices.
 */
class CoverDisplayManager(private val context: Context) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val _displaysFlow = MutableStateFlow<List<DisplayInfo>>(emptyList())
    val displaysFlow: StateFlow<List<DisplayInfo>> = _displaysFlow.asStateFlow()

    private val _activeCoverDisplay = MutableStateFlow<Display?>(null)
    val activeCoverDisplay: StateFlow<Display?> = _activeCoverDisplay.asStateFlow()

    /**
     * Display enumeration is cached briefly.
     *
     * [Display] objects re-read their own state from DisplayManagerGlobal on every getter, so
     * caching the *list* never serves a stale power state -- it only avoids re-asking which
     * displays exist, which changes far less often than DisplayListener fires.
     */
    private var cachedDisplays: List<Display> = emptyList()
    private var cachedAtUptimeMs: Long = 0L

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshDisplays() }

    /**
     * Coalesces DisplayListener callbacks.
     *
     * onDisplayChanged fires on every refresh-rate and brightness change. On a 120 Hz adaptive
     * panel that is a storm: measured at 20 callbacks in 50 seconds on an idle, screen-off
     * device, each one previously triggering a full re-enumeration.
     */
    private fun scheduleRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            invalidateCache()
            scheduleRefresh()
        }

        override fun onDisplayRemoved(displayId: Int) {
            invalidateCache()
            scheduleRefresh()
        }

        override fun onDisplayChanged(displayId: Int) {
            // Deliberately does NOT invalidate the cache: a changed display is still the same
            // display, and Display objects report fresh state from the cached instances.
            scheduleRefresh()
        }
    }

    private fun invalidateCache() {
        cachedAtUptimeMs = 0L
    }

    fun start() {
        displayManager.registerDisplayListener(displayListener, null)
        refreshDisplays()
    }

    fun stop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering display listener", e)
        }
    }

    fun getAllDisplays(): List<Display> {
        val now = SystemClock.uptimeMillis()
        if (cachedDisplays.isNotEmpty() && now - cachedAtUptimeMs < DISPLAY_CACHE_TTL_MS) {
            return cachedDisplays
        }

        val list = mutableListOf<Display>()
        displayManager.getDisplays(null)?.let { list.addAll(it) }
        val standard = list.distinctBy { it.displayId }

        // Samsung does not always surface the cover panel through getDisplays() while it is
        // powered down, so fall back to probing low logical display ids. This is a fallback, not
        // a default: the probe previously ran unconditionally on every enumeration, which put
        // eight extra binder calls on a path that fires dozens of times a minute.
        val needsProbe = standard.none { it.displayId != Display.DEFAULT_DISPLAY }
        if (needsProbe) {
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.let { list.addAll(it) }
            for (id in 0 until DISPLAY_ID_PROBE_LIMIT) {
                displayManager.getDisplay(id)?.let { list.add(it) }
            }
        }

        val resolved = list.distinctBy { it.displayId }
        cachedDisplays = resolved
        cachedAtUptimeMs = now
        return resolved
    }

    fun refreshDisplays() {
        val displays = getAllDisplays()
        val infoList = mutableListOf<DisplayInfo>()
        var foundCover: Display? = null

        for (display in displays) {
            val (isCover, reason) = evaluateIfCoverDisplay(display, displays.size)
            val size = getDisplayRealSize(display)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getMetrics(metrics)

            val info = DisplayInfo(
                displayId = display.displayId,
                name = display.name ?: "Display #${display.displayId}",
                width = size.x,
                height = size.y,
                densityDpi = metrics.densityDpi,
                flags = display.flags,
                state = display.state,
                isCoverScreen = isCover,
                detectionReason = reason
            )
            infoList.add(info)

            if (isCover && foundCover == null) {
                foundCover = display
            }
        }

        _displaysFlow.value = infoList
        // Display does not implement equals, so assigning an identical instance would still
        // re-emit; compare by id to keep this quiet when nothing actually moved.
        if (_activeCoverDisplay.value?.displayId != foundCover?.displayId) {
            _activeCoverDisplay.value = foundCover
            Log.i(TAG, "Cover screen resolved: ${foundCover?.name} (ID: ${foundCover?.displayId})")
        }
    }

    /**
     * Resolves the target display according to user settings.
     * Strictly targets the Flip cover display (returns null rather than falling back to main screen).
     */
    fun resolveTargetDisplay(settings: OverlaySettings): Display? {
        val displays = getAllDisplays()
        if (displays.isEmpty()) return null

        return when (settings.targetDisplayMode) {
            TargetDisplayMode.AUTO_DETECT_COVER -> {
                val cover = findCoverDisplay()
                if (cover != null) {
                    cover
                } else {
                    Log.w(TAG, "Cover display not found. Will not attach to main display.")
                    null
                }
            }
            TargetDisplayMode.DEFAULT_DISPLAY -> {
                displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: displays.firstOrNull()
            }
            TargetDisplayMode.SPECIFIC_ID -> {
                if (settings.targetDisplayId >= 0) {
                    displayManager.getDisplay(settings.targetDisplayId)
                } else {
                    null
                }
            }
        }
    }

    /**
     * Finds the cover display using multi-heuristic rules.
     */
    fun findCoverDisplay(): Display? {
        val displays = getAllDisplays()
        for (display in displays) {
            val (isCover, reason) = evaluateIfCoverDisplay(display, displays.size)
            if (isCover) {
                Log.d(TAG, "Cover display matched: #${display.displayId} (${display.name}) -> $reason")
                return display
            }
        }
        return null
    }

    /**
     * Creates a specialized Context bound to the target display for WindowManager operations.
     */
    fun createDisplayContextFor(targetDisplay: Display): Context {
        val displayContext = context.createDisplayContext(targetDisplay)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                displayContext.createWindowContext(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } catch (e: Exception) {
                Log.w(TAG, "createWindowContext failed, falling back to displayContext", e)
                displayContext
            }
        } else {
            displayContext
        }
    }

    /**
     * Heuristic evaluation of whether a display is the cover/sub-display.
     */
    fun evaluateIfCoverDisplay(display: Display, totalDisplays: Int): Pair<Boolean, String> {
        val displayId = display.displayId
        val rawName = display.name ?: ""
        val name = rawName.lowercase()
        val size = getDisplayRealSize(display)
        val w = size.x
        val h = size.y
        val maxDim = max(w, h)
        val minDim = min(w, h)
        val aspectRatio = if (minDim > 0) maxDim.toFloat() / minDim.toFloat() else 1.0f

        if (displayId == Display.DEFAULT_DISPLAY) {
            return Pair(false, "Default / Main internal display")
        }

        // Rule 0 -- hard reject anything that is, or might be, an external or remote surface.
        // Casting to a TV, plugging in HDMI/DeX or enabling a simulated display in developer
        // options must never move door-unlock buttons onto someone else's screen.
        EXTERNAL_NAME_KEYWORDS.firstOrNull { name.contains(it) }?.let { keyword ->
            return Pair(false, "Rejected: name contains external-display keyword '$keyword' ('$rawName')")
        }
        if ((display.flags and Display.FLAG_PRIVATE) != 0) {
            return Pair(false, "Rejected: FLAG_PRIVATE virtual/simulated display (#$displayId)")
        }

        // Rule 1 -- the display name explicitly identifies a Samsung cover / sub display.
        COVER_NAME_KEYWORDS.firstOrNull { name.contains(it) }?.let {
            return Pair(true, "Matched cover display keyword in name: '$rawName'")
        }

        // Rule 2 -- resolution signature of a known Z Flip cover screen.
        //   Flip7   948x1048 (aspect ~1.11)
        //   Flip6/5 720x748  (aspect ~1.04)
        //   Flip4/3 260x512  (aspect ~1.97)
        val isLargeFlipCover = minDim in 700..1200 && maxDim in 700..1200 && aspectRatio <= 1.35f
        val isSmallFlipCover = minDim in 240..320 && maxDim in 480..600
        if (isLargeFlipCover || isSmallFlipCover) {
            return Pair(true, "Resolution ${w}x${h} (aspect $aspectRatio) matches a Z Flip sub-display")
        }

        // Deliberately no catch-all. This used to end with "any secondary display wins", plus a
        // FLAG_PRESENTATION rule -- and FLAG_PRESENTATION is precisely what external displays
        // carry. Unknown hardware now returns false; the user pins the display by hand from the
        // Displays tab (TargetDisplayMode.SPECIFIC_ID) instead of the app guessing wrong.
        return Pair(false, "No cover-display signature (${w}x${h}, aspect $aspectRatio, $totalDisplays displays)")
    }

    private fun getDisplayRealSize(display: Display): Point {
        val point = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        return point
    }

    companion object {
        private const val TAG = "CoverDisplayManager"

        /** Upper bound for the direct getDisplay(id) probe; foldables use very low ids. */
        private const val DISPLAY_ID_PROBE_LIMIT = 8

        /** How long an enumeration stays valid. Display objects still report live state. */
        private const val DISPLAY_CACHE_TTL_MS = 2_000L

        /** Coalescing window for DisplayListener callbacks. */
        private const val REFRESH_DEBOUNCE_MS = 300L

        /** Substrings that positively identify a foldable's inner cover/sub display. */
        private val COVER_NAME_KEYWORDS = listOf("sub", "cover", "flip", "flex")

        /**
         * Substrings that disqualify a display outright. Note "external" used to live in the
         * *accept* list, which meant a display literally named "external" was treated as the
         * cover screen.
         */
        private val EXTERNAL_NAME_KEYWORDS = listOf(
            "cast", "chromecast", "miracast", "hdmi", "displayport", "wifi", "wi-fi",
            "wireless", "overlay", "virtual", "simulated", "remote", "external",
            "dex", "smartview", "smart view"
        )
    }
}
