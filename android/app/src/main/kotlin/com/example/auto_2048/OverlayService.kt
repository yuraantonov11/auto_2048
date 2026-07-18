package com.example.auto_2048

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.min
import kotlin.math.sqrt

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var startedForeground = false

    companion object {
        private const val CHANNEL_ID = "auto2048_overlay_channel"
        private const val NOTIFICATION_ID = 201

        @Volatile var instance: OverlayService? = null
        @Volatile var currentHint: String = "NONE"
        @Volatile var hintState: String = "IDLE"
        @Volatile var calibrationGrid: List<Int> = emptyList()
        @Volatile var lastDetectedGrid: List<Int> = emptyList()
        @Volatile var isCalibrationMode: Boolean = false
        var screenWidth: Int = 0
        var screenHeight: Int = 0
        @Volatile var samplePoints: List<Pair<Float, Float>> = emptyList()
        @Volatile var lastSamplePoints: List<VisionProcessor.CellSamples> = emptyList()
        @Volatile var overlayOffsetX: Int = 0
        @Volatile var overlayOffsetY: Int = 0
        @Volatile var captureSuppressed: Boolean = false
        @Volatile var debugMode: Boolean = false
        @Volatile var uncertainCells: Set<Int> = emptySet()

        /**
         * Promote the running service to a foreground service of type
         * `specialUse`. On Android 14+ every foreground service must
         * declare its type in the manifest and call `startForeground`
         * within ~5 seconds of `startForegroundService`, otherwise the
         * system raises `validateForegroundServiceType` and kills the
         * app. The OverlayService was previously a plain Service and
         * crashed when the host activity went into the background
         * (e.g. while the user opened the 2048 game).
         */
        fun promoteToForeground(service: Service) {
            if (service !is OverlayService) return
            service.ensureForeground()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Move to foreground as early as possible on Android 14+ where
        // a plain Service is killed if the app loses foreground state
        // shortly after `startForegroundService` returns.
        ensureForeground()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(this)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        try {
            addOverlayView()
            instance = this
            Logger.i("Overlay", "OverlayService: overlay attached successfully, screen=${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Logger.e("Overlay", "Unable to attach overlay", e)
            overlayView = null
            instance = null
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The system may invoke onStartCommand again on rebind after the
        // service was killed; re-promote so the overlay can survive.
        ensureForeground()
        return START_STICKY
    }

    private fun ensureForeground() {
        if (startedForeground) return
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires the foregroundServiceType to be passed
            // explicitly at promotion time. We use SPECIAL_USE because the
            // overlay is a non-standard helper UI for a 2048 auto-solver.
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startedForeground = true
    }

    private fun buildNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto 2048")
            .setContentText("Overlay is active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Overlay Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the calibration / hint overlay alive in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun layoutType(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            2002 // TYPE_PHONE

    private fun overlayFlags(): Int {
        // FLAG_LAYOUT_NO_LIMITS makes the window extend under the status bar,
        // so overlay (0,0) == screen (0,0). Without it, the system may inset
        // the window, causing a Y-offset between overlay coordinates and
        // bitmap coordinates from MediaProjection.
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return if (isCalibrationMode)
            base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else
            base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType(),
            overlayFlags(),
            PixelFormat.TRANSLUCENT
        )

    private fun addOverlayView() {
        val manager = windowManager ?: error("WindowManager is unavailable")
        val view = overlayView ?: error("Overlay view is unavailable")
        manager.addView(view, buildLayoutParams())
    }

    fun setCalibrationMode(enabled: Boolean) {
        isCalibrationMode = enabled
        Logger.i("Overlay", "OverlayService.setCalibrationMode: enabled=$enabled, overlayView=${overlayView != null}")
        val view = overlayView ?: return
        try {
            windowManager?.updateViewLayout(view, buildLayoutParams())
            view.postInvalidate()
        } catch (e: Exception) {
            Logger.e("Overlay", "Unable to update calibration overlay", e)
            isCalibrationMode = false
        }
    }

    fun updateHint(hint: String) {
        currentHint = hint
        overlayView?.postInvalidate()
    }

    fun setHintState(state: String) {
        hintState = state
        overlayView?.postInvalidate()
    }

    fun setCaptureSuppressed(suppressed: Boolean) {
        captureSuppressed = suppressed
        overlayView?.postInvalidate()
    }

    fun refresh() {
        overlayView?.postInvalidate()
    }

    fun setCalibrationPreview(grid: List<Int>) {
        calibrationGrid = if (grid.size == 16) grid else emptyList()
        overlayView?.postInvalidate()
    }

    override fun onDestroy() {
        super.onDestroy()
        val view = overlayView
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Logger.w("Overlay", "Overlay was already detached: ${e.message}")
            }
        }
        instance = null
        isCalibrationMode = false
        hintState = "IDLE"
        captureSuppressed = false
        debugMode = false
        uncertainCells = emptySet()
        calibrationGrid = emptyList()
    }

    // -----------------------------------------------------------------
    //  OverlayView — draws the frame, grid numbers, hint arrow, and
    //  (when in calibration mode) draggable corner handles + buttons.
    // -----------------------------------------------------------------
    class OverlayView(context: Context) : View(context) {

        private val paintFrame = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private val paintHint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
        }

        private val paintHintStatus = Paint().apply {
            color = Color.WHITE
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        private val paintHintStatusBackground = Paint().apply {
            style = Paint.Style.FILL
        }

        private val paintText = Paint().apply {
            color = Color.YELLOW
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        private val paintTextStroke = Paint().apply {
            color = Color.BLACK
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val paintCellSwatch = Paint().apply {
            style = Paint.Style.FILL
        }

        private val paintSample = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        private val paintSampleStroke = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val paintHandle = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val paintHandleStroke = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private val paintButton = Paint().apply {
            style = Paint.Style.FILL
        }

        private val paintButtonText = Paint().apply {
            color = Color.WHITE
            textSize = 42f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        // dragMode: 0=none, 1=TL, 2=TR, 3=BL, 4=BR
        private var dragMode = 0
        private var offsetLogged = false
        private val handleRadius = 80f
        private val minFrameSize = 120f

        private val saveButtonRect = RectF()
        private val cancelButtonRect = RectF()

        // ----- Touch handling -----

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isCalibrationMode) return false

            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (saveButtonRect.contains(x, y)) {
                        saveFromOverlay()
                        return true
                    }
                    if (cancelButtonRect.contains(x, y)) {
                        cancelCalibration()
                        return true
                    }
                    dragMode = findCorner(x, y)
                    return dragMode != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragMode != 0) {
                        moveCorner(dragMode, x, y)
                        postInvalidate()
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragMode = 0
                    return true
                }
            }
            return false
        }

        private fun findCorner(x: Float, y: Float): Int {
            val x1 = (GameConfig.detectedXStart - overlayOffsetX).toFloat()
            val y1 = (GameConfig.detectedYStart - overlayOffsetY).toFloat()
            val x2 = (GameConfig.detectedXEnd - overlayOffsetX).toFloat()
            val y2 = (GameConfig.detectedYEnd - overlayOffsetY).toFloat()

            if (dist(x, y, x1, y1) < handleRadius) return 1
            if (dist(x, y, x2, y1) < handleRadius) return 2
            if (dist(x, y, x1, y2) < handleRadius) return 3
            if (dist(x, y, x2, y2) < handleRadius) return 4
            return 0
        }

        private fun moveCorner(corner: Int, x: Float, y: Float) {
            val offsetX = overlayOffsetX.toFloat()
            val offsetY = overlayOffsetY.toFloat()
            // Use full screen dimensions (not overlay view bounds) so handles
            // can be dragged all the way to the physical screen edge even when
            // the overlay view is inset by system bars.
            val screenRight = screenWidth.toFloat()
            val screenBottom = screenHeight.toFloat()
            val screenX = x + offsetX
            val screenY = y + offsetY
            val xe = GameConfig.detectedXEnd.toFloat()
            val xs = GameConfig.detectedXStart.toFloat()
            val ye = GameConfig.detectedYEnd.toFloat()
            val ys = GameConfig.detectedYStart.toFloat()

            when (corner) {
                1 -> { // Top-Left
                    GameConfig.detectedXStart = clamp(screenX, offsetX, xe - minFrameSize).toInt()
                    GameConfig.detectedYStart = clamp(screenY, offsetY, ye - minFrameSize).toInt()
                }
                2 -> { // Top-Right
                    GameConfig.detectedXEnd = clamp(screenX, xs + minFrameSize, screenRight).toInt()
                    GameConfig.detectedYStart = clamp(screenY, offsetY, ye - minFrameSize).toInt()
                }
                3 -> { // Bottom-Left
                    GameConfig.detectedXStart = clamp(screenX, offsetX, xe - minFrameSize).toInt()
                    GameConfig.detectedYEnd = clamp(screenY, ys + minFrameSize, screenBottom).toInt()
                }
                4 -> { // Bottom-Right
                    GameConfig.detectedXEnd = clamp(screenX, xs + minFrameSize, screenRight).toInt()
                    GameConfig.detectedYEnd = clamp(screenY, ys + minFrameSize, screenBottom).toInt()
                }
            }
        }

        private fun clamp(v: Float, min: Float, max: Float): Float =
            if (max <= min) min else v.coerceIn(min, max)
        private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float = sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))

        // ----- Save / Cancel from overlay -----

        private fun saveFromOverlay() {
            val sw = screenWidth.toFloat()
            val sh = screenHeight.toFloat()
            if (sw > 0 && sh > 0) {
                val prefs = context.getSharedPreferences("auto2048_calibration", Context.MODE_PRIVATE)
                prefs.edit()
                    .putFloat("y1_frac", GameConfig.detectedYStart / sh)
                    .putFloat("y2_frac", GameConfig.detectedYEnd / sh)
                    .putFloat("x1_frac", GameConfig.detectedXStart / sw)
                    .putFloat("x2_frac", GameConfig.detectedXEnd / sw)
                    .apply()
            }
            GameConfig.isManualMode = true
            (context as? OverlayService)?.setCalibrationMode(false)
        }

        private fun cancelCalibration() {
            (context as? OverlayService)?.setCalibrationMode(false)
        }

        // ----- Drawing -----

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Keep the coordinate-space offset current even while the drawing
            // itself is suppressed for MediaProjection capture.
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            OverlayService.overlayOffsetX = loc[0]
            OverlayService.overlayOffsetY = loc[1]
            if (!offsetLogged) {
                Logger.d("Overlay", "Overlay offset: (${loc[0]},${loc[1]})")
                offsetLogged = true
            }

            if (captureSuppressed) return

            // Store the overlay's position on screen — needed by VisionProcessor
            // to convert overlay coordinates to bitmap (screen) coordinates.
            // Only log once to avoid flooding logcat.
            val x1 = (GameConfig.detectedXStart - overlayOffsetX).toFloat()
            val y1 = (GameConfig.detectedYStart - overlayOffsetY).toFloat()
            val x2 = (GameConfig.detectedXEnd - overlayOffsetX).toFloat()
            val y2 = (GameConfig.detectedYEnd - overlayOffsetY).toFloat()

            // Clamp to canvas (screen) bounds so the frame is always visible
            // even when calibration was done on a different device or after
            // a screen configuration change.
            val clampedX1 = x1.coerceIn(0f, canvas.width.toFloat())
            val clampedY1 = y1.coerceIn(0f, canvas.height.toFloat())
            val clampedX2 = x2.coerceIn(0f, canvas.width.toFloat())
            val clampedY2 = y2.coerceIn(0f, canvas.height.toFloat())

            if (clampedY1 >= 0 && clampedY2 > clampedY1 && clampedX2 > clampedX1) {
                if (debugMode || isCalibrationMode) {
                    canvas.drawRect(clampedX1, clampedY1, clampedX2, clampedY2, paintFrame)
                }

                // Use clamped coordinates for all cell calculations so grid
                // numbers stay within screen bounds.
                val cellH = (clampedY2 - clampedY1) / 4f
                val cellW = (clampedX2 - clampedX1) / 4f

                val grid = OverlayService.lastDetectedGrid
                if (debugMode && grid.size == 16) {
                    // Text size proportional to cell size
                    val ts = min(cellW, cellH) * 0.28f
                    paintText.textSize = ts
                    paintTextStroke.textSize = ts
                    for (row in 0..3) {
                        for (col in 0..3) {
                            val index = row * 4 + col
                            val v = grid[index]
                            val tx = clampedX1 + (col * cellW) + (cellW / 2f)
                            // Proper vertical centring using font metrics
                            val cy = clampedY1 + (row * cellH) + (cellH / 2f)
                            val ty = cy - (paintText.descent() + paintText.ascent()) / 2f
                            paintText.color = if (index in uncertainCells) Color.RED else Color.YELLOW
                            // Draw dark outline first, then debug value on top
                            canvas.drawText(v.toString(), tx, ty, paintTextStroke)
                            canvas.drawText(v.toString(), tx, ty, paintText)
                        }
                    }
                    paintText.color = Color.YELLOW
                }

                if (debugMode) {
                    val cellSamples = OverlayService.lastSamplePoints
                    if (cellSamples.isNotEmpty()) {
                        drawCellSamples(canvas, cellSamples)
                    } else {
                        for ((sx, sy) in OverlayService.samplePoints) {
                            canvas.drawCircle(sx, sy, 8f, paintSample)
                            canvas.drawCircle(sx, sy, 8f, paintSampleStroke)
                        }
                    }
                }

                if (!isCalibrationMode && hintState != "IDLE") {
                    drawHintStatus(canvas, clampedX1, clampedY1, clampedX2)
                }

                if (currentHint != "NONE" && !isCalibrationMode &&
                    (hintState == "READY" || hintState == "READY_NEW")) {
                    drawHint(canvas, clampedX1, clampedY1, clampedX2, clampedY2)
                }

                if (isCalibrationMode) {
                    val preview = OverlayService.calibrationGrid
                    if (preview.size == 16) {
                        drawCalibrationPreview(canvas, clampedX1, clampedY1, clampedX2, clampedY2, cellW, cellH, preview)
                    }
                    drawHandles(canvas, clampedX1, clampedY1, clampedX2, clampedY2)
                    drawCalibrationButtons(canvas)
                }
            }
        }

        private fun drawHandles(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
            val r = handleRadius / 2.5f
            for ((cx, cy) in listOf(x1 to y1, x2 to y1, x1 to y2, x2 to y2)) {
                canvas.drawCircle(cx, cy, r, paintHandle)
                canvas.drawCircle(cx, cy, r, paintHandleStroke)
            }
        }

        private fun drawCalibrationButtons(canvas: Canvas) {
            val sw = width.toFloat()
            val btnW = 340f
            val btnH = 100f
            val gap = 24f
            val totalW = btnW * 2 + gap
            val startX = (sw - totalW) / 2f
            val btnY = 80f

            val previewText = if (calibrationGrid.size == 16) {
                "Сканів: ${calibrationGrid.count { it > 0 }}/16"
            } else {
                "Очікування даних..."
            }
            val textWidth = paintButtonText.measureText(previewText)
            val infoY = btnY + btnH + 38f
            val infoRect = RectF(
                (sw - textWidth) / 2f - 24f,
                infoY - 48f,
                (sw + textWidth) / 2f + 24f,
                infoY + 16f
            )
            paintButton.color = Color.argb(200, 30, 30, 30)
            canvas.drawRoundRect(infoRect, 18f, 18f, paintButton)
            paintButtonText.textSize = 36f
            canvas.drawText(previewText, sw / 2f, infoY, paintButtonText)
            paintButtonText.textSize = 42f

            // Save button
            saveButtonRect.set(startX, btnY, startX + btnW, btnY + btnH)
            paintButton.color = Color.parseColor("#2E7D32")
            canvas.drawRoundRect(saveButtonRect, 18f, 18f, paintButton)
            canvas.drawText("✓ ЗБЕРЕГТИ", saveButtonRect.centerX(), saveButtonRect.centerY() + 15f, paintButtonText)

            // Cancel button
            cancelButtonRect.set(startX + btnW + gap, btnY, startX + btnW * 2 + gap, btnY + btnH)
            paintButton.color = Color.parseColor("#C62828")
            canvas.drawRoundRect(cancelButtonRect, 18f, 18f, paintButton)
            canvas.drawText("✕ СКАСУВАТИ", cancelButtonRect.centerX(), cancelButtonRect.centerY() + 15f, paintButtonText)
        }

        @Suppress("UNCHECKED_CAST")
        private val learnedColorsField by lazy {
            VisionProcessor::class.java.getDeclaredField("learnedColors").apply { isAccessible = true }
        }

        private fun getLearnedColor(value: Int): IntArray? {
            return try {
                val map = learnedColorsField.get(VisionProcessor) as Map<Int, IntArray>
                map[value]
            } catch (_: Exception) { null }
        }

        private fun drawCalibrationPreview(
            canvas: Canvas,
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            cellW: Float,
            cellH: Float,
            grid: List<Int>
        ) {
            val ts = min(cellW, cellH) * 0.28f
            paintText.textSize = ts
            paintTextStroke.textSize = ts
            for (row in 0..3) {
                for (col in 0..3) {
                    val index = row * 4 + col
                    val value = grid[index]
                    val tx = x1 + (col * cellW) + (cellW / 2f)
                    val cy = y1 + (row * cellH) + (cellH / 2f)
                    val ty = cy - (paintText.descent() + paintText.ascent()) / 2f
                    paintText.color = if (value <= 0) Color.argb(200, 220, 220, 220) else Color.YELLOW
                    val label = if (value <= 0) "·" else value.toString()
                    canvas.drawText(label, tx, ty, paintTextStroke)
                    canvas.drawText(label, tx, ty, paintText)
                }
            }
            paintText.color = Color.YELLOW

            val swatchSize = min(cellW, cellH) * 0.16f
            val swatchGap = swatchSize * 0.4f
            for (row in 0..3) {
                for (col in 0..3) {
                    val value = grid[row * 4 + col]
                    if (value <= 0) continue
                    val ref = GameConfig.TILE_COLORS[value]
                    val baseRgb = ref ?: intArrayOf(180, 180, 180)
                    val cx = x1 + (col * cellW) + (cellW / 2f)
                    val cy = y1 + (row * cellH) + (cellH * 0.78f)
                    paintCellSwatch.color = Color.rgb(baseRgb[0], baseRgb[1], baseRgb[2])
                    canvas.drawRect(
                        cx - swatchSize - swatchGap / 2f,
                        cy - swatchSize / 2f,
                        cx - swatchGap / 2f,
                        cy + swatchSize / 2f,
                        paintCellSwatch
                    )
                    val learned = getLearnedColor(value)
                    if (learned != null) {
                        paintCellSwatch.color = Color.rgb(learned[0], learned[1], learned[2])
                    } else {
                        paintCellSwatch.color = Color.argb(180, 60, 60, 60)
                    }
                    canvas.drawRect(
                        cx + swatchGap / 2f,
                        cy - swatchSize / 2f,
                        cx + swatchSize + swatchGap / 2f,
                        cy + swatchSize / 2f,
                        paintCellSwatch
                    )
                }
            }
        }

        private fun drawHintStatus(canvas: Canvas, x1: Float, y1: Float, x2: Float) {
            val text = when (hintState) {
                "SCANNING" -> "СКАНУЮ ПОЛЕ..."
                "READY_NEW" -> "НОВИЙ ХІД ГОТОВИЙ"
                "READY" -> "ХІД ГОТОВИЙ"
                else -> return
            }
            val color = when (hintState) {
                "SCANNING" -> Color.rgb(255, 167, 38)
                "READY_NEW" -> Color.rgb(76, 255, 80)
                else -> Color.CYAN
            }
            val centerX = (x1 + x2) / 2f
            val baseline = (y1 - 34f).coerceAtLeast(64f)
            val textWidth = paintHintStatus.measureText(text)
            val rect = RectF(
                centerX - textWidth / 2f - 30f,
                baseline - 48f,
                centerX + textWidth / 2f + 30f,
                baseline + 16f
            )
            paintHintStatusBackground.color = Color.argb(215, 20, 20, 20)
            canvas.drawRoundRect(rect, 22f, 22f, paintHintStatusBackground)
            paintHintStatus.color = color
            canvas.drawText(text, centerX, baseline, paintHintStatus)
        }

        private fun drawCellSamples(
            canvas: Canvas,
            cellSamples: List<VisionProcessor.CellSamples>
        ) {
            val baseFill = paintSample.color
            val baseStroke = paintSampleStroke.color
            for (cell in cellSamples) {
                for ((index, point) in cell.points.withIndex()) {
                    if (index >= cell.samples.size) continue
                    val (x, y) = point
                    val sample = cell.samples[index]
                    if (sample.accepted) {
                        paintSample.color = Color.rgb(
                            sample.rgb.first.coerceIn(0, 255),
                            sample.rgb.second.coerceIn(0, 255),
                            sample.rgb.third.coerceIn(0, 255)
                        )
                        canvas.drawCircle(x, y, 11f, paintSample)
                        paintSampleStroke.color = Color.WHITE
                        canvas.drawCircle(x, y, 11f, paintSampleStroke)
                    } else {
                        paintSample.color = Color.argb(120, 120, 120, 120)
                        canvas.drawCircle(x, y, 8f, paintSample)
                        paintSampleStroke.color = Color.argb(220, 255, 60, 60)
                        canvas.drawCircle(x, y, 8f, paintSampleStroke)
                    }
                }
            }
            paintSample.color = baseFill
            paintSampleStroke.color = baseStroke
        }

    private fun drawHint(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        paintHint.color = if (hintState == "READY_NEW") Color.rgb(76, 255, 80) else Color.CYAN
        paintHint.strokeWidth = if (hintState == "READY_NEW") 16f else 12f
        val cx = (x1 + x2) / 2f
        val cy = (y1 + y2) / 2f
        val s = 120f
        val p = Path()
        when (currentHint) {
            "UP" -> { p.moveTo(cx, cy + s); p.lineTo(cx, cy - s); p.lineTo(cx - 30, cy - s + 30); p.moveTo(cx, cy - s); p.lineTo(cx + 30, cy - s + 30) }
            "DOWN" -> { p.moveTo(cx, cy - s); p.lineTo(cx, cy + s); p.lineTo(cx - 30, cy + s - 30); p.moveTo(cx, cy + s); p.lineTo(cx + 30, cy + s - 30) }
            "LEFT" -> { p.moveTo(cx + s, cy); p.lineTo(cx - s, cy); p.lineTo(cx - s + 30, cy - 30); p.moveTo(cx - s, cy); p.lineTo(cx - s + 30, cy + 30) }
            "RIGHT" -> { p.moveTo(cx - s, cy); p.lineTo(cx + s, cy); p.lineTo(cx + s - 30, cy - 30); p.moveTo(cx + s, cy); p.lineTo(cx + s - 30, cy + 30) }
        }
        canvas.drawPath(p, paintHint)
        if (hintState == "READY_NEW") {
            canvas.drawCircle(cx, cy, s + 38f, paintHint)
        }
    }
}
}

