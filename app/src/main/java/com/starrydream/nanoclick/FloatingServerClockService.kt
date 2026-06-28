package com.starrydream.nanoclick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class FloatingClockMode(val label: String) {
    Device("기기시간"),
    Server("서버시간")
}

class FloatingServerClockService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)
    private val gapPx by lazy { 6.dpToPx() }
    private val edgePaddingPx by lazy { 8.dpToPx() }
    private val handleSizePx by lazy { 40.dpToPx() }
    private val timeHorizontalPaddingPx by lazy { 8.dpToPx() }
    private val timeWidthSafetyPaddingPx by lazy { 10.dpToPx() }
    private val timeWidthPx by lazy { calculateTimeWindowWidth() }
    private val barHeightPx by lazy { 40.dpToPx() }
    private val dragSlopPx by lazy { 8.dpToPx() }
    private val clockBackground = Color.rgb(58, 61, 79)
    private val clockBackgroundHex = "#3A3D4F"

    private var windowManager: WindowManager? = null
    private var lockView: ImageButton? = null
    private var timeView: TextView? = null
    private var closeView: TextView? = null
    private var lockParams: WindowManager.LayoutParams? = null
    private var timeParams: WindowManager.LayoutParams? = null
    private var closeParams: WindowManager.LayoutParams? = null
    private var serverOffsetMs: Long = 0L
    private var clockMode: FloatingClockMode = FloatingClockMode.Device
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastLockTapMs = 0L
    private var lastTimeTapMs = 0L
    private var isEditing = false
    private var isDragging = false
    private var resyncJob: Job? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (resyncJob?.isActive != true) {
                updateClockText()
            }
            // Android UI rendering is not guaranteed to paint every millisecond.
            handler.postDelayed(this, 30L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopClock()
                return START_NOT_STICKY
            }
            ACTION_ADJUST -> {
                if (timeView != null) {
                    switchToEditing()
                }
                return START_STICKY
            }
            ACTION_RESET -> {
                resetClockPosition()
                return START_STICKY
            }
            ACTION_UPDATE_OFFSET -> {
                serverOffsetMs = intent.getLongExtra(EXTRA_SERVER_OFFSET_MS, serverOffsetMs)
                updateClockText()
                return START_STICKY
            }
            ACTION_START, null -> {
                clockMode = FloatingClockMode.valueOf(
                    intent?.getStringExtra(EXTRA_MODE) ?: FloatingClockMode.Device.name
                )
                serverOffsetMs = intent?.getLongExtra(EXTRA_SERVER_OFFSET_MS, serverOffsetMs) ?: serverOffsetMs
                if (timeView == null) {
                    startClock()
                } else {
                    updateClockText()
                    FloatingClockRuntimeState.markRunning(
                        editing = isEditing,
                        mode = clockMode,
                        message = "플로팅 기준: ${clockMode.label}"
                    )
                }
            }
        }
        return START_STICKY
    }

    private fun startClock() {
        startForeground(NOTIFICATION_ID, buildNotification())
        showBarAtTopCenter()
        switchToEditing()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        FloatingClockRuntimeState.markRunning(
            editing = true,
            mode = clockMode,
            message = "플로팅 기준: ${clockMode.label}"
        )
    }

    private fun resetClockPosition() {
        if (timeView == null) return
        removeAllOverlays()
        showBarAtTopCenter()
        switchToEditing()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        FloatingClockRuntimeState.markRunning(
            editing = true,
            mode = clockMode,
            message = "플로팅 시계를 초기 위치로 되돌렸습니다."
        )
    }

    private fun showBarAtTopCenter() {
        val screen = getScreenSize()
        val groupLeft = ((screen.x - editingGroupWidth()) / 2).coerceAtLeast(edgePaddingPx)
        val top = 36.dpToPx()
        val timeLeft = groupLeft + handleSizePx + gapPx

        lockView = createLockView()
        timeView = createTimeView()
        closeView = createCloseView()

        lockParams = baseParams(handleSizePx, barHeightPx).apply {
            x = groupLeft
            y = top
            title = "CLYM 플로팅 시계 잠금"
        }
        timeParams = baseParams(timeWidthPx, barHeightPx).apply {
            x = timeLeft
            y = top
            title = "CLYM 플로팅 시계 시간"
        }
        closeParams = baseParams(handleSizePx, barHeightPx).apply {
            x = timeLeft + timeWidthPx + gapPx
            y = top
            title = "CLYM 플로팅 시계 닫기"
        }

        windowManager?.addView(lockView, lockParams)
        windowManager?.addView(timeView, timeParams)
        windowManager?.addView(closeView, closeParams)
        updateLockIcon()
        updateClockText()
    }

    private fun createTimeView(): TextView =
        TextView(this).apply {
            textSize = TIME_TEXT_SIZE_SP
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            isSingleLine = true
            maxLines = 1
            ellipsize = null
            setPadding(timeHorizontalPaddingPx, 0, timeHorizontalPaddingPx, 0)
            setTextColor(Color.WHITE)
            applyClockBackground()
            setOnTouchListener { _, event ->
                if (!isEditing) return@setOnTouchListener false
                handleTimeTouch(event)
                true
            }
        }

    private fun createLockView(): ImageButton =
        ImageButton(this).apply {
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
            setColorFilter(Color.WHITE)
            applyClockBackground()
            setOnClickListener { handleLockTap() }
        }

    private fun createCloseView(): TextView =
        TextView(this).apply {
            text = "\u00D7"
            textSize = 22f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            applyClockBackground()
            setOnClickListener { stopClock() }
        }

    private fun handleTimeTouch(event: MotionEvent) {
        val params = timeParams ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragOffsetX = event.rawX - params.x
                dragOffsetY = event.rawY - params.y
                downRawX = event.rawX
                downRawY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && movedEnough(event)) {
                    isDragging = true
                }
                if (isDragging) {
                    params.x = (event.rawX - dragOffsetX).roundToInt()
                    params.y = (event.rawY - dragOffsetY).roundToInt()
                    clampTimeParams()
                    movePartsAroundTime()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    params.x = (event.rawX - dragOffsetX).roundToInt()
                    params.y = (event.rawY - dragOffsetY).roundToInt()
                    clampTimeParams()
                    movePartsAroundTime()
                } else {
                    handleTimeTap()
                }
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
    }

    private fun movedEnough(event: MotionEvent): Boolean =
        abs(event.rawX - downRawX) > dragSlopPx || abs(event.rawY - downRawY) > dragSlopPx

    private fun handleTimeTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTimeTapMs <= DOUBLE_TAP_MS) {
            lastTimeTapMs = 0L
            if (clockMode == FloatingClockMode.Server && isEditing) {
                resyncServerTime()
            }
        } else {
            lastTimeTapMs = now
        }
    }

    private fun resyncServerTime() {
        if (resyncJob?.isActive == true) return

        val url = FloatingClockRuntimeState.latestServerUrl.trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "서버 링크가 없어 재동기화할 수 없습니다.", Toast.LENGTH_SHORT).show()
            FloatingClockRuntimeState.markRunning(
                editing = isEditing,
                mode = clockMode,
                message = "서버 링크가 없어 재동기화할 수 없습니다."
            )
            return
        }

        timeView?.text = "${modeIcon()} 동기화 중..."
        resyncJob = serviceScope.launch {
            val result = fetchServerTime(url)
            result
                .onSuccess { serverTime ->
                    serverOffsetMs = serverTime.offsetMs
                    updateClockText()
                    FloatingClockRuntimeState.markRunning(
                        editing = isEditing,
                        mode = clockMode,
                        message = "서버시간을 다시 동기화했습니다."
                    )
                }
                .onFailure {
                    updateClockText()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@FloatingServerClockService,
                            "서버시간 재동기화에 실패했습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    FloatingClockRuntimeState.markRunning(
                        editing = isEditing,
                        mode = clockMode,
                        message = "서버시간 재동기화에 실패했습니다."
                    )
                }
        }
    }

    private fun handleLockTap() {
        if (isEditing) {
            switchToFixed()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastLockTapMs <= DOUBLE_TAP_MS) {
            lastLockTapMs = 0L
            switchToEditing()
        } else {
            lastLockTapMs = now
        }
    }

    private fun switchToEditing() {
        val time = timeView ?: return
        val params = timeParams ?: return
        logOverlayState("before switchToEditing")
        isEditing = true
        normalizeOverlayVisualState()
        params.flags = touchableFlags()
        params.alpha = 1f
        params.format = PixelFormat.TRANSLUCENT
        time.isEnabled = true
        windowManager?.updateViewLayout(time, params)
        ensureCloseView()
        normalizeOverlayVisualState()
        clampTimeParams()
        movePartsAroundTime()
        updateLockIcon()
        logOverlayState("after switchToEditing")
        FloatingClockRuntimeState.markRunning(
            editing = true,
            mode = clockMode,
            message = "위치 조정 중 · 플로팅 기준: ${clockMode.label}"
        )
    }

    private fun switchToFixed() {
        val time = timeView ?: return
        val params = timeParams ?: return
        logOverlayState("before switchToFixed")
        isEditing = false
        normalizeOverlayVisualState()
        removeCloseView()
        params.flags = passthroughFlags()
        params.alpha = 1f
        params.format = PixelFormat.TRANSLUCENT
        time.isEnabled = true
        windowManager?.updateViewLayout(time, params)
        normalizeOverlayVisualState()
        clampTimeParams()
        movePartsAroundTime()
        updateLockIcon()
        logOverlayState("after switchToFixed")
        FloatingClockRuntimeState.markRunning(
            editing = false,
            mode = clockMode,
            message = "고정됨 · 플로팅 기준: ${clockMode.label}"
        )
    }

    private fun ensureCloseView() {
        if (closeView != null) return
        val close = createCloseView()
        val params = baseParams(handleSizePx, barHeightPx)
        params.alpha = 1f
        params.format = PixelFormat.TRANSLUCENT
        closeView = close
        closeParams = params
        windowManager?.addView(close, params)
    }

    private fun removeCloseView() {
        closeView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        closeView = null
        closeParams = null
    }

    private fun normalizeOverlayVisualState() {
        lockView?.applyClockBackground()
        timeView?.applyClockBackground()
        closeView?.applyClockBackground()

        lockView?.let { view ->
            view.alpha = 1f
            view.isEnabled = true
            view.setColorFilter(Color.WHITE)
        }
        timeView?.let { view ->
            view.alpha = 1f
            view.isEnabled = true
            view.setTextColor(Color.WHITE)
        }
        closeView?.let { view ->
            view.alpha = 1f
            view.isEnabled = true
            view.setTextColor(Color.WHITE)
        }

        lockParams?.apply {
            alpha = 1f
            format = PixelFormat.TRANSLUCENT
        }
        timeParams?.apply {
            alpha = 1f
            format = PixelFormat.TRANSLUCENT
        }
        closeParams?.apply {
            alpha = 1f
            format = PixelFormat.TRANSLUCENT
        }
    }

    private fun View.applyClockBackground() {
        alpha = 1f
        isEnabled = true
        backgroundTintList = null
        background = roundedBackground(clockBackground, CLOCK_CORNER_RADIUS_DP.dpToPx().toFloat())
    }

    private fun logOverlayState(label: String) {
        logSingleOverlayState(label, "lock", lockView, lockParams)
        logSingleOverlayState(label, "time", timeView, timeParams)
        logSingleOverlayState(label, "close", closeView, closeParams)
    }

    private fun logSingleOverlayState(
        label: String,
        name: String,
        view: View?,
        params: WindowManager.LayoutParams?
    ) {
        Log.d(
            TAG,
            "$label/$name " +
                "view.alpha=${view?.alpha ?: "null"}, " +
                "layoutParams.alpha=${params?.alpha ?: "null"}, " +
                "view.isEnabled=${view?.isEnabled ?: "null"}, " +
                "layoutParams.flags=${params?.flags ?: "null"}, " +
                "layoutParams.format=${params?.format ?: "null"}, " +
                "background=$clockBackgroundHex"
        )
    }

    private fun updateLockIcon() {
        lockView?.setImageResource(
            if (isEditing) {
                R.drawable.ic_floating_lock_open
            } else {
                R.drawable.ic_floating_lock_closed
            }
        )
    }

    private fun updateClockText() {
        val nowMs = when (clockMode) {
            FloatingClockMode.Device -> System.currentTimeMillis()
            FloatingClockMode.Server -> System.currentTimeMillis() + serverOffsetMs
        }
        timeView?.text = "${modeIcon()} ${timeFormat.format(Date(nowMs))}"
        updateLockIcon()
    }

    private fun modeIcon(): String =
        when (clockMode) {
            FloatingClockMode.Device -> "\uD83D\uDCF1"
            FloatingClockMode.Server -> "\uD83C\uDF10"
        }

    private fun calculateTimeWindowWidth(): Int {
        val paint = TextPaint().apply {
            textSize = TIME_TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
            typeface = Typeface.MONOSPACE
        }
        val maxTextWidth = maxOf(
            paint.measureText("\uD83D\uDCF1 88:88:88.888"),
            paint.measureText("\uD83C\uDF10 88:88:88.888")
        )
        return ceil(maxTextWidth + (timeHorizontalPaddingPx * 2) + timeWidthSafetyPaddingPx).roundToInt()
    }

    private fun movePartsAroundTime() {
        val time = timeParams ?: return
        val lock = lockParams ?: return

        lock.x = time.x - gapPx - handleSizePx
        lock.y = time.y

        lockView?.let { windowManager?.updateViewLayout(it, lock) }
        timeView?.let { windowManager?.updateViewLayout(it, time) }

        if (isEditing) {
            val close = closeParams ?: return
            close.x = time.x + timeWidthPx + gapPx
            close.y = time.y
            closeView?.let { windowManager?.updateViewLayout(it, close) }
        }
    }

    private fun clampTimeParams() {
        val params = timeParams ?: return
        val screen = getScreenSize()
        val leftOfTime = handleSizePx + gapPx
        val rightOfTime = if (isEditing) gapPx + handleSizePx else 0
        val minX = edgePaddingPx + leftOfTime
        val maxX = screen.x - edgePaddingPx - rightOfTime - timeWidthPx
        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(edgePaddingPx, screen.y - barHeightPx - edgePaddingPx)
    }

    private fun editingGroupWidth(): Int =
        handleSizePx + gapPx + timeWidthPx + gapPx + handleSizePx

    private fun stopClock() {
        removeAllOverlays()
        handler.removeCallbacks(ticker)
        resyncJob?.cancel()
        FloatingClockRuntimeState.markStopped("플로팅 시계를 껐습니다.")
        stopForegroundCompat()
        stopSelf()
    }

    private fun removeAllOverlays() {
        lockView?.let { view -> runCatching { windowManager?.removeView(view) } }
        timeView?.let { view -> runCatching { windowManager?.removeView(view) } }
        closeView?.let { view -> runCatching { windowManager?.removeView(view) } }
        lockView = null
        timeView = null
        closeView = null
        lockParams = null
        timeParams = null
        closeParams = null
    }

    private fun baseParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            touchableFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
        }

    private fun touchableFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun passthroughFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("CLYM 플로팅 시계 표시 중")
            .setContentText("다른 앱 위에 ${clockMode.label}을 표시하고 있습니다.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "시계 끄기", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CLYM 플로팅 시계",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getScreenSize(): Point {
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealSize(point)
        return point
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        removeAllOverlays()
        handler.removeCallbacks(ticker)
        resyncJob?.cancel()
        serviceScope.cancel()
        FloatingClockRuntimeState.markStopped()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.starrydream.nanoclick.START_FLOATING_CLOCK"
        private const val ACTION_STOP = "com.starrydream.nanoclick.STOP_FLOATING_CLOCK"
        private const val ACTION_ADJUST = "com.starrydream.nanoclick.ADJUST_FLOATING_CLOCK"
        private const val ACTION_RESET = "com.starrydream.nanoclick.RESET_FLOATING_CLOCK"
        private const val ACTION_UPDATE_OFFSET = "com.starrydream.nanoclick.UPDATE_FLOATING_CLOCK_OFFSET"
        private const val EXTRA_SERVER_OFFSET_MS = "extra_server_offset_ms"
        private const val EXTRA_MODE = "extra_mode"
        private const val CHANNEL_ID = "nanoclick_floating_clock"
        private const val NOTIFICATION_ID = 20260618
        private const val DOUBLE_TAP_MS = 350L
        private const val CLOCK_CORNER_RADIUS_DP = 10
        private const val TIME_TEXT_SIZE_SP = 20f
        private const val TAG = "FloatingClock"

        fun startIntent(
            context: Context,
            mode: FloatingClockMode,
            serverOffsetMs: Long = 0L
        ): Intent =
            Intent(context, FloatingServerClockService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_SERVER_OFFSET_MS, serverOffsetMs)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, FloatingServerClockService::class.java).apply {
                action = ACTION_STOP
            }

        fun adjustIntent(context: Context): Intent =
            Intent(context, FloatingServerClockService::class.java).apply {
                action = ACTION_ADJUST
            }

        fun resetIntent(context: Context): Intent =
            Intent(context, FloatingServerClockService::class.java).apply {
                action = ACTION_RESET
            }

        fun updateOffsetIntent(context: Context, serverOffsetMs: Long): Intent =
            Intent(context, FloatingServerClockService::class.java).apply {
                action = ACTION_UPDATE_OFFSET
                putExtra(EXTRA_SERVER_OFFSET_MS, serverOffsetMs)
            }
    }
}
