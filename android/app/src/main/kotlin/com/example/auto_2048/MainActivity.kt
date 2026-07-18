package com.example.auto_2048

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class MainActivity : FlutterActivity() {

    private val TAG = "Auto2048"
    private val CHANNEL_NAME = "com.antonov.auto2048/gestures"

    private val REQUEST_CODE_SCREEN_CAPTURE = 1001
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionHandlerThread: HandlerThread? = null
    private var projectionHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingCaptureResult: Result? = null
    private var mediaProjectionData: Intent? = null
    private var mediaProjectionResultCode: Int = 0

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private lateinit var prefs: SharedPreferences
    private lateinit var screenProbe: ScreenProbe

    private var solverRunning = false
    private var solverAutomatic = true
    private var solverGeneration = 0
    private var solverIterationInProgress = false
    private var solverLastMove = "NONE"
    private var solverLastGrid: List<Int> = emptyList()
    // Stuck-state tracking: when OCR misreads a tile (e.g. cat-mascot
    // occludes a cell), the solver may keep recommending the same move
    // and the board never changes. Count consecutive identical (grid,
    // move) pairs; once we hit STUCK_THRESHOLD, forbid the previously
    // chosen move and force the solver to pick something different so
    // the bot breaks out of the loop.
    private var stuckGridSignature: Int = 0
    private var stuckCount: Int = 0
    private var stuckForbiddenMove: String = "NONE"
    private val stuckThreshold = 3
    private var pendingHintGrid: List<Int> = emptyList()
    private var pendingHintMove = "NONE"
    private var publishedHintGrid: List<Int> = emptyList()
    private var publishedHintMove = "NONE"
    private var solverHintState = "IDLE"
    private var solverSpeed = 0.55
    private var solverLastError = ""
    private var solverFailureStreak = 0
    private var captureInProgress = false
    private var captureInitializationInProgress = false
    // Temporal smoothing for the cat-paw animation: when VisionProcessor
    // returns an empty grid because a single cell was uncertain (e.g. the
    // cat's paw was occluding it), we fall back to the last fully-resolved
    // grid instead of failing the solve step. [lastReliableGridTimestampMs]
    // lets us discard the fallback after [gridFallbackMaxAgeMs] so the bot
    // doesn't play forever on a stale view.
    private var lastReliableGrid: List<Int> = emptyList()
    private var lastReliableGridTimestampMs: Long = 0L
    private val gridFallbackMaxAgeMs = 8_000L

    private val hintIterationDelayMs = 80L
    private val retryIterationDelayMs = 450L
    private val gestureTimeoutMs = 1500L
    private val maxSpeed = 1.0
    private val overlayCaptureDelayMs = 50L
    private val calibrationScanIntervalMs = 700L
    private var calibrationScanGeneration = 0

    companion object {
        private const val PREFS_NAME = "auto2048_calibration"
        private const val KEY_Y1 = "y1_frac"
        private const val KEY_Y2 = "y2_frac"
        private const val KEY_X1 = "x1_frac"
        private const val KEY_X2 = "x2_frac"
        private const val KEY_SOLVER_SPEED = "solver_speed"
        private const val DEFAULT_Y1 = 0.250
        private const val DEFAULT_Y2 = 0.928
        private const val DEFAULT_X1 = 0.000
        private const val DEFAULT_X2 = 0.981
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Share screen dimensions with VisionProcessor for coordinate scaling
        GameConfig.screenWidth = screenWidth
        GameConfig.screenHeight = screenHeight
        VisionProcessor.initialize(applicationContext)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        screenProbe = ScreenProbe()
        solverSpeed = prefs.getFloat(KEY_SOLVER_SPEED, 0.55f).toDouble().coerceIn(0.0, maxSpeed)
        // Ensure prefs always have our latest default fractions so fresh installs
        // or devices without saved calibration get correct bounds immediately.
        if (!prefs.contains(KEY_Y1)) {
            prefs.edit()
                .putFloat(KEY_Y1, DEFAULT_Y1.toFloat())
                .putFloat(KEY_Y2, DEFAULT_Y2.toFloat())
                .putFloat(KEY_X1, DEFAULT_X1.toFloat())
                .putFloat(KEY_X2, DEFAULT_X2.toFloat())
                .apply()
        }
        applySavedCalibration()

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
            .setMethodCallHandler { call, result -> onMethodCall(call, result) }
    }

    private fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "swipeUp" -> handleSwipe("UP", result)
            "swipeDown" -> handleSwipe("DOWN", result)
            "swipeLeft" -> handleSwipe("LEFT", result)
            "swipeRight" -> handleSwipe("RIGHT", result)
            "isServiceRunning" -> result.success(Auto2048Service.instance != null)
            "openAccessibilitySettings" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "startSolver" -> {
                val automatic = call.argument<Boolean>("automatic") ?: true
                solverSpeed = (call.argument<Double>("speed") ?: solverSpeed).coerceIn(0.0, maxSpeed)
                prefs.edit().putFloat(KEY_SOLVER_SPEED, solverSpeed.toFloat()).apply()
                            Logger.i("Main", "startSolver MethodChannel called: automatic=$automatic speed=$solverSpeed")
                            startSolverLoop(automatic, result)
                        }
            "stopSolver" -> {
                stopSolverLoop()
                result.success(true)
            }
            "getSolverStatus" -> result.success(mapOf(
                "running" to solverRunning,
                "automatic" to solverAutomatic,
                "lastMove" to solverLastMove,
                "lastError" to solverLastError,
                "speed" to solverSpeed,
                "hintState" to solverHintState,
                "recognitionDiagnostics" to VisionProcessor.lastDiagnostics,
                "debugMode" to OverlayService.debugMode
            ))
            "getGridState" -> handleCaptureScreen(result)
            "toggleOverlay" -> handleToggleOverlay(call, result)
            "setHint" -> {
                val hint = call.argument<String>("direction") ?: "NONE"
                OverlayService.instance?.updateHint(hint)
                result.success(null)
            }
            "setDebugMode" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                handleSetDebugMode(enabled, result)
            }
            "setAutomaticSpeed" -> {
                solverSpeed = (call.argument<Double>("speed") ?: solverSpeed).coerceIn(0.0, maxSpeed)
                prefs.edit().putFloat(KEY_SOLVER_SPEED, solverSpeed.toFloat()).apply()
                result.success(solverSpeed)
            }
            "solveStep" -> handleSolveStep(result)
            "probeScreen" -> handleProbeScreen(result)
            "setCalibrationMode" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                handleSetCalibrationMode(enabled, result)
            }
            "calibrationScanNow" -> {
                runCatching { triggerCalibrationScan() }
                    .onSuccess { result.success(true) }
                    .onFailure { result.error("CAL_SCAN_FAIL", it.message, null) }
            }
            else -> result.notImplemented()
        }
    }

    private fun handleSetCalibrationMode(enabled: Boolean, result: Result) {
        Logger.i("Main", "handleSetCalibrationMode: enabled=$enabled, hasOverlayPermission=${Settings.canDrawOverlays(this)}")
        if (enabled && android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            result.error("OVERLAY_REQUIRED", "Allow the overlay to calibrate the board", null)
            return
        }

        try {
            if (enabled && OverlayService.instance == null) {
                Logger.i("Main", "handleSetCalibrationMode: starting OverlayService")
                startService(Intent(this, OverlayService::class.java))
            }
            mainHandler.postDelayed({
                OverlayService.instance?.setCalibrationMode(enabled)
                if (enabled) startCalibrationScanLoop()
            }, if (enabled) 200L else 0L)
            if (!enabled) stopCalibrationScanLoop()
            result.success(true)
        } catch (e: Exception) {
            Logger.e("Main", "Unable to start calibration overlay", e)
            result.error("OVERLAY_FAIL", e.message, null)
        }
    }

    private fun startCalibrationScanLoop() {
        stopCalibrationScanLoop()
        calibrationScanGeneration = (calibrationScanGeneration + 1) and 0x7fffffff
        val generation = calibrationScanGeneration
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (generation != calibrationScanGeneration) return
                if (OverlayService.instance == null || !OverlayService.isCalibrationMode) return
                runCatching { triggerCalibrationScan() }
                mainHandler.postDelayed(this, calibrationScanIntervalMs)
            }
        }, calibrationScanIntervalMs)
    }

    private fun stopCalibrationScanLoop() {
        calibrationScanGeneration++
    }

    private fun triggerCalibrationScan() {
        if (solverRunning) return
        handleCaptureScreen(object : Result {
            override fun success(res: Any?) {
                val grid = (res as? String).orEmpty()
                    .removePrefix("GRID_STATE:")
                    .split(",")
                    .mapNotNull { it.toIntOrNull() }
                OverlayService.instance?.setCalibrationPreview(grid)
            }

            override fun error(code: String, message: String?, details: Any?) = Unit
            override fun notImplemented() = Unit
        })
    }

    private fun handleSetDebugMode(enabled: Boolean, result: Result) {
        Logger.i("Main", "handleSetDebugMode: enabled=$enabled, hasOverlayPermission=${Settings.canDrawOverlays(this)}")
        if (enabled && android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
            result.error("OVERLAY_REQUIRED", "Allow the overlay to display diagnostics", null)
            return
        }

        try {
            if (enabled && OverlayService.instance == null) {
                Logger.i("Main", "handleSetDebugMode: starting OverlayService")
                startService(Intent(this, OverlayService::class.java))
            }
            OverlayService.debugMode = enabled
            OverlayService.instance?.refresh()
            result.success(true)
        } catch (e: Exception) {
            Logger.e("Main", "Unable to change debug overlay", e)
            result.error("OVERLAY_FAIL", e.message, null)
        }
    }

    private fun startSolverLoop(automatic: Boolean, result: Result) {
        if (automatic && Auto2048Service.instance == null) {
            result.error("SERVICE_OFF", "Enable the accessibility service for automatic mode", null)
            return
        }

        if (!automatic) {
            if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
                result.error("OVERLAY_REQUIRED", "Allow the overlay to display move hints", null)
                return
            }
            if (OverlayService.instance == null) {
                try {
                    startService(Intent(this, OverlayService::class.java))
                } catch (e: Exception) {
                    Logger.e("Main", "Unable to start hint overlay", e)
                    result.error("OVERLAY_FAIL", e.message, null)
                    return
                }
            }
        }

        solverGeneration++
        val generation = solverGeneration
        solverRunning = true
        solverAutomatic = automatic
        solverIterationInProgress = false
        solverLastMove = "NONE"
        solverLastGrid = emptyList()
        pendingHintGrid = emptyList()
        pendingHintMove = "NONE"
        publishedHintGrid = emptyList()
        publishedHintMove = "NONE"
        solverHintState = if (automatic) "IDLE" else "SCANNING"
        OverlayService.instance?.setHintState(solverHintState)
        VisionProcessor.clearExpectedBoard()
        solverLastError = ""
        solverFailureStreak = 0
        result.success(true)

        val initialDelay = if (automatic) 0L else 250L
        mainHandler.postDelayed({ runSolverIteration(generation) }, initialDelay)
    }

    private fun stopSolverLoop(error: String = "") {
        solverRunning = false
        solverGeneration++
        solverIterationInProgress = false
        solverLastError = error
        solverLastGrid = emptyList()
        pendingHintGrid = emptyList()
        pendingHintMove = "NONE"
        publishedHintGrid = emptyList()
        publishedHintMove = "NONE"
        solverHintState = "IDLE"
        OverlayService.instance?.setHintState("IDLE")
        OverlayService.instance?.updateHint("NONE")
        VisionProcessor.clearExpectedBoard()
    }

    private fun runSolverIteration(generation: Int) {
            Logger.d("Main", "runSolverIteration: enter solverRunning=$solverRunning gen=$generation match=${generation == solverGeneration} iterInProgress=$solverIterationInProgress")
            if (!solverRunning || generation != solverGeneration || solverIterationInProgress) return
        solverIterationInProgress = true
        if (!solverAutomatic) {
            solverHintState = "SCANNING"
            OverlayService.instance?.setHintState("SCANNING")
        }

        handleSolveStep(object : Result {
            override fun success(res: Any?) {
                if (!solverRunning || generation != solverGeneration) return

                // handleSolveStep may now return either a direction String
                // (the happy path) or a Map describing a terminal-state
                // detect + automatic restart. Normalise both into the same
                // downstream handling - terminal results skip the swipe but
                // keep the loop alive so the new game is played
                // immediately.
                val move: String = when (res) {
                    is String -> res
                    is Map<*, *> -> {
                        val status = res["status"] as? String ?: "NONE"
                        when (status) {
                            "restarted" -> {
                                val terminalState = res["state"] as? String ?: "unknown"
                                Logger.i("Main", "Solver restarted game from $terminalState state")
                                "RESTARTED"
                            }
                            "terminal" -> {
                                // The board is already terminal but no
                                // modal was visible (rare - typically an
                                // OCR-only detection). Stop the loop so
                                // the user can see the final position.
                                Logger.w("Main", "Solver saw terminal board; pausing iteration")
                                stopSolverLoop("Terminal board reached")
                                "NONE"
                            }
                            else -> "NONE"
                        }
                    }
                    else -> "NONE"
                }
                solverLastMove = move
                solverLastError = ""
                solverFailureStreak = 0

                // RESTARTED means the modal was tapped and a new board
                // is on screen. Wait the standard post-swipe delay so
                // the new game's spawn animation completes, then keep
                // iterating.
                if (move == "RESTARTED") {
                    mainHandler.postDelayed({
                        runSolverIteration(generation)
                    }, postSwipeDelayMs())
                    return
                }

                if (!solverAutomatic) {
                    val gridStable = solverLastGrid.size == 16 && solverLastGrid == pendingHintGrid
                    val moveStable = move == pendingHintMove
                    if (gridStable && moveStable) {
                        val isNewRecommendation = solverLastGrid != publishedHintGrid || move != publishedHintMove
                        solverHintState = if (isNewRecommendation) "READY_NEW" else "READY"
                        OverlayService.instance?.updateHint(move)
                        OverlayService.instance?.setHintState(solverHintState)
                        publishedHintGrid = solverLastGrid.toList()
                        publishedHintMove = move
                    } else {
                        solverHintState = "SCANNING"
                        OverlayService.instance?.updateHint("NONE")
                        OverlayService.instance?.setHintState("SCANNING")
                        pendingHintGrid = solverLastGrid.toList()
                        pendingHintMove = move
                    }
                    completeSolverIteration(generation, hintIterationDelayMs)
                    return
                }

                OverlayService.instance?.updateHint(move)
                if (move == "NONE") {
                    completeSolverIteration(generation, retryIterationDelayMs)
                    return
                }

                val service = Auto2048Service.instance
                if (service == null) {
                    stopSolverLoop("Accessibility service stopped")
                    return
                }

                var gestureFinished = false
                val predictedBoard = GameSolver.predictBoardAfterMove(solverLastGrid, move)
                try {
                    service.performSwipe(move) { gestureResult ->
                        mainHandler.post {
                            if (!gestureFinished) {
                                gestureFinished = true
                                if (gestureResult == Auto2048Service.GestureResult.SUCCESS && predictedBoard != null) {
                                    VisionProcessor.setExpectedBoard(predictedBoard)
                                    // Use a delay long enough for the game's
                                    // move + merge + spawn animation to
                                    // finish so we don't sample stale tiles.
                                    completeSolverIteration(generation, postSwipeDelayMs())
                                } else {
                                    VisionProcessor.clearExpectedBoard()
                                    solverLastError = "Swipe was not completed"
                                    completeSolverIteration(generation, retryIterationDelayMs)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("Main", "Swipe dispatch failed", e)
                    VisionProcessor.clearExpectedBoard()
                    gestureFinished = true
                    solverLastError = e.message ?: "Swipe dispatch failed"
                    completeSolverIteration(generation, retryIterationDelayMs)
                }

                if (!gestureFinished) {
                    mainHandler.postDelayed({
                        if (!gestureFinished) {
                            gestureFinished = true
                            VisionProcessor.clearExpectedBoard()
                            solverLastError = "Swipe timed out"
                            completeSolverIteration(generation, retryIterationDelayMs)
                        }
                    }, gestureTimeoutMs)
                }
            }

            override fun error(code: String, message: String?, details: Any?) {
                if (!solverRunning || generation != solverGeneration) return
                solverLastError = message ?: code
                solverFailureStreak++

                if (code == "DENIED") {
                    stopSolverLoop(solverLastError)
                } else if (solverFailureStreak >= 5) {
                    stopSolverLoop("$solverLastError. Restart the solver after checking screen capture.")
                } else {
                    val backoff = retryIterationDelayMs * solverFailureStreak.coerceAtMost(4)
                    completeSolverIteration(generation, backoff)
                }
            }

            override fun notImplemented() {
                stopSolverLoop("Solver is unavailable")
            }
        })
    }

    private fun nextAutomaticDelayMs(): Long {
        val fastestMs = 90.0
        val slowestMs = 1500.0
        val base = (slowestMs - (slowestMs - fastestMs) * solverSpeed).toLong()
        val jitter = (base * 0.15).toLong().coerceAtLeast(20L)
        return (base + Random.nextLong(-jitter, jitter + 1)).coerceIn(70L, 1800L)
    }

    /**
     * Delay used between a successful swipe and the next capture. Must be
     * at least [GameConfig.MIN_POST_SWIPE_DELAY_MS] so the game's move +
     * merge + spawn animation completes before we sample the board;
     * sampling mid-animation causes VisionProcessor to return uncertainty
     * (-1 in [VisionProcessor.lastObservedGrid]) and stale data on screen.
     *
     * At slower [solverSpeed] the calculated [nextAutomaticDelayMs] is
     * already longer than the animation time, so we use the larger of the
     * two values to honour the user's chosen pace.
     */
    private fun postSwipeDelayMs(): Long {
        val minDelay = GameConfig.MIN_POST_SWIPE_DELAY_MS
        val computed = nextAutomaticDelayMs()
        val base = maxOf(minDelay, computed).toDouble()
        val jitter = (base * 0.10).toLong().coerceAtLeast(15L)
        return (base + Random.nextLong(-jitter, jitter + 1)).toLong()
            .coerceIn(minDelay, 2000L)
    }

    private fun completeSolverIteration(generation: Int, delayMs: Long) {
        if (generation != solverGeneration) return
        solverIterationInProgress = false
        if (solverRunning) {
            mainHandler.postDelayed({ runSolverIteration(generation) }, delayMs)
        }
    }

    private fun applySavedCalibration() {
        val hasReal = prefs.contains(KEY_X1) && prefs.contains(KEY_X2) &&
            prefs.contains(KEY_Y1) && prefs.contains(KEY_Y2)
        val y1 = prefs.getFloat(KEY_Y1, DEFAULT_Y1.toFloat()).toDouble()
        val y2 = prefs.getFloat(KEY_Y2, DEFAULT_Y2.toFloat()).toDouble()
        val x1 = prefs.getFloat(KEY_X1, DEFAULT_X1.toFloat()).toDouble()
        val x2 = prefs.getFloat(KEY_X2, DEFAULT_X2.toFloat()).toDouble()
        if (hasReal) {
            applyCalibration(y1, y2, x1, x2)
        } else {
            // First-run: do NOT set isManualMode. The auto-detector will run
            // on the first capture and write the real bounds. Using the
            // DEFAULT_* constants as if they were user-saved would lock the
            // bot onto the wrong region (e.g. only the top half of the
            // ftband 2048 field).
            Logger.d("Main", "applySavedCalibration: no saved values, deferring to auto-detector")
        }
    }

    private fun applyCalibration(y1Frac: Double, y2Frac: Double, x1Frac: Double, x2Frac: Double) {
        GameConfig.detectedYStart = (screenHeight * y1Frac).toInt()
        GameConfig.detectedYEnd = (screenHeight * y2Frac).toInt()
        GameConfig.detectedXStart = (screenWidth * x1Frac).toInt()
        GameConfig.detectedXEnd = (screenWidth * x2Frac).toInt()
        GameConfig.isManualMode = true
        OverlayService.instance?.updateHint("NONE")
    }

    private fun handleToggleOverlay(call: MethodCall, result: Result) {
        val show = call.argument<Boolean>("show") ?: false
        if (show && android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
            startActivity(intent)
            result.error("PERMISSION_REQUIRED", "Allow overlay", null)
            return
        }
        val intent = Intent(this, OverlayService::class.java)
        try {
            if (show) startService(intent) else stopService(intent)
            result.success(null)
        } catch (e: Exception) {
            Logger.e("Main", "Unable to change overlay state", e)
            result.error("OVERLAY_FAIL", e.message, null)
        }
    }

    private fun handleSolveStep(result: Result) {
            Logger.d("Main", "handleSolveStep: enter")
            // Terminal-state pre-check: if the screen shows "You Win" or
        // "Game Over" we MUST stop the solver, simulate a tap on the
        // restart button and report the outcome to Dart. Doing OCR on
        // a modal-covered frame returns garbage grids that crash
        // Expectimax and eventually kill the MediaProjection session.
        screenProbe.probe(mediaProjection) { outcome, _ ->
            when (outcome) {
                ScreenProbeOutcome.GameOver -> {
                    Logger.i("Main", "Pre-check: Game Over detected, restarting")
                    restartAfterTerminal(wasWon = false) { status, error ->
                        if (status == "ok") {
                            result.success(mapOf("status" to "restarted", "state" to "gameover"))
                        } else {
                            result.error("RESTART_FAIL", error, null)
                        }
                    }
                    return@probe
                }
                ScreenProbeOutcome.Won -> {
                    Logger.i("Main", "Pre-check: 2048 Win detected, restarting")
                    restartAfterTerminal(wasWon = true) { status, error ->
                        if (status == "ok") {
                            result.success(mapOf("status" to "restarted", "state" to "won"))
                        } else {
                            result.error("RESTART_FAIL", error, null)
                        }
                    }
                    return@probe
                }
                ScreenProbeOutcome.Playing, ScreenProbeOutcome.Unknown -> {
                    // Fall through to the normal capture pipeline.
                }
            }
            // Continue with the normal capture pipeline. A probe of
            // "Unknown" means we did not see a terminal state and
            // should let the regular grid read decide what to do.
            handleCaptureScreen(object : Result {
                override fun success(res: Any?) {
                    val gridString = res as? String ?: ""
                    val grid = if (gridString.startsWith("GRID_STATE:")) {
                        gridString.substringAfter(":").split(",").mapNotNull { it.toIntOrNull() }
                    } else {
                        emptyList()
                    }

                    if (grid.size != 16) {
                        // Frame was rejected by the OCR pipeline (likely one or
                        // more cells were occluded by the cat-paw animation).
                        // If we have a recent reliable grid, fall back to it so
                        // the solver can keep playing. Otherwise there's nothing
                        // we can do.
                        val fallbackAgeMs = android.os.SystemClock.elapsedRealtime() - lastReliableGridTimestampMs
                        if (lastReliableGrid.size == 16 && fallbackAgeMs in 0..gridFallbackMaxAgeMs) {
                            Logger.d(
                                                            "Main",
                                "OCR frame incomplete (age=${fallbackAgeMs}ms), using fallback grid"
                            )
                            val worker = projectionHandler ?: run {
                                result.error("FAIL", "Capture worker is unavailable", null)
                                return
                            }
                            processSolverGrid(lastReliableGrid, worker, result)
                            return
                        }
                        OverlayService.instance?.updateHint("NONE")
                        result.error("GRID_NOT_FOUND", "The calibrated 2048 board was not recognized", null)
                        return
                    }

                    // Cache a fresh reliable grid for the next cat-occluded frame.
                    lastReliableGrid = grid.toList()
                    lastReliableGridTimestampMs = android.os.SystemClock.elapsedRealtime()

                    val worker = projectionHandler
                    if (worker == null) {
                        result.error("FAIL", "Capture worker is unavailable", null)
                        return
                    }
                    processSolverGrid(grid, worker, result)
                }
                override fun error(c: String, m: String?, d: Any?) = result.error(c, m, d)
                override fun notImplemented() = result.notImplemented()
            })
        }
    }

    /**
     * Runs the solver on a 16-cell [grid] and reports the chosen move (or a
     * terminal-state notice) to [result]. Extracted so the cat-paw fallback
     * path in [handleSolveStep] can reuse the exact same decision logic.
     */
    private fun processSolverGrid(grid: List<Int>, worker: Handler, result: Result) {
        if (grid.size != 16) {
            result.error("FAIL", "Solver expects a 16-cell grid, got ${grid.size}", null)
            return
        }
        try {
            solverLastGrid = grid.toList()

            // Final safety net: if the grid is already
            // terminal (no legal moves remain) we drop the
            // solve result and report it back to Dart so the
            // UI can stop the loop and restart manually.
            if (GameSolver.isTerminal(grid)) {
                stuckGridSignature = 0
                stuckCount = 0
                stuckForbiddenMove = "NONE"
                mainHandler.post {
                    result.success(mapOf("status" to "terminal", "grid" to grid))
                }
                return
            }

            // Stuck-state detection: if OCR returns the same grid
            // AND the previously chosen move did not change the
            // board, increment the counter. When it exceeds
            // [stuckThreshold], forbid that move on the next
            // solve so the bot is forced to try a different
            // direction. This breaks the loop where an OCR ghost
            // read (e.g. a cat mascot occluding a tile) makes the
            // solver chase a phantom merge.
            val gridSignature = grid.fold(0) { acc, v -> 31 * acc + v }
            if (gridSignature == stuckGridSignature &&
                solverLastMove != "NONE" &&
                solverLastMove == stuckForbiddenMove) {
                stuckCount++
            } else {
                stuckGridSignature = gridSignature
                stuckCount = 0
                stuckForbiddenMove = "NONE"
            }
            val forbidMove = if (stuckCount >= stuckThreshold) {
                stuckForbiddenMove.also {
                    Logger.w("Main", "Stuck detector: grid & move unchanged for " +
                        "$stuckCount iterations, forbidding $it")
                }
            } else {
                "NONE"
            }

            // If we're stuck AND the user has pinned manual calibration, the
            // cached bounds may have drifted (e.g. user re-launched the game
            // and the field shifted). Trigger a one-shot re-detect so the
            // next capture searches for the board instead of trusting the
            // stale rectangle. After re-detect, also reset the stuck counter
            // so we don't keep nudging the solver on a frame that is still
            // stale.
            if (stuckCount >= stuckThreshold && GameConfig.isManualMode) {
                VisionProcessor.forceAutoDetect = true
                Logger.w("Main", "Stuck detector: forcing board re-detect (manual mode, stale bounds?)")
                stuckCount = 0
                stuckForbiddenMove = "NONE"
            }

            worker.post {
                try {
                    val move = GameSolver.decideBestMove(
                        grid,
                        forbiddenMove = forbidMove
                    )
                    // If the solver returned the same move despite the
                    // forbid (e.g. it's the only legal direction),
                    // reset the counter — we're not actually stuck.
                    if (move == forbidMove) {
                        stuckCount = 0
                        stuckForbiddenMove = "NONE"
                    } else if (move == stuckForbiddenMove) {
                        // same as before; keep counter climbing
                    } else {
                        // different move → not stuck any more
                        stuckCount = 0
                        stuckForbiddenMove = move
                    }
                    mainHandler.post {
                        result.success(move)
                    }
                } catch (e: Exception) {
                    Logger.e("Main", "Solve failed", e)
                    mainHandler.post { result.error("SOLVE_FAIL", e.message, null) }
                }
            }
        } catch (e: Exception) {
            Logger.e("Main", "Solve pipeline failed", e)
            result.error("SOLVE_FAIL", e.message, null)
        }
    }

    /**
     * Probe-only handler exposed to Dart so the controller layer can poll
     * the current modal state without committing to a solver move. Useful
     * for the UI badge "Game Over / Win / In game".
     *
     * `state` matches the Dart [GameState] enum names so the bridge can
     * resolve it back to the same enum (playing, won2048, gameOver,
     * menu, unknown).
     */
    private fun handleProbeScreen(result: Result) {
        screenProbe.probe(mediaProjection) { outcome, diagnostics ->
            val stateName = when (outcome) {
                ScreenProbeOutcome.Playing -> "playing"
                ScreenProbeOutcome.Won -> "won2048"
                ScreenProbeOutcome.GameOver -> "gameOver"
                ScreenProbeOutcome.Unknown -> "unknown"
            }
            result.success(mapOf(
                "state" to stateName,
                "diagnostics" to diagnostics,
                "confidence" to when (outcome) {
                    ScreenProbeOutcome.Playing -> 1.0
                    ScreenProbeOutcome.Won -> 0.95
                    ScreenProbeOutcome.GameOver -> 0.95
                    ScreenProbeOutcome.Unknown -> 0.30
                }
            ))
        }
    }

    /**
     * Tap the 2048 "Try again" button after a terminal state and confirm
     * the new board is in Playing state. Reports back through [callback]
     * with ("ok", null) on success or ("fail", errorMessage) on failure.
     */
    private fun restartAfterTerminal(
        wasWon: Boolean,
        callback: (status: String, error: String?) -> Unit
    ) {
        val service = Auto2048Service.instance
        if (service == null) {
            callback("fail", "Accessibility service is not running")
            return
        }
        // The "Try again" button sits in the lower-third of the modal
        // which itself is centred on the screen. Coordinates are
        // expressed as a fraction of (width, height).
        val cx = (screenWidth * 0.5f).toInt()
        val cy = (screenHeight * 0.62f).toInt()
        service.performTap(cx, cy) { res ->
            mainHandler.postDelayed({
                // After the tap we verify the modal is gone. If it is
                // still up we tap once more - the position is stable
                // across the 2048 build.
                screenProbe.probe(mediaProjection) { outcome, _ ->
                    if (outcome is ScreenProbeOutcome.Playing) {
                        callback("ok", null)
                    } else {
                        service.performTap(cx, cy) { _ ->
                            callback("ok", if (res == Auto2048Service.GestureResult.SUCCESS) null else "first tap rejected")
                        }
                    }
                }
            }, 350L)
        }
    }

    private fun handleSwipe(direction: String, result: Result) {
        val service = Auto2048Service.instance ?: run { result.error("SERVICE_OFF", "Enable Service", null); return }
        service.performSwipe(direction) { res ->
            mainHandler.post { 
                if (res == Auto2048Service.GestureResult.SUCCESS) result.success("SUCCESS")
                else result.error("FAIL", "Swipe fail", null)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_SCREEN_CAPTURE) return
        val res = pendingCaptureResult ?: return
        pendingCaptureResult = null
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            mediaProjectionData = data
            mediaProjectionResultCode = resultCode
            startFlow(res)
        } else {
            res.error("DENIED", "Permission denied", null)
        }
    }

    private fun handleCaptureScreen(result: Result) {
        if (pendingCaptureResult != null || captureInitializationInProgress) {
            result.error("BUSY", "Screen capture is already being initialized", null)
            return
        }

        val data = mediaProjectionData
        
        // Якщо сесія вже працює — просто робимо скріншот
        if (mediaProjection != null && imageReader != null && virtualDisplay != null) {
            Logger.d("Main", "Using active projection session")
            startAnalysis(result)
            return
        }

        // Якщо є збережений токен, пробуємо ініціалізувати (але Android 14 може це заблокувати)
        if (data != null) {
            Logger.d("Main", "Found cached token, trying to init session...")
            startFlow(result)
        } else {
            // Токена немає — просимо дозвіл у користувача
            Logger.d("Main", "No token found, requesting fresh permission")
            val manager = mediaProjectionManager
            if (manager != null) {
                pendingCaptureResult = result
                startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
            } else {
                result.error("FAIL", "No MediaProjectionManager", null)
            }
        }
    }

    private fun startFlow(result: Result) {
        if (captureInitializationInProgress) {
            result.error("BUSY", "Screen capture is already being initialized", null)
            return
        }
        captureInitializationInProgress = true

        try {
            MediaProjectionService.start(this)
        } catch (e: Exception) {
            captureInitializationInProgress = false
            Logger.e("Main", "Unable to start projection service", e)
            result.error("FAIL", "Unable to start screen capture service: ${e.message}", null)
            return
        }

        val thread = HandlerThread("Init").apply { start() }
        Handler(thread.looper).post {
            val success = try {
                MediaProjectionService.awaitForeground() && initCapture()
            } catch (e: Exception) {
                Logger.e("Main", "Capture initialization failed", e)
                false
            }

            mainHandler.post {
                captureInitializationInProgress = false
                if (success) {
                    startAnalysis(result)
                } else {
                    cleanup()
                    mediaProjectionData = null
                    val manager = mediaProjectionManager
                    if (manager == null) {
                        result.error("FAIL", "No MediaProjectionManager", null)
                    } else if (pendingCaptureResult != null) {
                        result.error("BUSY", "Capture permission request already in progress", null)
                    } else {
                        pendingCaptureResult = result
                        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
                    }
                }
            }
            thread.quitSafely()
        }
    }

    private fun initCapture(): Boolean {
        if (mediaProjection != null) return true
        val data = mediaProjectionData ?: return false
        
        try {
            projectionHandlerThread = HandlerThread("Capture").apply { start() }
            val h = Handler(projectionHandlerThread!!.looper)
            projectionHandler = h
            
            val mp: MediaProjection = mediaProjectionManager?.getMediaProjection(mediaProjectionResultCode, data) ?: return false
            
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Logger.d("Main", "MediaProjection STOPPED externally")
                    mainHandler.post {
                        // Clear the cached permission data so the next capture
                        // request triggers a fresh user consent prompt instead
                        // of attempting to reuse the revoked token (which on
                        // Android 14+ raises SecurityException and crashes
                        // MediaProjectionService).
                        mediaProjectionData = null
                        mediaProjectionResultCode = 0
                        cleanup()
                        if (solverRunning) {
                            stopSolverLoop("Screen capture permission ended")
                        }
                    }
                }
            }, h)
            
            mediaProjection = mp
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mp.createVirtualDisplay("BotCap", screenWidth, screenHeight, screenDensity, 
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, h)
            
            Logger.d("Main", "Session initialized successfully")
            return true
        } catch (e: Exception) { 
            Logger.e("Main", "CRITICAL: Init fail: ${e.message}")
            return false
        }
    }

    private fun startAnalysis(result: Result) {
        val reader = imageReader ?: run {
            Logger.e("Main", "Reader is null")
            result.error("FAIL", "No reader", null)
            return
        }
        val handler = projectionHandler ?: run {
            result.error("FAIL", "No capture handler", null)
            return
        }
        if (captureInProgress) {
            result.error("BUSY", "A screen capture is already in progress", null)
            return
        }

        captureInProgress = true
        val overlayVisible = OverlayService.instance != null
        OverlayService.instance?.setCaptureSuppressed(true)
        Logger.d("Main", "Requested frame analysis...")

        mainHandler.postDelayed({
            handler.post {
                try {
                    reader.acquireLatestImage()?.close()
                } catch (_: Exception) {
                }

                val completed = AtomicBoolean(false)
                val startTime = android.os.SystemClock.elapsedRealtime()

                reader.setOnImageAvailableListener({ availableReader ->
                    if (completed.get()) return@setOnImageAvailableListener

                    val image = availableReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    if (!completed.compareAndSet(false, true)) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    availableReader.setOnImageAvailableListener(null, null)
                    Logger.d("Main", "Frame received after ${android.os.SystemClock.elapsedRealtime() - startTime}ms")

                    try {
                        val bitmap = imageToBitmap(image)
                        val grid = try {
                            VisionProcessor.analyze(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                        val previousGrid = OverlayService.lastDetectedGrid
                        val gridChanged = previousGrid.size == 16 && grid.size == 16 && previousGrid != grid
                        OverlayService.lastDetectedGrid = if (grid.size == 16) grid else VisionProcessor.lastObservedGrid

                        mainHandler.post {
                            captureInProgress = false
                            if (gridChanged) {
                                OverlayService.instance?.updateHint("NONE")
                                if (!solverAutomatic) {
                                    solverHintState = "SCANNING"
                                    OverlayService.instance?.setHintState("SCANNING")
                                }
                            }
                            if (overlayVisible) OverlayService.instance?.setCaptureSuppressed(false)
                            result.success("GRID_STATE:" + grid.joinToString(","))
                        }
                    } catch (e: Exception) {
                        Logger.e("Main", "Frame analysis failed", e)
                        mainHandler.post {
                            captureInProgress = false
                            if (overlayVisible) OverlayService.instance?.setCaptureSuppressed(false)
                            result.error("ANALYSIS_FAIL", e.message, null)
                        }
                    } finally {
                        image.close()
                    }
                }, handler)

                mainHandler.postDelayed({
                    if (completed.compareAndSet(false, true)) {
                        Logger.w("Main", "Analysis TIMEOUT - no frames delivered")
                        reader.setOnImageAvailableListener(null, null)
                        captureInProgress = false
                        if (overlayVisible) OverlayService.instance?.setCaptureSuppressed(false)
                        result.error("TIMEOUT", "No frames from system", null)
                    }
                }, 5000)
            }
        }, if (overlayVisible) overlayCaptureDelayMs else 0L)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buf = plane.buffer
        val pS = plane.pixelStride
        val rS = plane.rowStride
        val pad = rS - pS * screenWidth
        val bmp = if (pad > 0) {
            val pBmp = Bitmap.createBitmap(screenWidth + pad / pS, screenHeight, Bitmap.Config.ARGB_8888)
            pBmp.copyPixelsFromBuffer(buf)
            val cropped = Bitmap.createBitmap(pBmp, 0, 0, screenWidth, screenHeight)
            pBmp.recycle()
            cropped
        } else {
            val eBmp = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            eBmp.copyPixelsFromBuffer(buf)
            eBmp
        }
        return bmp
    }

    private fun cleanup() {
        captureInProgress = false
        captureInitializationInProgress = false
        OverlayService.instance?.setCaptureSuppressed(false)
        pendingCaptureResult?.error("CANCELLED", "Screen capture was closed", null)
        pendingCaptureResult = null
        try { MediaProjectionService.stop(this) } catch (_: Exception) {}
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection = null
        projectionHandlerThread?.quitSafely(); projectionHandlerThread = null
        projectionHandler = null
    }

    override fun onDestroy() {
        stopSolverLoop()
        cleanup()
        if (::screenProbe.isInitialized) screenProbe.shutdown()
        super.onDestroy()
    }
}
