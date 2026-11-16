package com.bambu.floatwidget.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.bambu.floatwidget.R
import com.bambu.floatwidget.data.PrefsManager
import com.bambu.floatwidget.data.PrinterUiState
import kotlin.math.hypot

class FloatingViewManager(
    private val context: Context,
    private val prefs: PrefsManager,
    private val onExitRequested: () -> Unit
) {
    private val themedContext = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_BambuFloat)
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val inflater: LayoutInflater = LayoutInflater.from(themedContext)

    // Capsule View & Elements
    private var capsuleView: View? = null
    private var tvCapsulePercent: TextView? = null
    private var tvCapsuleTime: TextView? = null
    private var tvCapsuleEta: TextView? = null
    private var vStatusDot: View? = null

    // Card View & Elements
    private var cardView: View? = null
    private var tvCardDeviceName: TextView? = null
    private var tvCardStatusBadge: TextView? = null
    private var tvCardModelName: TextView? = null
    private var pbCardProgress: ProgressBar? = null
    private var tvCardPercent: TextView? = null
    private var tvCardRemainingTime: TextView? = null
    private var tvCardEta: TextView? = null
    private var tvCardTemps: TextView? = null
    private var tvCardLayers: TextView? = null

    private var isExpanded = false
    private var isShowing = false

    private val screenWidth: Int
        get() {
            val dm = context.resources.displayMetrics
            return dm.widthPixels
        }

    private val screenHeight: Int
        get() {
            val dm = context.resources.displayMetrics
            return dm.heightPixels
        }

    // Window LayoutParams for Capsule (docked at edge)
    private val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = if (prefs.floatPosX >= 0) prefs.floatPosX else 24
        y = prefs.floatPosY
    }

    // Window LayoutParams for Expanded Card (Centered on screen horizontally & vertically)
    private val cardLayoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        gravity = Gravity.CENTER
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = 0
        y = 0
    }

    fun show() {
        if (isShowing) return
        createCapsuleView()
        createCardView()

        try {
            if (capsuleView?.parent != null) {
                windowManager.removeView(capsuleView)
            }
            windowManager.addView(capsuleView, layoutParams)
            isShowing = true
            isExpanded = false
            android.util.Log.i("FloatingViewManager", "Floating capsule view added successfully at x=${layoutParams.x}, y=${layoutParams.y}")
            updateData(com.bambu.floatwidget.data.PrinterStateManager.state.value)
        } catch (e: Exception) {
            android.util.Log.e("FloatingViewManager", "Failed to add floating view: ${e.message}", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createCapsuleView() {
        val view = inflater.inflate(R.layout.view_floating_capsule, null)
        tvCapsulePercent = view.findViewById(R.id.tvCapsulePercent)
        tvCapsuleTime = view.findViewById(R.id.tvCapsuleTime)
        tvCapsuleEta = view.findViewById(R.id.tvCapsuleEta)
        vStatusDot = view.findViewById(R.id.vStatusDot)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (hypot(dx.toDouble(), dy.toDouble()) > 15) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = (initialY + dy).coerceIn(50, screenHeight - 200)
                        windowManager.updateViewLayout(capsuleView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Click detected -> expand to Card
                        expandToCard()
                    } else {
                        // Drag released -> snap to left or right edge
                        snapCapsuleToEdge()
                        prefs.floatPosX = layoutParams.x
                        prefs.floatPosY = layoutParams.y
                    }
                    true
                }
                else -> false
            }
        }

        capsuleView = view
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createCardView() {
        val view = inflater.inflate(R.layout.view_floating_card, null)
        tvCardDeviceName = view.findViewById(R.id.tvCardDeviceName)
        tvCardStatusBadge = view.findViewById(R.id.tvCardStatusBadge)
        tvCardModelName = view.findViewById(R.id.tvCardModelName)
        pbCardProgress = view.findViewById(R.id.pbCardProgress)
        tvCardPercent = view.findViewById(R.id.tvCardPercent)
        tvCardRemainingTime = view.findViewById(R.id.tvCardRemainingTime)
        tvCardEta = view.findViewById(R.id.tvCardEta)
        tvCardTemps = view.findViewById(R.id.tvCardTemps)
        tvCardLayers = view.findViewById(R.id.tvCardLayers)

        view.findViewById<View>(R.id.btnCollapseCard).setOnClickListener {
            collapseToCapsule()
        }

        view.findViewById<View>(R.id.btnOpenHandy).setOnClickListener {
            openBambuHandyApp()
        }

        view.findViewById<View>(R.id.btnCloseFloating).setOnClickListener {
            onExitRequested()
        }

        // Tap outside the card -> collapse back to capsule
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                collapseToCapsule()
                true
            } else {
                false
            }
        }

        cardView = view
    }

    private fun snapCapsuleToEdge() {
        capsuleView?.let { v ->
            val currentX = layoutParams.x
            val midScreen = screenWidth / 2
            val targetX = if (currentX + v.width / 2 < midScreen) {
                24 // Snap to left edge with padding
            } else {
                screenWidth - v.width - 24 // Snap to right edge
            }

            val animator = ValueAnimator.ofInt(currentX, targetX)
            animator.duration = 200
            animator.interpolator = DecelerateInterpolator()
            animator.addUpdateListener { va ->
                layoutParams.x = va.animatedValue as Int
                if (isShowing && !isExpanded) {
                    try {
                        windowManager.updateViewLayout(capsuleView, layoutParams)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
            animator.start()
        }
    }

    fun expandToCard() {
        if (!isShowing || isExpanded) return
        try {
            windowManager.removeView(capsuleView)
            // Center the card both horizontally and vertically
            cardLayoutParams.x = 0
            cardLayoutParams.y = 0
            windowManager.addView(cardView, cardLayoutParams)
            isExpanded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun collapseToCapsule() {
        if (!isShowing || !isExpanded) return
        try {
            windowManager.removeView(cardView)
            layoutParams.x = if (prefs.floatPosX >= 0) prefs.floatPosX else 24
            layoutParams.y = if (prefs.floatPosY >= 0) prefs.floatPosY else 500
            windowManager.addView(capsuleView, layoutParams)
            isExpanded = false
            snapCapsuleToEdge()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateData(state: PrinterUiState) {
        capsuleView?.post {
            // Update Capsule
            tvCapsulePercent?.text = "${state.percent}%"
            tvCapsuleTime?.text = state.formatRemainingTime()
            tvCapsuleEta?.text = if (state.isPrinting()) state.calculateEta() else state.getStateDisplay()

            vStatusDot?.setBackgroundResource(
                when {
                    state.isPrinting() -> R.drawable.dot_status_green
                    state.isPaused() -> R.drawable.dot_status_yellow
                    state.isConnected -> R.drawable.dot_status_green
                    else -> R.drawable.dot_status_gray
                }
            )

            // Update Card
            tvCardDeviceName?.text = state.deviceName
            tvCardStatusBadge?.text = state.getStateDisplay()
            tvCardModelName?.text = if (state.subtaskName.isNotBlank()) {
                "任务: ${state.subtaskName}"
            } else {
                "状态: ${state.getStateDisplay()}"
            }

            pbCardProgress?.progress = state.percent
            tvCardPercent?.text = "${state.percent}%"
            tvCardRemainingTime?.text = state.formatRemainingTimeDetail()
            tvCardEta?.text = state.calculateEta()

            val nozzleStr = if (state.nozzleTargetTemp > 0) {
                "${state.nozzleTemp.toInt()}°C / ${state.nozzleTargetTemp.toInt()}°C"
            } else {
                "${state.nozzleTemp.toInt()}°C"
            }
            val bedStr = if (state.bedTargetTemp > 0) {
                "${state.bedTemp.toInt()}°C / ${state.bedTargetTemp.toInt()}°C"
            } else {
                "${state.bedTemp.toInt()}°C"
            }
            tvCardTemps?.text = "喷头 $nozzleStr · 热床 $bedStr"

            if (state.totalLayerNum > 0) {
                tvCardLayers?.text = "层数 ${state.layerNum} / ${state.totalLayerNum}"
                tvCardLayers?.visibility = View.VISIBLE
            } else {
                tvCardLayers?.visibility = View.GONE
            }
        }
    }

    private fun openBambuHandyApp() {
        try {
            var launchIntent = context.packageManager.getLaunchIntentForPackage("com.bambulab.creator")
            if (launchIntent == null) {
                launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = android.content.ComponentName(
                        "com.bambulab.creator",
                        "com.bambulab.bambulab.MainActivity"
                    )
                }
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launchIntent)
            collapseToCapsule()
        } catch (e: Exception) {
            android.util.Log.e("FloatingViewManager", "Failed to open Bambu Handy: ${e.message}", e)
            android.widget.Toast.makeText(context, "无法打开 Bambu Handy", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun dismiss() {
        if (!isShowing) return
        try {
            if (isExpanded && cardView?.parent != null) {
                windowManager.removeView(cardView)
            } else if (capsuleView?.parent != null) {
                windowManager.removeView(capsuleView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isShowing = false
            isExpanded = false
        }
    }
}
