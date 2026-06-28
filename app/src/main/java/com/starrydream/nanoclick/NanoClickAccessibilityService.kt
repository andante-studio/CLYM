package com.starrydream.nanoclick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class NanoClickAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        NanoClickRuntimeState.setAccessibilityConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        NanoClickRuntimeState.setAccessibilityConnected(false)
        super.onDestroy()
    }

    private fun dispatchSingleTap(
        x: Int,
        y: Int,
        onResult: (TapResult) -> Unit
    ) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onResult(TapResult.Success)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onResult(TapResult.Cancelled)
                }
            },
            null
        )

        if (!dispatched) {
            onResult(TapResult.FailedToDispatch)
        }
    }

    companion object {
        private const val TAP_DURATION_MS = 60L
        private var activeService: NanoClickAccessibilityService? = null

        fun isConnected(): Boolean = activeService != null

        fun tapOnce(
            x: Int,
            y: Int,
            onResult: (TapResult) -> Unit
        ) {
            val service = activeService
            if (service == null) {
                onResult(TapResult.ServiceUnavailable)
            } else {
                service.dispatchSingleTap(x, y, onResult)
            }
        }
    }
}

enum class TapResult {
    Success,
    Cancelled,
    FailedToDispatch,
    ServiceUnavailable
}
