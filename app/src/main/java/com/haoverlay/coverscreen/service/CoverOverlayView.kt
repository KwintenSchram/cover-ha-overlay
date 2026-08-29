package com.haoverlay.coverscreen.service

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.haoverlay.coverscreen.R
import com.haoverlay.coverscreen.data.model.BackgroundStyle
import com.haoverlay.coverscreen.data.model.HaEntityState
import com.haoverlay.coverscreen.data.model.IconSize
import com.haoverlay.coverscreen.data.model.OverlayButtonConfig
import com.haoverlay.coverscreen.data.model.OverlayOrientation
import com.haoverlay.coverscreen.data.model.OverlaySettings
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom Floating View rendered on the cover screen WindowManager.
 * Renders the compact button cluster with full touch passthrough outside the bounds.
 */
@SuppressLint("ViewConstructor")
class CoverOverlayView(
    context: Context,
    private var settings: OverlaySettings,
    private var buttons: List<OverlayButtonConfig>,
    private val onButtonClick: (OverlayButtonConfig) -> Unit,
    private val onPositionChanged: (newX: Int, newY: Int) -> Unit
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val entityStates = ConcurrentHashMap<String, HaEntityState>()
    private val buttonViews = mutableMapOf<String, ButtonViewHolder>()
    private val buttonCooldowns = ConcurrentHashMap<String, Long>()

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private lateinit var containerLayout: LinearLayout

    init {
        setupLayout()
    }

    private fun setupLayout() {
        removeAllViews()
        buttonViews.clear()

        containerLayout = LinearLayout(context).apply {
            orientation = if (settings.orientation == OverlayOrientation.HORIZONTAL) {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
            gravity = Gravity.CENTER
            val pad = dpToPx(6)
            setPadding(pad, pad, pad, pad)
            background = createContainerBackground()
        }

        // Add Drag Handle if custom dragging is enabled
        if (settings.allowDragReposition) {
            val dragHandle = createDragHandle()
            containerLayout.addView(dragHandle)
        }

        // Add Button Views
        for (buttonConfig in buttons.sortedBy { it.order }) {
            val holder = createButtonViewHolder(buttonConfig)
            buttonViews[buttonConfig.id] = holder
            containerLayout.addView(holder.rootView)
        }

        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(containerLayout, params)
    }

    fun updateSettings(newSettings: OverlaySettings) {
        this.settings = newSettings
        setupLayout()
        // Re-apply states to newly created views
        for ((entityId, state) in entityStates) {
            updateEntityState(entityId, state)
        }
    }

    fun updateButtons(newButtons: List<OverlayButtonConfig>) {
        this.buttons = newButtons
        setupLayout()
        for ((entityId, state) in entityStates) {
            updateEntityState(entityId, state)
        }
    }

    fun updateEntityState(entityId: String, state: HaEntityState) {
        entityStates[entityId] = state
        for (button in buttons) {
            if (button.entityId == entityId) {
                buttonViews[button.id]?.let { holder ->
                    updateButtonAppearance(button, holder, state)
                }
            }
        }
    }

    fun setButtonLoading(buttonId: String, isLoading: Boolean) {
        buttonViews[buttonId]?.let { holder ->
            holder.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            holder.iconView.alpha = if (isLoading) 0.3f else 1.0f
        }
    }

    fun flashButtonSuccess(buttonId: String) {
        buttonViews[buttonId]?.let { holder ->
            val anim = ObjectAnimator.ofPropertyValuesHolder(
                holder.iconContainer,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.25f, 1.0f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.25f, 1.0f)
            ).apply {
                duration = 300
                interpolator = AccelerateDecelerateInterpolator()
            }
            anim.start()
        }
    }

    fun flashButtonConfirmation(buttonId: String, timeoutMs: Long) {
        buttonViews[buttonId]?.let { holder ->
            holder.errorBadge.setImageResource(R.drawable.ic_shield)
            holder.errorBadge.setColorFilter(ContextCompat.getColor(context, R.color.state_on))
            holder.errorBadge.visibility = View.VISIBLE

            val pulse = ObjectAnimator.ofPropertyValuesHolder(
                holder.iconContainer,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.15f, 1.0f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.15f, 1.0f)
            ).apply {
                duration = 500
                repeatCount = (timeoutMs / 600).toInt().coerceIn(1, 10)
            }
            pulse.start()

            holder.labelView?.text = "Confirm?"
            holder.labelView?.setTextColor(ContextCompat.getColor(context, R.color.state_on))

            handler.postDelayed({
                holder.errorBadge.visibility = View.GONE
                holder.errorBadge.setImageResource(R.drawable.ic_error)
                holder.errorBadge.setColorFilter(ContextCompat.getColor(context, R.color.state_error))
                pulse.cancel()
                holder.iconContainer.scaleX = 1.0f
                holder.iconContainer.scaleY = 1.0f
                val originalButton = buttons.firstOrNull { it.id == buttonId }
                holder.labelView?.text = originalButton?.label ?: ""
                holder.labelView?.setTextColor(Color.WHITE)
            }, timeoutMs)
        }
    }

    fun triggerWarningVibration() {
        if (!settings.hapticFeedbackEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 140, 90, 200)
                val amplitudes = intArrayOf(0, 255, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 140, 90, 200), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error triggering warning vibration", e)
        }
    }

    fun flashButtonError(buttonId: String) {
        buttonViews[buttonId]?.let { holder ->
            holder.errorBadge.setImageResource(R.drawable.ic_error)
            holder.errorBadge.setColorFilter(ContextCompat.getColor(context, R.color.state_error))
            holder.errorBadge.visibility = View.VISIBLE
            val shake = ObjectAnimator.ofFloat(
                holder.iconContainer,
                View.TRANSLATION_X,
                0f, 8f, -8f, 6f, -6f, 0f
            ).apply {
                duration = 400
            }
            shake.start()

            handler.postDelayed({
                holder.errorBadge.visibility = View.GONE
            }, 2500)
        }
    }

    private fun createButtonViewHolder(button: OverlayButtonConfig): ButtonViewHolder {
        val sizeDp = settings.iconSize.dp
        val sizePx = dpToPx(sizeDp)
        val spacingPx = dpToPx(settings.iconSpacingDp / 2)

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(spacingPx, spacingPx, spacingPx, spacingPx)
            }
        }

        val iconContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER
            }
            background = createCircleRippleBackground()
            isClickable = true
            isFocusable = false
        }

        val iconView = ImageView(context).apply {
            val iconPad = dpToPx(sizeDp / 4)
            setPadding(iconPad, iconPad, iconPad, iconPad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setImageResource(getIconDrawableResource(button.iconName))
            setColorFilter(resolveInitialColor(button))
        }
        iconContainer.addView(iconView)

        val progressBar = ProgressBar(context).apply {
            val progSize = dpToPx(sizeDp / 2)
            layoutParams = FrameLayout.LayoutParams(progSize, progSize).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
            isIndeterminate = true
        }
        iconContainer.addView(progressBar)

        val errorBadge = ImageView(context).apply {
            val badgeSize = dpToPx(14)
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            setImageResource(R.drawable.ic_error)
            setColorFilter(ContextCompat.getColor(context, R.color.state_error))
            visibility = View.GONE
        }
        iconContainer.addView(errorBadge)

        rootLayout.addView(iconContainer)

        var labelView: TextView? = null
        if (settings.showButtonLabels && button.label.isNotBlank() && settings.iconSize != IconSize.COMPACT) {
            labelView = TextView(context).apply {
                text = button.label
                textSize = if (settings.iconSize == IconSize.EXTRA_LARGE) 10f else 9f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(2)
                }
            }
            rootLayout.addView(labelView)
        }

        val holder = ButtonViewHolder(rootLayout, iconContainer, iconView, progressBar, errorBadge, labelView)

        iconContainer.setOnClickListener {
            val now = System.currentTimeMillis()
            val lastTap = buttonCooldowns[button.id] ?: 0L
            if (now - lastTap >= settings.debounceDelayMs) {
                buttonCooldowns[button.id] = now
                triggerHaptic()
                onButtonClick(button)
            }
        }

        // Apply current state if available
        entityStates[button.entityId]?.let { state ->
            updateButtonAppearance(button, holder, state)
        }

        return holder
    }

    private fun updateButtonAppearance(
        button: OverlayButtonConfig,
        holder: ButtonViewHolder,
        state: HaEntityState
    ) {
        if (!button.showState) {
            val color = resolveCustomColor(button.customColorHex) ?: Color.WHITE
            holder.iconView.setColorFilter(color)
            return
        }

        when {
            state.isUnavailable -> {
                holder.iconView.setColorFilter(ContextCompat.getColor(context, R.color.state_error))
                holder.iconView.alpha = 0.5f
            }
            state.isOn -> {
                val activeColor = resolveCustomColor(button.customColorHex)
                    ?: ContextCompat.getColor(context, R.color.state_on)
                holder.iconView.setColorFilter(activeColor)
                holder.iconView.alpha = 1.0f
            }
            else -> {
                val offColor = ContextCompat.getColor(context, R.color.state_off)
                holder.iconView.setColorFilter(offColor)
                holder.iconView.alpha = 0.85f
            }
        }
    }

    private fun resolveInitialColor(button: OverlayButtonConfig): Int {
        return resolveCustomColor(button.customColorHex) ?: ContextCompat.getColor(context, R.color.state_on)
    }

    private fun resolveCustomColor(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            null
        }
    }

    private fun createDragHandle(): View {
        val handle = ImageView(context).apply {
            setImageResource(R.drawable.ic_drag_handle)
            setColorFilter(Color.argb(128, 255, 255, 255))
            val pad = dpToPx(4)
            setPadding(pad, pad, pad, pad)
            val size = dpToPx(24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
                if (settings.orientation == OverlayOrientation.HORIZONTAL) {
                    setMargins(dpToPx(4), 0, dpToPx(6), 0)
                } else {
                    setMargins(0, dpToPx(4), 0, dpToPx(6))
                }
            }
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f

        handle.setOnTouchListener { _, event ->
            val parentLayoutParams = layoutParams as? WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (parentLayoutParams != null) {
                        initialX = parentLayoutParams.x
                        initialY = parentLayoutParams.y
                    }
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (parentLayoutParams != null) {
                        val dx = (event.rawX - touchStartX).toInt()
                        val dy = (event.rawY - touchStartY).toInt()
                        val newX = initialX + dx
                        val newY = initialY + dy
                        onPositionChanged(newX, newY)
                    }
                    true
                }
                else -> false
            }
        }

        return handle
    }

    private fun createContainerBackground(): GradientDrawable {
        val cornerPx = dpToPx(settings.cornerRadiusDp).toFloat()
        val opacity = settings.backgroundOpacity.coerceIn(0.0f, 1.0f)
        val alpha = (opacity * 255).toInt()

        return GradientDrawable().apply {
            cornerRadius = cornerPx
            when (settings.backgroundStyle) {
                BackgroundStyle.PILL_DARK -> {
                    setColor(Color.argb(alpha, 15, 23, 42))
                    setStroke(dpToPx(1), Color.argb(40, 255, 255, 255))
                }
                BackgroundStyle.PILL_LIGHT -> {
                    setColor(Color.argb(alpha, 241, 245, 249))
                    setStroke(dpToPx(1), Color.argb(40, 0, 0, 0))
                }
                BackgroundStyle.MINIMAL -> {
                    setColor(Color.argb((alpha * 0.5f).toInt(), 30, 41, 59))
                    setStroke(dpToPx(1), Color.argb(30, 255, 255, 255))
                }
                BackgroundStyle.TRANSPARENT -> {
                    setColor(Color.TRANSPARENT)
                }
            }
        }
    }

    private fun createCircleRippleBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(45, 255, 255, 255))
        }
    }

    private fun triggerHaptic() {
        if (!settings.hapticFeedbackEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25)
            }
        } catch (e: Exception) {
            // Ignore haptic errors on unsupported hardware
        }
    }

    private fun getIconDrawableResource(iconName: String): Int {
        return when (iconName.lowercase()) {
            "lightbulb" -> R.drawable.ic_lightbulb
            "power" -> R.drawable.ic_power
            "lock" -> R.drawable.ic_lock
            "lock_open" -> R.drawable.ic_lock_open
            "door" -> R.drawable.ic_door
            "blinds" -> R.drawable.ic_blinds
            "thermostat" -> R.drawable.ic_thermostat
            "fan" -> R.drawable.ic_fan
            "sparkles" -> R.drawable.ic_sparkles
            "touch" -> R.drawable.ic_touch
            "code" -> R.drawable.ic_code
            "bolt" -> R.drawable.ic_bolt
            "music" -> R.drawable.ic_music
            "garage" -> R.drawable.ic_garage
            "camera" -> R.drawable.ic_camera
            "vacuum" -> R.drawable.ic_vacuum
            "shield" -> R.drawable.ic_shield
            "refresh" -> R.drawable.ic_refresh
            else -> R.drawable.ic_power
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    data class ButtonViewHolder(
        val rootView: View,
        val iconContainer: View,
        val iconView: ImageView,
        val progressBar: ProgressBar,
        val errorBadge: ImageView,
        val labelView: TextView?
    )

    companion object {
        private const val TAG = "CoverOverlayView"
    }
}
