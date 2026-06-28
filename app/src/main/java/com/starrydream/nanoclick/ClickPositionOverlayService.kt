package com.starrydream.nanoclick

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.roundToInt

data class ClickPosition(
    val x: Int,
    val y: Int
)

object ClickPositionOverlayResult {
    var onApplied: ((ClickPosition) -> Unit)? = null
    var onCancelled: (() -> Unit)? = null
}

class ClickPositionOverlayService : Service() {
    private val markerSizePx by lazy { 52.dpToPx() }
    private val controlGapPx by lazy { 14.dpToPx() }
    private val edgePaddingPx by lazy { 8.dpToPx() }
    private val controlWidthPx by lazy { 176.dpToPx() }
    private val controlHeightPx by lazy { 44.dpToPx() }

    private var windowManager: WindowManager? = null
    private var markerView: TargetMarkerView? = null
    private var controlsView: LinearLayout? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var currentPosition: ClickPosition? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLEAR -> {
                clearOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_EDITING, null -> {
                if (!Settings.canDrawOverlays(this)) {
                    ClickPositionOverlayResult.onCancelled?.invoke()
                    stopSelf()
                    return START_NOT_STICKY
                }
                startEditing()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        clearOverlay()
        super.onDestroy()
    }

    private fun startEditing() {
        clearOverlay()

        val screenSize = getScreenSize()
        val centerX = screenSize.x / 2
        val centerY = screenSize.y / 2
        currentPosition = ClickPosition(centerX, centerY)

        val marker = TargetMarkerView(this)
        val markerLayoutParams = WindowManager.LayoutParams(
            markerSizePx,
            markerSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = centerX - (markerSizePx / 2)
            y = centerY - (markerSizePx / 2)
            title = "CLYM 클릭 위치 포인터"
        }

        marker.setDragListener(
            onDrag = { center ->
                val boundedCenter = boundMarkerCenter(center)
                currentPosition = boundedCenter
                markerLayoutParams.x = boundedCenter.x - (markerSizePx / 2)
                markerLayoutParams.y = boundedCenter.y - (markerSizePx / 2)
                windowManager?.updateViewLayout(marker, markerLayoutParams)
                moveControlsNearMarker(boundedCenter)
            }
        )

        val controls = createControls()
        val controlsLayoutParams = WindowManager.LayoutParams(
            controlWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "CLYM 클릭 위치 컨트롤"
        }

        markerView = marker
        markerParams = markerLayoutParams
        controlsView = controls
        controlsParams = controlsLayoutParams

        windowManager?.addView(marker, markerLayoutParams)
        windowManager?.addView(controls, controlsLayoutParams)
        moveControlsNearMarker(ClickPosition(centerX, centerY))
    }

    private fun createControls(): LinearLayout {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val cancelButton = Button(this).apply {
            text = "취소"
            minHeight = 0
            minimumHeight = 0
            setOnClickListener {
                ClickPositionOverlayResult.onCancelled?.invoke()
                clearOverlay()
                stopSelf()
            }
        }

        val applyButton = Button(this).apply {
            text = "적용"
            minHeight = 0
            minimumHeight = 0
            isEnabled = currentPosition != null
            setOnClickListener {
                currentPosition?.let { position ->
                    ClickPositionOverlayResult.onApplied?.invoke(position)
                    switchToAppliedMarker()
                }
            }
        }

        controls.addView(
            cancelButton,
            LinearLayout.LayoutParams(0, controlHeightPx, 1f).apply {
                marginEnd = 10.dpToPx()
            }
        )
        controls.addView(
            applyButton,
            LinearLayout.LayoutParams(0, controlHeightPx, 1f)
        )

        return controls
    }

    private fun moveControlsNearMarker(center: ClickPosition) {
        val params = controlsParams ?: return
        val controls = controlsView ?: return
        val screenSize = getScreenSize()
        val controlHeight = if (controls.height > 0) controls.height else controlHeightPx

        val rawX = center.x - (controlWidthPx / 2)
        val belowY = center.y + (markerSizePx / 2) + controlGapPx
        val aboveY = center.y - (markerSizePx / 2) - controlGapPx - controlHeight
        val preferBelow = center.y < screenSize.y / 2
        val rawY = if (preferBelow) {
            if (belowY + controlHeight <= screenSize.y - edgePaddingPx) belowY else aboveY
        } else {
            if (aboveY >= edgePaddingPx) aboveY else belowY
        }

        params.x = rawX.coerceIn(edgePaddingPx, screenSize.x - controlWidthPx - edgePaddingPx)
        params.y = rawY.coerceIn(edgePaddingPx, screenSize.y - controlHeight - edgePaddingPx)
        windowManager?.updateViewLayout(controls, params)
    }

    private fun boundMarkerCenter(center: ClickPosition): ClickPosition {
        val screenSize = getScreenSize()
        val radius = markerSizePx / 2
        return ClickPosition(
            x = center.x.coerceIn(radius, screenSize.x - radius),
            y = center.y.coerceIn(radius, screenSize.y - radius)
        )
    }

    private fun switchToAppliedMarker() {
        controlsView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        controlsView = null
        controlsParams = null

        val marker = markerView ?: return
        val params = markerParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        windowManager?.updateViewLayout(marker, params)
    }

    private fun clearOverlay() {
        controlsView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        markerView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        controlsView = null
        markerView = null
        controlsParams = null
        markerParams = null
        currentPosition = null
    }

    private fun getScreenSize(): Point {
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealSize(point)
        return point
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val ACTION_START_EDITING = "com.starrydream.nanoclick.START_CLICK_POSITION_EDITING"
        private const val ACTION_CLEAR = "com.starrydream.nanoclick.CLEAR_CLICK_POSITION_OVERLAY"

        fun startEditingIntent(context: Context): Intent =
            Intent(context, ClickPositionOverlayService::class.java).apply {
                action = ACTION_START_EDITING
            }

        fun clearIntent(context: Context): Intent =
            Intent(context, ClickPositionOverlayService::class.java).apply {
                action = ACTION_CLEAR
            }
    }
}

private class TargetMarkerView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 33, 150, 243)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 25, 118, 210)
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 15, 23, 42)
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var onDrag: ((ClickPosition) -> Unit)? = null

    fun setDragListener(onDrag: (ClickPosition) -> Unit) {
        this.onDrag = onDrag
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragOffsetX = event.x
                dragOffsetY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val centerX = (event.rawX - dragOffsetX + (width / 2f)).roundToInt()
                val centerY = (event.rawY - dragOffsetY + (height / 2f)).roundToInt()
                onDrag?.invoke(ClickPosition(centerX, centerY))
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) - strokePaint.strokeWidth
        val crossSize = 7f * resources.displayMetrics.density

        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
        canvas.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY, centerPaint)
        canvas.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize, centerPaint)
        canvas.drawCircle(centerX, centerY, 2.5f * resources.displayMetrics.density, centerPaint)
    }
}
