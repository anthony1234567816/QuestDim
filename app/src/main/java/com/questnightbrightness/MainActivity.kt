package com.questnightbrightness

import android.app.Activity
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** A Quest panel app that only writes Settings.System.SCREEN_BRIGHTNESS. */
class MainActivity : Activity() {
    companion object {
        private const val MIN_BRIGHTNESS = 1
        private const val DIM_BRIGHTNESS = 10
        private const val META_MINIMUM = 45
        private const val PREFS = "brightness_restore_state"
        private const val KEY_HAS_PREVIOUS = "has_previous"
        private const val KEY_PREVIOUS_BRIGHTNESS = "previous_brightness"
        private const val KEY_PREVIOUS_MODE = "previous_mode"
    }

    private enum class Screen { PERMISSION, MAIN }

    private var displayedScreen: Screen? = null
    private var isRefreshing = false
    private var isSliderAnimating = false
    private var sliderAnimator: ValueAnimator? = null
    private lateinit var currentValue: TextView
    private lateinit var slider: BrightnessSliderView
    private lateinit var restoreButton: Button
    private lateinit var presetNight: LinearLayout
    private lateinit var presetDim: LinearLayout
    private lateinit var presetMeta: LinearLayout
    private lateinit var presetNightIcon: ImageView
    private lateinit var presetDimIcon: ImageView
    private lateinit var presetMetaIcon: ImageView

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = showAppropriateScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showAppropriateScreen()
        registerBrightnessObserver()
    }

    override fun onResume() {
        super.onResume()
        showAppropriateScreen()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(brightnessObserver)
        super.onDestroy()
    }

    private fun registerBrightnessObserver() {
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, brightnessObserver
        )
    }

    /** A re-check here changes the permission screen to the main screen after returning from Settings. */
    private fun showAppropriateScreen() {
        if (Settings.System.canWrite(this)) {
            if (displayedScreen != Screen.MAIN) {
                displayedScreen = Screen.MAIN
                setContentView(createMainScreen())
            }
            refreshMainUi()
        } else if (displayedScreen != Screen.PERMISSION) {
            displayedScreen = Screen.PERMISSION
            setContentView(createPermissionScreen())
        }
    }

    private fun createPermissionScreen(): View = scrollContainer { content ->
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(dp(34), dp(34), dp(34), dp(34))
        content.addView(space(24))
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.bg_icon_circle)
            setPadding(dp(27), dp(27), dp(27), dp(27))
        }, LinearLayout.LayoutParams(dp(108), dp(108)))
        content.addView(space(30))
        content.addView(headline(getString(R.string.permission_title), 32).apply { gravity = Gravity.CENTER })
        content.addView(space(12))
        content.addView(copy(getString(R.string.permission_description), 18).apply { gravity = Gravity.CENTER })
        content.addView(space(28))
        content.addView(permissionStepsCard())
        content.addView(space(28))
        content.addView(primaryButton(getString(R.string.open_settings)) { openWriteSettings() })
        content.addView(space(24))
    }

    private fun createMainScreen(): View = scrollContainer { content ->
        content.setPadding(dp(34), dp(30), dp(34), dp(34))
        content.addView(headline(getString(R.string.app_name), 36))
        content.addView(space(8))
        content.addView(copy(getString(R.string.app_subtitle), 19))
        content.addView(space(36))

        currentValue = headline("", 76).apply { gravity = Gravity.CENTER }
        content.addView(currentValue, matchWidth())
        content.addView(copy(getString(R.string.brightness_level), 20).apply { gravity = Gravity.CENTER })
        content.addView(space(28))

        slider = BrightnessSliderView(this).apply {
            setRange(MIN_BRIGHTNESS, META_MINIMUM)
            setValue(META_MINIMUM)
            contentDescription = getString(R.string.brightness_slider_description)
            onValueChanged = { value, fromUser ->
                if (fromUser) applyBrightness(value)
            }
            onStartTracking = { sliderAnimator?.cancel() }
        }
        content.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        content.addView(brightnessRangeLabels())
        content.addView(space(32))
        content.addView(presetRow())
        content.addView(space(28))
        content.addView(systemAccessCard())
        content.addView(space(14))
        restoreButton = secondaryButton(getString(R.string.restore_previous_brightness)) {
            restorePreviousBrightness()
        }
        content.addView(restoreButton)
        content.addView(space(20))
    }

    private fun brightnessRangeLabels(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(rangeLabel(MIN_BRIGHTNESS, R.string.darkest, Gravity.START), weighted())
        addView(rangeLabel(META_MINIMUM, R.string.meta_minimum, Gravity.END), weighted())
    }

    private fun rangeLabel(value: Int, label: Int, gravity: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
        addView(copy(value.toString(), 22, getColor(R.color.text_primary)).apply { this.gravity = gravity })
        addView(copy(getString(label), 15).apply { this.gravity = gravity })
    }

    private fun presetRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        presetNight = presetCard(R.drawable.ic_moon, R.string.preset_night, MIN_BRIGHTNESS).also {
            presetNightIcon = it.getChildAt(0) as ImageView
            addView(it, weighted(marginEnd = 8))
        }
        presetDim = presetCard(R.drawable.ic_sun, R.string.preset_dim, DIM_BRIGHTNESS).also {
            presetDimIcon = it.getChildAt(0) as ImageView
            addView(it, weighted(marginStart = 4, marginEnd = 4))
        }
        presetMeta = presetCard(R.drawable.ic_sun, R.string.preset_meta, META_MINIMUM).also {
            presetMetaIcon = it.getChildAt(0) as ImageView
            addView(it, weighted(marginStart = 8))
        }
    }

    private fun presetCard(icon: Int, title: Int, value: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(148)
        isClickable = true
        isFocusable = true
        contentDescription = getString(title)
        setPadding(dp(10), dp(16), dp(10), dp(14))
        setBackgroundResource(R.drawable.bg_preset)
        setOnClickListener { applyPresetBrightness(value) }
        attachPressScale(this)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(space(8))
        addView(copy(getString(title), 20, getColor(R.color.text_primary)).apply { gravity = Gravity.CENTER })
        addView(space(2))
        addView(copy(value.toString(), 17).apply { gravity = Gravity.CENTER })
    }

    private fun systemAccessCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(94)
        setPadding(dp(22), dp(18), dp(22), dp(18))
        setBackgroundResource(R.drawable.bg_card)
        isClickable = true
        isFocusable = true
        setOnClickListener { openWriteSettings() }
        attachPressScale(this)
        addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_status_dot) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(18) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(copy(getString(R.string.system_access), 18, getColor(R.color.text_primary)))
            addView(space(2))
            addView(copy(getString(R.string.allowed), 16, getColor(R.color.success)))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(16) })
    }

    private fun permissionStepsCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(20))
        setBackgroundResource(R.drawable.bg_card)
        addView(permissionStep(1, R.string.permission_step_1))
        addView(space(16))
        addView(permissionStep(2, R.string.permission_step_2))
        addView(space(16))
        addView(permissionStep(3, R.string.permission_step_3))
    }

    private fun permissionStep(number: Int, description: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(copy(number.toString(), 15, getColor(R.color.text_primary)).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_step_number)
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
        addView(copy(getString(description), 16, getColor(R.color.text_primary)), weighted())
    }

    // Existing brightness and restoration behavior is intentionally retained below.
    private fun applyBrightness(value: Int) {
        if (value !in MIN_BRIGHTNESS..META_MINIMUM || !Settings.System.canWrite(this)) return
        savePreviousStateIfNeeded()
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        refreshMainUi()
    }

    /** Apply system brightness immediately, while only the visual slider moves smoothly. */
    private fun applyPresetBrightness(value: Int) {
        val startValue = slider.value
        sliderAnimator?.cancel()
        if (startValue == value) {
            applyBrightness(value)
            return
        }

        isSliderAnimating = true
        applyBrightness(value)
        sliderAnimator = ValueAnimator.ofInt(startValue, value).apply {
            duration = 260L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { slider.setValue(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isSliderAnimating = false
                    val actual = readBrightness()
                    if (actual in MIN_BRIGHTNESS..META_MINIMUM) slider.setValue(actual)
                }
            })
            start()
        }
    }

    private fun restorePreviousBrightness() {
        if (!Settings.System.canWrite(this)) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_PREVIOUS, false)) return
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            prefs.getInt(KEY_PREVIOUS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL))
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS,
            prefs.getInt(KEY_PREVIOUS_BRIGHTNESS, META_MINIMUM))
        prefs.edit().clear().apply()
        refreshMainUi()
    }

    private fun savePreviousStateIfNeeded() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HAS_PREVIOUS, false)) return
        prefs.edit()
            .putBoolean(KEY_HAS_PREVIOUS, true)
            .putInt(KEY_PREVIOUS_BRIGHTNESS, readBrightness())
            .putInt(KEY_PREVIOUS_MODE, readBrightnessMode())
            .apply()
    }

    private fun refreshMainUi() {
        if (displayedScreen != Screen.MAIN || isRefreshing) return
        isRefreshing = true
        val value = readBrightness()
        val valueText = value.toString()
        if (currentValue.text.toString() != valueText) {
            currentValue.text = valueText
            currentValue.alpha = 0.58f
            currentValue.translationY = dp(3).toFloat()
            currentValue.animate().alpha(1f).translationY(0f).setDuration(150L).start()
        }
        if (!isSliderAnimating && value in MIN_BRIGHTNESS..META_MINIMUM) slider.setValue(value)
        restoreButton.isEnabled = hasPreviousState()
        updatePresetSelection(presetNight, presetNightIcon, value == MIN_BRIGHTNESS)
        updatePresetSelection(presetDim, presetDimIcon, value == DIM_BRIGHTNESS)
        updatePresetSelection(presetMeta, presetMetaIcon, value == META_MINIMUM)
        isRefreshing = false
    }

    private fun updatePresetSelection(card: LinearLayout, icon: ImageView, selected: Boolean) {
        if (card.isSelected == selected) return
        val oldBackground = getDrawable(if (card.isSelected) R.drawable.bg_preset_selected else R.drawable.bg_preset)
        val newBackground = getDrawable(if (selected) R.drawable.bg_preset_selected else R.drawable.bg_preset)
        card.background = TransitionDrawable(arrayOf(oldBackground, newBackground)).apply {
            isCrossFadeEnabled = true
            startTransition(150)
        }
        val fromColor = getColor(if (card.isSelected) R.color.accent else R.color.text_secondary)
        val toColor = getColor(if (selected) R.color.accent else R.color.text_secondary)
        ValueAnimator.ofArgb(fromColor, toColor).apply {
            duration = 150L
            addUpdateListener { icon.setColorFilter(it.animatedValue as Int) }
            start()
        }
        card.isSelected = selected
    }

    private fun hasPreviousState(): Boolean = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_HAS_PREVIOUS, false)

    private fun readBrightness(): Int = Settings.System.getInt(contentResolver,
        Settings.System.SCREEN_BRIGHTNESS, META_MINIMUM)

    private fun readBrightnessMode(): Int = Settings.System.getInt(contentResolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

    private fun openWriteSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            // Some Horizon OS builds do not expose this page; the README documents the ADB fallback.
        }
    }

    private fun scrollContainer(buildContent: (LinearLayout) -> Unit): ScrollView = ScrollView(this).apply {
        setBackgroundColor(getColor(R.color.background))
        isFillViewport = true
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            buildContent(this)
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun headline(text: String, size: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(getColor(R.color.text_primary))
        includeFontPadding = false
    }

    private fun copy(text: String, size: Int, color: Int = getColor(R.color.text_secondary)): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        includeFontPadding = false
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 18f
        isAllCaps = false
        setTextColor(getColor(R.color.button_primary_text))
        setBackgroundResource(R.drawable.bg_button_primary)
        minimumHeight = dp(64)
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 18f
        isAllCaps = false
        setTextColor(getColor(R.color.text_primary))
        setBackgroundResource(R.drawable.bg_button_secondary)
        minimumHeight = dp(64)
        setOnClickListener { onClick() }
        attachPressScale(this)
    }

    private fun attachPressScale(view: View) {
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touched.animate()
                    .scaleX(0.975f).scaleY(0.975f).setDuration(75L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touched.animate()
                    .scaleX(1f).scaleY(1f).setDuration(140L).start()
            }
            false
        }
    }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weighted(marginStart: Int = 0, marginEnd: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            this.marginStart = dp(marginStart)
            this.marginEnd = dp(marginEnd)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
