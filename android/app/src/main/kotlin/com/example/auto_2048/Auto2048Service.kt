package com.example.auto_2048

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.min
import kotlin.random.Random

class Auto2048Service : AccessibilityService() {

    companion object {
        private const val TAG = "Auto2048Service"
        @Volatile var instance: Auto2048Service? = null
    }

    /**
     * Outcome of an asynchronously-dispatched gesture.
     * Reported to Flutter via the MainActivity MethodChannel bridge.
     */
    enum class GestureResult {
        SUCCESS,
        FAILURE,
        CANCELLED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Тут ми могли б читати екран через дерево UI, але нам це не потрібно
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * Dispatch a tap gesture at the absolute pixel coordinate ([x], [y]).
     * Used by [MainActivity.restartAfterTerminal] to push the "Try again"
     * button on the Game Over / 2048-win overlay.
     *
     * A tap is implemented as a short stroke that starts and ends at the
     * same point - the AccessibilityService rejects gesture paths whose
     * start equals their end so we add a 1px jitter.
     */
    fun performTap(x: Int, y: Int, onComplete: (GestureResult) -> Unit): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            // 1px stroke is below human-perceptible jitter but enough for
            // the gesture API to dispatch.
            lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
        }
        Logger.d("Service", "performTap: x=$x, y=$y")

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0L, 90L))

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete(GestureResult.SUCCESS)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete(GestureResult.CANCELLED)
            }
        }

        val dispatched = dispatchGesture(gestureBuilder.build(), callback, null)
        if (!dispatched) {
            Logger.w("Service", "performTap: gesture was rejected")
            onComplete(GestureResult.FAILURE)
        }
        return dispatched
    }

    /**
     * Dispatch a swipe gesture in [direction] (one of UP/DOWN/LEFT/RIGHT).
     *
     * The path endpoints are computed from the calibrated grid bounds published
     * by [GridCoordinates] (which [MainActivity.analyzeGridValues] keeps in sync
     * with the last captured screenshot). If no screenshot has been analyzed yet
     * (e.g. user hits the manual swipe button on a fresh install), we fall back
     * to a reasonable default path so the gesture still goes through the middle
     * of the screen.
     */
    fun performSwipe(direction: String, onComplete: (GestureResult) -> Unit): Boolean {
        val path = buildSwipePath(direction)
        Logger.d("Service", "performSwipe: direction=$direction, path=$path")

        val gestureBuilder = GestureDescription.Builder()
        val startDelay = Random.nextLong(0L, 36L)
        val duration = Random.nextLong(85L, 181L)
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, startDelay, duration))

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete(GestureResult.SUCCESS)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete(GestureResult.CANCELLED)
            }
        }

        val dispatched = dispatchGesture(gestureBuilder.build(), callback, null)
        if (!dispatched) {
            Logger.w("Service", "performSwipe: gesture was rejected")
            onComplete(GestureResult.FAILURE)
        }
        return dispatched
    }

    /**
     * Build a [Path] for the requested swipe direction.
     *
     * Strategy: start near one edge of the detected grid and end near the opposite
     * edge, offset by one cell so the gesture has a clear "travel" through every
     * row/column. This is more robust than tapping cell centers because the
     * AccessibilityService only sees the *path*, not the individual cells, and a
     * center-to-center stroke can be ambiguous if the start/end coincide with a
     * tile position.
     */
    private fun buildSwipePath(direction: String): Path {
        val coords = GridCoordinates
        if (!coords.isCalibrated) {
            val centerX = 500f + Random.nextInt(-90, 91)
            val centerY = 1000f + Random.nextInt(-90, 91)
            val left = 200f + Random.nextInt(-35, 36)
            val right = 900f + Random.nextInt(-35, 36)
            val top = 600f + Random.nextInt(-35, 36)
            val bottom = 1500f + Random.nextInt(-35, 36)
            return humanPath(direction, centerX, centerY, left, right, top, bottom, 70f)
        }

        val cellWidth = coords.cellWidth()
        val cellHeight = coords.cellHeight()
        val horizontalInset = cellWidth * randomFloat(0.22f, 0.42f)
        val verticalInset = cellHeight * randomFloat(0.22f, 0.42f)
        val left = coords.gridXStart + horizontalInset
        val right = coords.gridXEnd - horizontalInset
        val top = coords.gridYStart + verticalInset
        val bottom = coords.gridYEnd - verticalInset
        val centerX = randomFloat(
            coords.gridXStart + cellWidth * 0.75f,
            coords.gridXEnd - cellWidth * 0.75f
        )
        val centerY = randomFloat(
            coords.gridYStart + cellHeight * 0.75f,
            coords.gridYEnd - cellHeight * 0.75f
        )
        val curve = min(cellWidth, cellHeight) * randomFloat(0.04f, 0.16f)

        return humanPath(direction, centerX, centerY, left, right, top, bottom, curve)
    }

    private fun humanPath(
        direction: String,
        centerX: Float,
        centerY: Float,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        curveAmount: Float
    ): Path {
        val curve = curveAmount * if (Random.nextBoolean()) 1f else -1f
        val path = Path()
        when (direction) {
            "UP" -> {
                path.moveTo(centerX, bottom)
                path.quadTo(centerX + curve, (top + bottom) / 2f, centerX, top)
            }
            "DOWN" -> {
                path.moveTo(centerX, top)
                path.quadTo(centerX + curve, (top + bottom) / 2f, centerX, bottom)
            }
            "LEFT" -> {
                path.moveTo(right, centerY)
                path.quadTo((left + right) / 2f, centerY + curve, left, centerY)
            }
            "RIGHT" -> {
                path.moveTo(left, centerY)
                path.quadTo((left + right) / 2f, centerY + curve, right, centerY)
            }
            else -> {
                path.moveTo(centerX, bottom)
                path.quadTo(centerX + curve, (top + bottom) / 2f, centerX, top)
            }
        }
        return path
    }

    private fun randomFloat(from: Float, until: Float): Float {
        if (until <= from) return from
        return from + Random.nextFloat() * (until - from)
    }
}
