package com.jackz314.puzzlesolver


import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.os.HandlerCompat


private const val TAG = "GestureAccessService"

//required for all apps that uses accessibility services, listens for events, we don't do anything currently with these events
class GestureService : AccessibilityService() {

    interface ServiceInstanceAvailableListener {
        fun onAvailable()
    }

    companion object {
        private var service: GestureService? = null
        private var serviceInstanceAvailableListener: ServiceInstanceAvailableListener? = null
        fun getInstance() = service
        fun setInstanceListener(listenerService: ServiceInstanceAvailableListener?) {
            serviceInstanceAvailableListener = listenerService
        }
    }

    private val handler = HandlerThread("GestureService").apply {start()}.run {
        HandlerCompat.createAsync(
            looper
        )
    }

    private var resultCallback: GestureResultCallback? = null

    private var delay = 0 // delay after each gesture

    fun setCallback(callback: GestureResultCallback?) {
        resultCallback = callback
    }

    private fun clickGesture(x: Float, y: Float): GestureDescription {
        val clickPath =
            GestureDescription.StrokeDescription(Path().apply {moveTo(x, y)}, 0, 5)//1 ms is enough for click
        return GestureDescription.Builder().apply {addStroke(clickPath)}.build()
    }

    fun click(x: Int, y: Int) = click(x.toFloat(), y.toFloat())

    fun click(x: Float, y: Float){
        dispatchGesture(clickGesture(x, y), resultCallback, handler)
    }

    fun setDelay(delay: Int){
        this.delay = delay
    }

    private fun swipeGesture(x: Float, y: Float, dx: Float, dy: Float, duration: Int): GestureDescription {
        val swipePath = GestureDescription.StrokeDescription(Path()
            .apply {moveTo(x, y);lineTo(x + dx, y + dy)}, delay.toLong(), duration.toLong()
        )
        return GestureDescription.Builder().apply {addStroke(swipePath)}.build()
    }

    //duration in ms, default 10 ms
    fun swipe(x: Float, y: Float, dx: Float, dy: Float, duration: Int = 10){
//        Log.d(TAG, "swipe: $x, $y | $dx, $dy")
        dispatchGesture(swipeGesture(x, y, dx, dy, duration), resultCallback, handler)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected: Gesture Service Connected")
        service = this
        serviceInstanceAvailableListener?.onAvailable()
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt: Interrupted.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.text?.toString()?.contains("Claim", true) == true) {
            Log.d(TAG, "onAccessibilityEvent: $event")
            val nodeInfo = event.source
            Log.d(TAG, "onACE: $nodeInfo")
            nodeInfo?.recycle()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: Service unbind")
        return super.onUnbind(intent)
    }

}