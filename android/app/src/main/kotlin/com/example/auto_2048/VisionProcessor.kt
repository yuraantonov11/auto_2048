package com.example.auto_2048

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object VisionProcessor {

    /**
     * Coarse classification of the currently captured screen. Used by the
     * native solver pipeline to short-circuit OCR when the screen is one of
     * the explicit terminal states ("Game Over", "You Win") and to drive an
     * automatic Restart tap.
     *
     *  - [Playing]    - the 4x4 board is present and tiles are populated.
     *  - [Won]        - the white "You Win" banner is overlaid above the board.
     *  - [GameOver]   - the red "Game Over" modal is overlaid on the board.
     *  - [Unknown]    - too little signal to decide (animation, lock screen,
     *                   permission popup, etc.) - caller should retry the probe
     *                   instead of trusting the result.
     */
    enum class ModalState { Playing, Won, GameOver, Unknown }

    private data class ColorMatch(
        val value: Int,
        val distance: Double,
        val ambiguous: Boolean
    )

    private data class CellObservation(
        val rgb: Triple<Int, Int, Int>,
        val match: ColorMatch
    )

    private data class OcrBoardResult(
        val values: MutableMap<Int, Int>,
        val completed: Boolean
    )

    @Volatile var lastModalState: ModalState = ModalState.Unknown
    @Volatile var lastModalDiagnostics: String = ""

    /**
     * One-shot flag: when set to `true` the next call to [analyze] will run
     * [detectBoard] again even if the user has manually calibrated the field.
     * Used by the stuck-state detector in [MainActivity] to self-heal when the
     * cached bounds look stale (e.g. every OCR cycle returns the exact same
     * grid while the on-screen board is clearly changing).
     */
    @Volatile var forceAutoDetect: Boolean = false

        /**
         * Monotonically-increasing capture generation. Bumped every time
         * [analyze] runs a successful frame. The probe (and any UI badge
         * depending on it) compares against its last seen generation to know
         * when the cached [lastModalState] / [lastModalDiagnostics] are
         * actually fresh. Without this, a probe running while no new frame
         * has been captured would happily return the modal state from a
         * frame seconds old.
         */
        @Volatile var lastCaptureGeneration: Long = 0L
            private set

    @Volatile var lastDebugCells: List<String> = emptyList()

    @Volatile var lastDiagnostics: String = ""
    @Volatile var lastObservedGrid: List<Int> = emptyList()
    @Volatile private var lastReliableGrid: List<Int> = emptyList()
    @Volatile private var lastReliableGridTimestampMs: Long = 0L
    @Volatile private var lastOcrVerifiedGrid: List<Int> = emptyList()
    @Volatile private var expectedBoard: List<Int> = emptyList()
    @Volatile private var learnedColors: Map<Int, IntArray> = emptyMap()
    private val gridFallbackMaxAgeMs = 8_000L
    private var applicationContext: android.content.Context? = null
    private var analysisCounter = 0

    data class CellSample(
        val rgb: Triple<Int, Int, Int>,
        val value: Int,
        val accepted: Boolean
    )

    data class CellSamples(
        val index: Int,
        val points: List<Pair<Float, Float>>,
        val samples: List<CellSample>
    )

    @Volatile var lastSamples: List<CellSamples> = emptyList()

    private const val LEARNED_COLOR_PREFS = "auto2048_learned_colors"
    private const val LEARNED_COLOR_KEY = "tile_colors_v1"

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun initialize(context: android.content.Context) {
        applicationContext = context.applicationContext
        learnedColors = loadLearnedColors(context)
    }

    fun setExpectedBoard(board: List<Int>) {
        expectedBoard = if (board.size == 16) board.toList() else emptyList()
    }

    fun clearExpectedBoard() {
        expectedBoard = emptyList()
    }

    /**
     * Cheap single-frame screen classifier. Does NOT run ML Kit OCR -
     * reads three coloured rectangles from the bitmap and decides whether
     * the player is currently in an active game, the "You Win" overlay,
     * the "Game Over" modal, or some unrecognised transition state.
     *
     * Used by the native solver loop as a fast terminal-state pre-check
     * (caller calls it BEFORE running the full OCR pipeline so that a
     * Game Over modal does not poison the grid reading).
     *
     * The heuristic relies on known colour signatures of the canonical
     * 2048 build:
     *  - "You Win" banner is a wide WHITE rectangle above the board.
     *  - "Game Over" modal is a translucent overlay tinted red/pink with
     *    bright white text - the area between tiles and game chrome is
     *    mostly covered by reddish pixels.
     *
     * The probe never blocks the UI thread for more than a millisecond
     * and never touches ML Kit - if either signal is absent, the state
     * defaults to [ModalState.Unknown] so callers retry instead of
     * guessing.
     */
    fun detectModalState(bitmap: Bitmap): ModalState {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            lastModalDiagnostics = "Bitmap is empty"
            lastModalState = ModalState.Unknown
            return ModalState.Unknown
        }

        // Sample three strips. Coordinates are intentionally coarse and
        // relative to screen size so the probe stays correct after a
        // device rotation or DPI change.
        val cx = width / 2
        val aboveBoard = (height * 0.18f).toInt().coerceIn(1, height - 1)
        val onBoard = (height * 0.50f).toInt().coerceIn(1, height - 1)
        val bannerRow = (height * 0.30f).toInt().coerceIn(1, height - 1)

        val aboveRgb = getMedianRGB(bitmap, cx, aboveBoard)
        val midRgb = getMedianRGB(bitmap, cx, onBoard)
        val bannerRgb = getMedianRGB(bitmap, cx, bannerRow)
        val (ar, ag, ab) = aboveRgb
        val (mr, mg, mb) = midRgb
        val (br, bg, bb) = bannerRgb

        // Win heuristic: a thick white banner. Both the row above the
        // board AND the row straddling the top of the board are bright
        // white-ish. A board with tiles would have at least one row
        // dominated by a coloured tile (bg ~22,21,37 or tile hues),
        // which never has all three channels above 230 simultaneously.
        val allBrightWhiteAbove =
            ar > 230 && ag > 230 && ab > 230 &&
            br > 230 && bg > 230 && bb > 230 &&
            (ar + ag + ab - ab - ab - ab) <= 30 // low chroma
        val allBrightWhiteOnBoard = mr > 230 && mg > 230 && mb > 230 && (mr + mg + mb - mb - mb - mb) <= 30
        // ftband Win heuristic: the "2048" banner appears above the board
        // at ~18% height and the area just above the board (~30%) also
        // brightens. Brightness above 200 on the upper strip with low
        // chroma signals the white win banner.
        val looksLikeWonFtb = ar > 200 && ag > 200 && ab > 200 &&
            (ar + ag + ab - ab - ab - ab) <= 40
        val looksLikeWon = looksLikeWonFtb || (allBrightWhiteAbove && allBrightWhiteOnBoard)

        // Game Over heuristic (ftband theme): the Game Over / Win overlay
        // is a grey translucent veil. When it covers the board the median
        // RGB of the board area becomes nearly uniform (all three channels
        // within ~20 of each other) AND the brightness rises above the
        // dark empty-cell baseline (~15-30 for ftband).
        // Normal gameplay shows high variation (tiles are colourful) with
        // dark values (~10-30). The grey overlay flattens both.
        val midRange = maxOf(mr, mg, mb) - minOf(mr, mg, mb)
        val midAvg = (mr + mg + mb) / 3f
        val looksLikeGreyOverlay = midRange < 20 && midAvg > 55

        // Fallback Game Over heuristic for classic red-overlay themes:
        // a translucent red/pink veil stretches over most of the board.
        // We detect it by demanding red dominance both above and on the
        // board centre, with non-trivial saturation. Tiles never cluster
        // as solid red - the highest-saturation red pixel class in 2048
        // belongs to the "32" tile (~245,75,80) at a single tile, not as
        // the median of a 60-px strip.
        val redAbove = ar.toFloat() - (ag + ab) / 2f
        val redMid = mr.toFloat() - (mg + mb) / 2f
        val looksLikeRedOverlay = redAbove > 60f && redMid > 50f

        val looksLikeGameOver = looksLikeGreyOverlay || looksLikeRedOverlay

        val state = when {
            looksLikeWon -> ModalState.Won
            looksLikeGameOver -> ModalState.GameOver
            else -> ModalState.Playing
        }
        lastModalState = state
        lastModalDiagnostics = buildString {
            append("probe above=(").append(ar).append(',').append(ag).append(',').append(ab).append(')')
            append(" mid=(").append(mr).append(',').append(mg).append(',').append(mb).append(')')
            append(" banner=(").append(br).append(',').append(bg).append(',').append(bb).append(')')
            append(" -> ").append(state.name)
        }
        return state
    }

    fun analyze(bitmap: Bitmap): List<Int> {
        // Auto-detect the board when the user hasn't pinned manual bounds,
        // OR when the detector has been requested again (forceAutoDetect)
        // because the cached bounds look stale (e.g. every iteration returns
        // the exact same OCR grid and the on-screen board is changing).
        if (!GameConfig.isManualMode || forceAutoDetect) {
            detectBoard(bitmap)
            if (forceAutoDetect) {
                Log.d("Auto2048", "analyze: forced re-detect, new bounds x[${GameConfig.detectedXStart},${GameConfig.detectedXEnd}] y[${GameConfig.detectedYStart},${GameConfig.detectedYEnd}]")
                forceAutoDetect = false
            }
        }

        // Always refresh modal state first, regardless of board bounds validity.
        // detectModalState samples the full bitmap and doesn't depend on board coordinates.
        refreshModalCache(bitmap)

        val xStart = GameConfig.detectedXStart
        val xEnd = GameConfig.detectedXEnd
        val yStart = GameConfig.detectedYStart
        val yEnd = GameConfig.detectedYEnd
        if (xStart < 0 || yStart < 0 || xEnd <= xStart || yEnd <= yStart ||
            xEnd - xStart < 100 || yEnd - yStart < 100) {
            lastDiagnostics = "Board bounds are invalid"
            lastObservedGrid = emptyList()
            OverlayService.samplePoints = emptyList()
            OverlayService.uncertainCells = emptySet()
            Log.d("Auto2048", "analyze: Board bounds invalid xStart=$xStart xEnd=$xEnd yStart=$yStart yEnd=$yEnd")
            return emptyList()
        }

        val scaleX = if (GameConfig.screenWidth > 0) {
            bitmap.width.toFloat() / GameConfig.screenWidth
        } else {
            1f
        }
        val scaleY = if (GameConfig.screenHeight > 0) {
            bitmap.height.toFloat() / GameConfig.screenHeight
        } else {
            1f
        }

        val cellHeight = (yEnd - yStart) / 4f
        val cellWidth = (xEnd - xStart) / 4f
        if (cellWidth <= 1f || cellHeight <= 1f) {
            lastDiagnostics = "Board cells are too small"
            lastObservedGrid = emptyList()
            OverlayService.samplePoints = emptyList()
            OverlayService.uncertainCells = emptySet()
            Log.d("Auto2048", "analyze: Cells too small cellWidth=$cellWidth cellHeight=$cellHeight")
            return emptyList()
        }

        // Six sample points arranged in two horizontal rows near the top and
        // bottom of the tile. Deliberately no points at the vertical
        // centre and no points at x=0.50 because wide digit strings
        // (3 digits like 512 / 1024 / 2048 occupy roughly x=0.15..0.85 of
        // the tile, 4 digits like 4096 / 8192 occupy even more) would
        // catch the digit pixels and skew the median colour. The top
        // (y=0.20) row sits above the digit area; the bottom row uses
        // y=0.75 (slightly higher than the symmetric 0.80) so the rendered
        // dot stays comfortably inside the calibration frame on devices
        // where the detected board edge sits flush against the actual
        // board edge - the 0.80 dot could otherwise visually overlap or
        // breach the green outline.
        val inset = GameConfig.SAMPLE_INSET_FRACTION
        val rightInset = 1f - inset
        val bottomInset = 1f - GameConfig.BOTTOM_SAMPLE_INSET_FRACTION
        val samplePositions = arrayOf(
            // Top row - above the digit area
            inset to inset,
            0.5f to inset,
            rightInset to inset,
            // Bottom row - below the digit area, with extra margin from the edge
            inset to bottomInset,
            0.5f to bottomInset,
            rightInset to bottomInset
        )
        val observations = ArrayList<CellObservation>(16)
        val cellSamples = ArrayList<CellSamples>(16)
        val provisionalGrid = MutableList(16) { -1 }
        val needsOcr = linkedSetOf<Int>()
        val expectedSnapshot = expectedBoard
        val debugCells = ArrayList<String>(16)
        analysisCounter = (analysisCounter + 1) % 1000
        val verifyHighTiles = analysisCounter % 4 == 0
        // Count of cells bright enough to look like real game content.
        // Used after the loop to bail out (return emptyList) when the whole
        // board is a darkened auth popup / system dialog.
        var nonGameBrightCells = 0

        for (row in 0..3) {
            for (col in 0..3) {
                val index = row * 4 + col
                val cellResult = sampleCell(
                    bitmap = bitmap,
                    index = index,
                    baseX = xStart + col * cellWidth,
                    baseY = yStart + row * cellHeight,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    xStart = xStart.toFloat(),
                    xEnd = xEnd.toFloat(),
                    yStart = yStart.toFloat(),
                    yEnd = yEnd.toFloat(),
                    samplePositions = samplePositions
                )
                cellSamples.add(cellResult.cellSamples)

                val (trustedVotedValue, representativeRgb, resolvedMatch) = Triple(
                    cellResult.trustedValue,
                    cellResult.representativeRgb,
                    cellResult.resolvedMatch
                )

                // Aggregate a "screen health" signal: how many cells are clearly
                // visible (bright enough to be a real game tile vs an auth
                // popup / system dialog with a darkened background). The
                // final pause/resume decision is made after the loop using
                // [nonGameBrightCells] so we only pause when the whole board
                // is unreadable, not when one dark tile sits on the board.
                val (r, g, b) = representativeRgb
                val brightness = (r + g + b) / 3
                if (brightness >= GameConfig.NON_GAME_BRIGHTNESS_THRESHOLD) {
                    nonGameBrightCells++
                }
                observations.add(CellObservation(representativeRgb, resolvedMatch))
                if (trustedVotedValue != null && !resolvedMatch.ambiguous) {
                    // Mid-range tiles (64/128/256/512/1024) are NEVER pre-committed
                    // by colour alone. On the user's theme the 64 tile shares its
                    // colour with canonical 128, and the 256 tile sits within ~2
                    // RGB units of canonical 128, so a unanimous colour vote for
                    // "128" cannot be told apart from a real 64/256. Leaving
                    // such cells at -1 until OCR confirms is the only way to
                    // avoid emitting the wrong mid-range value.
                    if (trustedVotedValue !in GameConfig.MID_RANGE_TILES) {
                        provisionalGrid[index] = trustedVotedValue
                        if (trustedVotedValue >= 1024 && verifyHighTiles) {
                            needsOcr.add(index)
                        }
                    } else {
                        needsOcr.add(index)
                    }
                } else {
                    needsOcr.add(index)
                }
                debugCells.add(
                    "($row,$col)=${if (provisionalGrid[index] < 0) "?" else provisionalGrid[index]}" +
                        " rgb=${representativeRgb.first},${representativeRgb.second},${representativeRgb.third}" +
                        " d=${"%.1f".format(resolvedMatch.distance)}" +
                        " v=${resolvedMatch.value}"
                )

                // Restore the predicted post-swipe board for every cell where
                // the colour vote is empty or untrusted. This prevents
                // stale tiles like a 64 being read as 1024 simply because
                // a single noisy corner matched the 1024 reference.
                val expectedValue = expectedSnapshot.getOrNull(index) ?: 0
                if (expectedValue > 0 && cellResult.sampledColors.isNotEmpty()) {
                    val validated = validateAgainstExpected(
                        expectedValue,
                        representativeRgb,
                        resolvedMatch
                    )
                    if (validated) {
                        provisionalGrid[index] = expectedValue
                        needsOcr.remove(index)
                    }
                }
            }
        }

        // Pause/resume gate: when the captured frame is a darkened overlay
        // (auth popup, "continue watching?" dialog, system permission sheet,
        // etc.), every sampled cell is dark and uniform. Returning
        // emptyList() here makes the solver skip this frame without
        // committing a grid of fake "empty" tiles, and the next frame is
        // re-evaluated automatically - so when the popup closes and the
        // 2048 board reappears the bot resumes without any user action.
        if (nonGameBrightCells < GameConfig.NON_GAME_MIN_BRIGHT_CELLS) {
            lastDiagnostics = "Paused: board not visible (popup/lock screen?)"
            lastObservedGrid = emptyList()
            OverlayService.samplePoints = emptyList()
            OverlayService.uncertainCells = emptySet()
                    refreshModalCache(bitmap)
                    return emptyList()
                }

        lastSamples = cellSamples
        if (cellSamples.isNotEmpty()) {
            OverlayService.lastSamplePoints = cellSamples
        }
        lastDebugCells = debugCells
        Log.d("Auto2048", "vision-debug " + debugCells.joinToString(" "))
        expectedBoard = emptyList()

        val ocrResult = if (needsOcr.isNotEmpty()) {
            recognizeBoardValues(bitmap, scaleX, scaleY)
        } else {
            OcrBoardResult(mutableMapOf(), true)
        }

        // Drop any OCR value of -1 (ML Kit's no-digit sentinel) so it is
        // not treated as a real tile value. The cell will fall back to
        // colour or lastReliableGrid below.
        if (ocrResult.completed) {
            for (key in ocrResult.values.keys.toList()) {
                if ((ocrResult.values[key] ?: 0) <= 0) {
                    ocrResult.values.remove(key)
                }
            }
        }

        if (!ocrResult.completed) {
            val recovered = provisionalGrid.toMutableList()
            val unresolved = linkedSetOf<Int>()
            for (index in needsOcr) {
                val previous = lastOcrVerifiedGrid.getOrNull(index) ?: 0
                val expectedValue = expectedSnapshot.getOrNull(index)
                val observation = observations[index]
                val stronglyEmpty = observation.match.value == 0 &&
                    !observation.match.ambiguous && observation.match.distance <= GameConfig.EMPTY_CELL_MAX_DISTANCE
                when {
                    expectedValue == 0 && stronglyEmpty -> recovered[index] = 0
                    expectedValue == 0 -> unresolved.add(index)
                    recovered[index] >= 0 -> Unit
                    // Only allow mid-range tiles from lastOcrVerifiedGrid (OCR-verified only).
                    previous > 0 && (provisionalGrid[index] !in GameConfig.MID_RANGE_TILES ||
                        lastOcrVerifiedGrid.getOrNull(index) == provisionalGrid[index]) ->
                        recovered[index] = previous
                    stronglyEmpty -> recovered[index] = 0
                    else -> unresolved.add(index)
                }
            }

            lastObservedGrid = recovered
            lastDiagnostics = if (unresolved.isEmpty()) {
                "OCR unavailable; stable high-tile values preserved"
            } else {
                "OCR unavailable; ${unresolved.size} cells unresolved"
            }
            OverlayService.samplePoints = cellSamples.flatMap { it.points }
            OverlayService.uncertainCells = unresolved
            // Commit the recovered grid even when some cells are unresolved -
            // a partial view is better than hanging. lastOcrVerifiedGrid still
            // guards against "stuck on wrong value" because unresolved mid-range
            // cells were never persisted there.
            if (recovered.none { it < 0 }) {
                lastReliableGrid = recovered.toList()
                            refreshModalCache(bitmap)
                            return recovered
                        }
                        refreshModalCache(bitmap)
                        return emptyList()
        }

        // Fresh boards have no expected snapshot, no lastReliableGrid, and
        // colour voting ignores 2/4, so we must trust OCR for the very
        // first frames or the bot will treat 2 as 0 and refuse to move.
        val firstFrame = expectedSnapshot.isEmpty() && lastReliableGrid.isEmpty()
        val uncertain = linkedSetOf<Int>()
        for (index in needsOcr) {
            val resolved = resolveUncertainCell(
                index = index,
                ocrResult = ocrResult,
                observation = observations[index],
                previous = lastOcrVerifiedGrid.getOrNull(index) ?: 0,
                expectedValue = expectedSnapshot.getOrNull(index),
                firstFrame = firstFrame
            )
            when (resolved) {
                is ResolvedCell.Found -> provisionalGrid[index] = resolved.value
                ResolvedCell.Uncertain -> uncertain.add(index)
            }
        }

        lastObservedGrid = provisionalGrid.toList()
        OverlayService.samplePoints = cellSamples.flatMap { it.points }
        OverlayService.uncertainCells = uncertain
        
        // Track which cells had OCR confirmation this frame so we can
        // distinguish OCR-verified mid-range values from colour-only commits.
        val ocrConfirmedThisFrame = provisionalGrid.indices
            .filter { provisionalGrid[it] > 0 && ocrResult.values.containsKey(it) }
            .toSet()
        // Build a grid of only OCR-confirmed values. Mid-range tiles that
        // were colour-committed without OCR will NOT be stored here, so
        // they cannot "stick" via the previous > 0 fallback.
        val ocrVerifiedValues = provisionalGrid.mapIndexed { index, value ->
            if (value > 0 && index in ocrConfirmedThisFrame) value else 0
        }
        lastOcrVerifiedGrid = ocrVerifiedValues
        
        lastDiagnostics = if (needsOcr.isEmpty()) {
            "Color recognition: 16/16"
        } else {
            "OCR fallback: ${ocrResult.values.size}, uncertain: ${uncertain.size}"
        }
        Log.d("Auto2048", "$lastDiagnostics; grid=${provisionalGrid.joinToString(",")}")
        // Commit the grid even if some cells are uncertain. lastOcrVerifiedGrid
        // only holds OCR-confirmed values, so mid-range tiles that were
        // colour-committed without OCR will be retried next frame and will
        // NOT be returned by the "previous > 0" fallback (no more "stuck on
        // wrong value" cycles). Rejecting the whole frame on any uncertain
        // cell used to leave the bot hanging on the first frame where OCR
        // missed a single digit.
        // Build the grid returned to the solver. Cells that were NOT OCR-confirmed
        // this frame (e.g. occluded by the cat-paw animation) are marked -1 so the
        // solver knows to use lastReliableGrid for those cells.
        //
        // Additionally, detect ghost reads: when a cell showed as 0 in the last
        // reliable grid but is now OCR-reading as 2 or 4, the cat-paw shadow is
        // likely mimicking a small tile. Mark it as -1 so temporal smoothing replaces
        // it with the known-good 0 from lastReliableGrid.
        val ghostReadIndices = if (lastReliableGrid.size == 16 && lastReliableGridTimestampMs > 0) {
            provisionalGrid.indices.filter { idx ->
                val wasEmpty = lastReliableGrid[idx] == 0
                val nowSmall = provisionalGrid[idx] in GameConfig.SMALL_TILES
                wasEmpty && nowSmall && idx !in ocrConfirmedThisFrame
            }.toSet()
        } else {
            emptySet()
        }
        val solverGrid = provisionalGrid.mapIndexed { index, value ->
            when {
                index in ocrConfirmedThisFrame && index !in ghostReadIndices -> value
                else -> -1
            }
        }
        val hasUncertain = solverGrid.any { it < 0 }

        return if (provisionalGrid.none { it < 0 }) {
            if (expectedSnapshot.isEmpty()) {
                // Only learn colours for OCR-confirmed cells. rememberLearnedColor
                // no longer sanity-checks against canonical references (the user's
                // theme uses different colours), so it must only be fed values
                // that OCR read directly to avoid pollution.
                var learnedColorChanged = false
                for (index in ocrConfirmedThisFrame) {
                    if (provisionalGrid[index] <= 0) continue
                    learnedColorChanged = rememberLearnedColor(
                        provisionalGrid[index],
                        observations[index].rgb
                    ) || learnedColorChanged
                }
                if (learnedColorChanged) persistLearnedColors()
            }
            // All 16 cells were OCR-confirmed — this is a fully reliable grid.
            lastReliableGrid = provisionalGrid.toList()
            lastReliableGridTimestampMs = android.os.SystemClock.elapsedRealtime()
            refreshModalCache(bitmap)
            Log.d("Auto2048", "analyze: All 16 OCR-confirmed, grid=${provisionalGrid.joinToString(",")}")
            solverGrid
        } else if (lastReliableGrid.size == 16) {
            // Some cells were not OCR-confirmed. If we have a recent reliable grid,
            // overwrite the -1 entries with the last known good values so the
            // solver works with a complete board. This is the temporal smoothing
            // for cat-paw occlusions.
            val ageMs = android.os.SystemClock.elapsedRealtime() - lastReliableGridTimestampMs
            if (ageMs <= gridFallbackMaxAgeMs) {
                val smoothedGrid = solverGrid.mapIndexed { index, value ->
                    if (value < 0) lastReliableGrid.getOrElse(index) { 0 } else value
                }
                Log.d("Auto2048", "Temporal smoothing: ${solverGrid.count { it < 0 }}/16 cells uncertain (age=${ageMs}ms)")
                refreshModalCache(bitmap)
                smoothedGrid
            } else {
                // Fallback grid is stale; just return what we have (may have -1 cells).
                lastReliableGrid = provisionalGrid.toList()
                lastReliableGridTimestampMs = android.os.SystemClock.elapsedRealtime()
                refreshModalCache(bitmap)
                Log.d("Auto2048", "analyze: Fallback stale, grid=${provisionalGrid.joinToString(",")}")
                solverGrid
            }
        } else {
            // No reliable grid available; commit what we have (cells may be -1).
            lastReliableGrid = provisionalGrid.toList()
            lastReliableGridTimestampMs = android.os.SystemClock.elapsedRealtime()
            refreshModalCache(bitmap)
            Log.d("Auto2048", "analyze: No reliable grid, grid=${provisionalGrid.joinToString(",")}")
            solverGrid
        }
    }

    /**
     * Update the cached modal state used by [ScreenProbe]. Called from
     * inside [analyze] on the SAME bitmap that produced the grid so the
     * probe never has to allocate its own [android.media.projection]
     * capture session. Cheap: three RGB samples, no ML Kit, no
     * VirtualDisplay.
     */
    private fun refreshModalCache(bitmap: Bitmap) {
        try {
            detectModalState(bitmap)
            lastCaptureGeneration++
        } catch (_: Exception) {
            // detectModalState never throws on its own; the try/catch
            // exists purely so a malformed bitmap (zero size, recycled)
            // can never abort the grid analysis that just succeeded.
        }
    }

    /**
     * Aggregated result of sampling a single board cell: where we sampled,
     * the per-sample classifier outcomes, the colour vote tallies, the
     * representative median colour, and the trusted voted value (if any).
     */
    private data class CellSampleResult(
        val cellSamples: CellSamples,
        val sampledColors: List<Triple<Int, Int, Int>>,
        val representativeRgb: Triple<Int, Int, Int>,
        val resolvedMatch: ColorMatch,
        val trustedValue: Int?
    )

    /**
     * Outcome of resolving a single uncertain cell during the OCR merge
     * stage. Either a concrete value (from OCR, colour, expected board,
     * or the previous reliable frame) or no decision at all - in which
     * case the cell stays in [uncertain] for the next frame.
     */
    private sealed class ResolvedCell {
        data class Found(val value: Int) : ResolvedCell()
        data object Uncertain : ResolvedCell()
    }

    /**
     * Samples [samplePositions] for a single cell of the board, runs the
     * colour classifier on each sample, and returns the aggregated result
     * including the trusted voted value (mid-range tiles require a stricter
     * vote threshold than the default).
     */
    private fun sampleCell(
        bitmap: Bitmap,
        index: Int,
        baseX: Float,
        baseY: Float,
        cellWidth: Float,
        cellHeight: Float,
        scaleX: Float,
        scaleY: Float,
        xStart: Float,
        xEnd: Float,
        yStart: Float,
        yEnd: Float,
        samplePositions: Array<Pair<Float, Float>>
    ): CellSampleResult {
        val cellSamplePoints = mutableListOf<Pair<Float, Float>>()
        val cellSampleData = mutableListOf<CellSample>()
        val votes = mutableMapOf<Int, Int>()
        val sampledColors = mutableListOf<Triple<Int, Int, Int>>()

        for (position in samplePositions) {
            val screenX = baseX + cellWidth * position.first
            val screenY = baseY + cellHeight * position.second
            val bitmapX = (screenX * scaleX).toInt()
            val bitmapY = (screenY * scaleY).toInt()

            cellSamplePoints.add(
                (screenX - OverlayService.overlayOffsetX) to
                    (screenY - OverlayService.overlayOffsetY)
            )
            val insideBoard = screenX >= xStart.toDouble() && screenX <= xEnd.toDouble() &&
                screenY >= yStart.toDouble() && screenY <= yEnd.toDouble()
            val inBounds = bitmapX in 3 until bitmap.width - 3 &&
                bitmapY in 3 until bitmap.height - 3
            if (!inBounds || !insideBoard) {
                cellSampleData.add(CellSample(Triple(0, 0, 0), 0, false))
                continue
            }

            val rgb = getMedianRGB(bitmap, bitmapX, bitmapY)
            if (rgb.first == 0 && rgb.second == 0 && rgb.third == 0) {
                cellSampleData.add(CellSample(rgb, 0, false))
                continue
            }
            if (rgb.first > 220 && rgb.second > 220 && rgb.third > 220) {
                cellSampleData.add(CellSample(rgb, 0, false))
                continue
            }
            sampledColors.add(rgb)
            val match = classifyColor(rgb.first, rgb.second, rgb.third)
            val accepted = !match.ambiguous &&
                match.distance <= GameConfig.MAX_COLOR_DISTANCE &&
                match.value != 0
            cellSampleData.add(CellSample(rgb, match.value, accepted))
            if (accepted) {
                votes[match.value] = (votes[match.value] ?: 0) + 1
            }
        }

        val cellSamples = CellSamples(index, cellSamplePoints, cellSampleData)

        val votedValue = votes.maxByOrNull { it.value }?.key
        val dominantVotes = votes[votedValue] ?: 0
        // Tile 2 is so close to the board background colour that colour
        // voting is unreliable; OCR must confirm it. Other values,
        // including tile 4 (clearly distinguishable from the background
        // at distance ~100), are accepted from colour voting once enough
        // samples agree. Mid-range tiles (64/128/256/1024) demand a
        // stricter majority because their hues can overlap with
        // neighbouring values on some themes.
        val requiredVotes = when {
            votedValue == 2 -> Int.MAX_VALUE  // tile 2 -> always OCR
            votedValue in GameConfig.MID_RANGE_TILES ->
                GameConfig.STRICT_VOTE_THRESHOLD
            else -> GameConfig.DEFAULT_VOTE_THRESHOLD
        }
        val trustedValue = votedValue?.takeIf {
            it != 0 && dominantVotes >= requiredVotes
        }
        val representativeRgb = medianColor(sampledColors)
        val resolvedMatch = classifyColor(
            representativeRgb.first,
            representativeRgb.second,
            representativeRgb.third
        )
        return CellSampleResult(
            cellSamples = cellSamples,
            sampledColors = sampledColors,
            representativeRgb = representativeRgb,
            resolvedMatch = resolvedMatch,
            trustedValue = trustedValue
        )
    }

    /**
     * Resolves a single cell that the colour-vote phase could not commit
     * to. Folds together OCR results, the representative colour, the
     * expected board, and the previous reliable frame, returning either a
     * concrete tile value or [ResolvedCell.Uncertain].
     *
     * Order of preference:
     *  1. Fresh frame + OCR (no expected board yet) -> trust OCR.
     *  2. OCR and colour agree -> trust OCR.
     *  3. OCR for small (2/4) tiles when colour is ambiguous or far.
     *  4. Mid-range (64/128/256) disagreement: when colour and OCR
     *     both pick a mid-range tile but disagree, prefer OCR - colour
     *     distances between 128/256 are only ~45 units, so the classifier
     *     is unreliable, but OCR reads the digit directly.
     *  5. Reliable colour wins otherwise (especially for >=8 tiles).
     *  6. Fall back to lastReliableGrid if available, else Uncertain.
     */
    private fun resolveUncertainCell(
        index: Int,
        ocrResult: OcrBoardResult,
        observation: CellObservation,
        previous: Int,
        expectedValue: Int?,
        firstFrame: Boolean
    ): ResolvedCell {
        val ocrValue = ocrResult.values[index] ?: 0
        val colorReliable = !observation.match.ambiguous &&
            observation.match.distance <= GameConfig.MAX_COLOR_DISTANCE
        val stronglyEmpty = colorReliable && observation.match.value == 0 &&
            observation.match.distance <= GameConfig.EMPTY_CELL_MAX_DISTANCE
        // OCR result -1 is the "no digits found" sentinel from ML Kit;
        // treat it as no-result rather than a real tile value.
        val ocrRealValue = if (ocrValue > 0) ocrValue else 0
        val ocrIsReal = ocrRealValue > 0
        val ocrIsSmall = ocrRealValue == 2 || ocrRealValue == 4
        val ocrAgreesWithColor = ocrRealValue == observation.match.value
        val ocrIsVeryHigh = ocrRealValue >= 1024

        return when {
            firstFrame && ocrIsReal -> ResolvedCell.Found(ocrRealValue)
            // Empty-cell detection: when the cell is genuinely empty and
            // OCR confirms no digit, commit 0. Otherwise fall through to
            // colour-based recovery.
            expectedValue == 0 && stronglyEmpty -> ResolvedCell.Found(0)
            // Mid-range tiles (64/128/256/512/1024) are the primary source of
            // "stuck on wrong value" bugs: the colour classifier can return the
            // wrong shade (e.g. 128 instead of 64 on a theme that re-uses the
            // 128-orange for tile 64), and if we commit that value here it
            // gets stored in lastReliableGrid and then returned by
            // "previous > 0" on every subsequent frame where OCR fails — the
            // value is now stuck. The fix: mid-range tiles that were not
            // OCR-confirmed in this frame are NOT committed by colour alone.
            // They return Uncertain, the frame is rejected, and the next frame
            // gets a fresh OCR attempt. This is much better than being locked
            // into a wrong value. The first-frame branch above still fires
            // when OCR returned something real, so fresh boards are not affected.
            colorReliable && observation.match.value !in GameConfig.MID_RANGE_TILES &&
                observation.match.value !in GameConfig.SMALL_TILES -> ResolvedCell.Found(observation.match.value)
            // OCR may override colour for the smallest tiles (2/4) when colour
            // could not recognise them and OCR does not return a wildly
            // high value.
            ocrIsReal && !ocrIsVeryHigh && ocrIsSmall &&
                (observation.match.ambiguous || observation.match.distance > GameConfig.RELIABLE_COLOR_DISTANCE) ->
                ResolvedCell.Found(ocrRealValue)
            // Mid-range tiles (64/128/256/512/1024) have hues that are visually
            // close to neighbouring values (e.g. 128 vs 256 are only 45
            // units apart). Some 2048 themes also use the canonical "128"
            // colour for tile 64, so a *very confident* colour match
            // (distance 8-10) can still be wrong. When colour and OCR
            // disagree on a mid-range tile, always prefer OCR - it reads
            // the digit directly and is far more reliable.
            ocrIsReal && observation.match.value in GameConfig.MID_RANGE_TILES &&
                ocrRealValue in GameConfig.MID_RANGE_TILES &&
                observation.match.value != ocrRealValue ->
                ResolvedCell.Found(ocrRealValue)
            ocrIsReal && colorReliable && ocrAgreesWithColor -> ResolvedCell.Found(ocrRealValue)
            // Mid-range tiles (64/128/256/512/1024) when OCR hasn't confirmed
            // them: prefer a previously OCR-confirmed value over going to -1.
            // lastOcrVerifiedGrid only stores OCR-confirmed positive values, so
            // the previous > 0 path is safe - no "stuck on colour vote" cycle.
            // Only fall back to Uncertain when no prior OCR confirmation exists.
            previous > 0 -> ResolvedCell.Found(previous)
            observation.match.value in GameConfig.MID_RANGE_TILES -> ResolvedCell.Uncertain
            else -> ResolvedCell.Uncertain
        }
    }

    private fun detectBoard(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height

        val startScan = (h * GameConfig.SCAN_Y_START).toInt()
        val endScan = (h * GameConfig.SCAN_Y_END).toInt()

        // TILE-BASED detection: scan every row inside [startScan..endScan]
        // and count how many pixels in that row match a known tile colour
        // (ANY value except pure background). A row with many tile pixels
        // must lie inside the playable board; a row with only background
        // pixels is the gap ABOVE or BELOW the board.
        //
        // This is more reliable than background-only scanning because the
        // ftband board has a thin "frame" of the background colour between
        // rows that the old detector sometimes mistook for the playable
        // area itself, causing OCR to read cells shifted by one row.
        val bg = GameConfig.BOARD_BACKGROUND_RGB
        val tolerance = GameConfig.COLOR_TOLERANCE + 5
        val step = 4
        val rowTileFraction = FloatArray(endScan - startScan)
        var maxY = 0
        var minY = endScan
        for (yi in rowTileFraction.indices) {
            val y = startScan + yi * step
            if (y >= h) break
            var tiles = 0
            var total = 0
            for (x in 0 until w step 8) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                total++
                if (kotlin.math.abs(r - bg[0]) > tolerance ||
                    kotlin.math.abs(g - bg[1]) > tolerance ||
                    kotlin.math.abs(b - bg[2]) > tolerance
                ) tiles++
            }
            val frac = if (total > 0) tiles.toFloat() / total else 0f
            rowTileFraction[yi] = frac
            // A row counts as "inside the board" if >= 8% of its pixels are
            // tiles. This is well below the ~30% we expect on a real row
            // and well above the ~0% we'd get in the area above/below the
            // board (where there might be UI chrome but no tiles).
            if (frac >= 0.08f) {
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (minY < maxY && (maxY - minY) > 200) {
            // Include a small padding so the OCR doesn't sample right on the
            // outer tile edge where antialiasing makes colour classification
            // unreliable.
            val pad = 8
            val firstY = (minY - pad).coerceAtLeast(0)
            val lastY = (maxY + pad).coerceAtMost(h - 1)
            val boardSize = lastY - firstY

            // Horizontal bounds: same idea, but only inside [firstY..lastY].
            var minX = w
            var maxX = 0
            for (x in 0 until w step 4) {
                for (y in firstY..lastY step 4) {
                    val p = bitmap.getPixel(x, y)
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    if (kotlin.math.abs(r - bg[0]) > tolerance ||
                        kotlin.math.abs(g - bg[1]) > tolerance ||
                        kotlin.math.abs(b - bg[2]) > tolerance
                    ) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                    }
                }
            }
            // If horizontal scan didn't find anything (sparse tiles), fall
            // back to centering a square based on the vertical extent.
            // Clamp minX to 0 to avoid negative coordinates when boardSize > screen width.
            if (minX >= maxX) {
                val cx = w / 2
                minX = (cx - boardSize / 2).coerceAtLeast(0)
                maxX = minX + boardSize
            } else {
                minX = (minX - pad).coerceAtLeast(0)
                maxX = (maxX + pad).coerceAtMost(w - 1)
                val detectedWidth = maxX - minX
                if (kotlin.math.abs(detectedWidth - boardSize) > boardSize / 4) {
                    // Width is suspiciously different from height - force
                    // a square frame centred on the vertical extent.
                    val cx = (minX + maxX) / 2
                    minX = (cx - boardSize / 2).coerceAtLeast(0)
                    maxX = minX + boardSize
                }
            }

            val screenScaleX = if (GameConfig.screenWidth > 0) GameConfig.screenWidth.toFloat() / w else 1f
            val screenScaleY = if (GameConfig.screenHeight > 0) GameConfig.screenHeight.toFloat() / h else 1f

            GameConfig.detectedYStart = (firstY * screenScaleY).toInt()
            GameConfig.detectedYEnd = (lastY * screenScaleY).toInt()
            GameConfig.detectedXStart = (minX * screenScaleX).toInt()
            GameConfig.detectedXEnd = (maxX * screenScaleX).toInt()
            Log.d(
                "Auto2048",
                "detectBoard: FOUND tile-based minY=$minY maxY=$maxY size=$boardSize minX=$minX maxX=$maxX → screen x[${GameConfig.detectedXStart},${GameConfig.detectedXEnd}] y[${GameConfig.detectedYStart},${GameConfig.detectedYEnd}]"
            )
        } else {
            GameConfig.detectedYStart = 0
            GameConfig.detectedYEnd = 0
            GameConfig.detectedXStart = 0
            GameConfig.detectedXEnd = 0
            // Log a sample of what's actually inside the scan band to help
            // diagnose colour / threshold mismatches.
            val sampleY1 = startScan + (endScan - startScan) / 4
            val sampleY2 = startScan + (endScan - startScan) / 2
            val sampleY3 = startScan + (endScan - startScan) * 3 / 4
            val p1 = bitmap.getPixel(w / 2, sampleY1)
            val p2 = bitmap.getPixel(w / 2, sampleY2)
            val p3 = bitmap.getPixel(w / 2, sampleY3)
            Log.d(
                "Auto2048",
                "detectBoard: NOT FOUND scan[$startScan..$endScan] pixels: y$sampleY1=(${Color.red(p1)},${Color.green(p1)},${Color.blue(p1)}) y$sampleY2=(${Color.red(p2)},${Color.green(p2)},${Color.blue(p2)}) y$sampleY3=(${Color.red(p3)},${Color.green(p3)},${Color.blue(p3)})"
            )
        }
    }

    private fun isBoardColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val ref = GameConfig.BOARD_BACKGROUND_RGB
        return abs(r - ref[0]) < 20 && abs(g - ref[1]) < 20 && abs(b - ref[2]) < 20
    }

    private fun getMedianRGB(bitmap: Bitmap, cx: Int, cy: Int): Triple<Int, Int, Int> {
        val rs = mutableListOf<Int>()
        val gs = mutableListOf<Int>()
        val bs = mutableListOf<Int>()

        val w = bitmap.width
        val h = bitmap.height

        for (dx in -2..2) {
            for (dy in -2..2) {
                // Безпечне зчитування пікселів
                val px = cx + dx
                val py = cy + dy
                if (px >= 0 && py >= 0 && px < w && py < h) {
                    val p = bitmap.getPixel(px, py)
                    rs.add(Color.red(p))
                    gs.add(Color.green(p))
                    bs.add(Color.blue(p))
                }
            }
        }
        
        if (rs.isEmpty()) return Triple(0, 0, 0)
        
        rs.sort(); gs.sort(); bs.sort()
        val mid = rs.size / 2
        return Triple(rs[mid], gs[mid], bs[mid])
    }

    private fun classifyColor(r: Int, g: Int, b: Int): ColorMatch {
        // Prefer learned colours when available — the user is playing a specific
        // third-party 2048 theme and those colours ARE the correct references.
        // Only fall back to canonical TILE_COLORS when no learned colour exists.
        // The old canonicalDriftLimit (~30) was far too strict for themes with
        // completely different palettes (e.g. warm oranges vs cool blues).
        val references = GameConfig.TILE_COLORS.mapValues { (value, canonical) ->
            learnedColors[value] ?: canonical
        } + learnedColors.filterKeys { it !in GameConfig.TILE_COLORS }
        val distances = references.map { (value, reference) ->
            val distance = sqrt(
                (r - reference[0]).toDouble().pow(2.0) +
                    (g - reference[1]).toDouble().pow(2.0) +
                    (b - reference[2]).toDouble().pow(2.0)
            )
            value to distance
        }.sortedBy { it.second }

        val nearest = distances.firstOrNull() ?: return ColorMatch(-1, Double.MAX_VALUE, false)
        val second = distances.getOrNull(1)
        val ambiguous = second != null &&
            second.second <= GameConfig.MAX_COLOR_DISTANCE + 20.0 &&
            second.second - nearest.second < 18.0
        return ColorMatch(nearest.first, nearest.second, ambiguous)
    }

    private fun medianColor(colors: List<Triple<Int, Int, Int>>): Triple<Int, Int, Int> {
        if (colors.isEmpty()) return Triple(0, 0, 0)
        val reds = colors.map { it.first }.sorted()
        val greens = colors.map { it.second }.sorted()
        val blues = colors.map { it.third }.sorted()
        val middle = colors.size / 2
        return Triple(reds[middle], greens[middle], blues[middle])
    }

    /**
     * Validates whether the sampled colour at a cell is consistent with the
     * value predicted by [expectedBoard]. Used to recover cells where the
     * colour vote was untrusted or contradictory by anchoring the result to
     * a known-good reference when both signals agree.
     *
     * Returns true when the classified colour matches [expectedValue] OR
     * the representative RGB is within a small Euclidean distance of the
     * reference colour for [expectedValue] (handles near-empty cells whose
     * dark colour is close to the background).
     */
    private fun validateAgainstExpected(
        expectedValue: Int,
        representativeRgb: Triple<Int, Int, Int>,
        resolvedMatch: ColorMatch
    ): Boolean {
        if (expectedValue <= 0) return false
        val reference = GameConfig.TILE_COLORS[expectedValue] ?: return false

        // Primary check: the colour classifier already agrees with the expected
        // value and is unambiguous.
        if (!resolvedMatch.ambiguous && resolvedMatch.value == expectedValue) {
            return true
        }

        // Secondary check: the representative RGB sits near the reference
        // colour for [expectedValue] - this catches cells where sampling
        // included anti-aliased digit pixels but the dominant tone matches.
        val referenceDistance = sqrt(
            (representativeRgb.first - reference[0]).toDouble().pow(2.0) +
                (representativeRgb.second - reference[1]).toDouble().pow(2.0) +
                (representativeRgb.third - reference[2]).toDouble().pow(2.0)
        )
        return referenceDistance <= GameConfig.RELIABLE_COLOR_DISTANCE
    }

    @Synchronized
    private fun rememberLearnedColor(value: Int, rgb: Triple<Int, Int, Int>): Boolean {
        if (value <= 0 || rgb == Triple(0, 0, 0)) return false

        // Reject grayscale samples. Real 2048 tile colours are always vibrant
        // (orange/blue/green/etc.), never pure gray. Gray samples mean the
        // MediaProjection is capturing a non-game surface (system UI, a black
        // screen, a closed app) - learning from them permanently pollutes
        // the palette with colours that have no real semantic meaning. R ~= G
        // AND G ~= B within ~5 covers pure gray plus slight JPEG noise.
        val (r, g, b) = rgb
        if (kotlin.math.abs(r - g) <= 5 && kotlin.math.abs(g - b) <= 5) return false

        // Sanity check against the current (post-learning) classifier palette.
        // Without this guard, a single OCR misread would let the system learn
        // the WRONG value for a colour already owned by another tile - e.g.
        // OCR reads "2" on a tile whose canonical/learned colour belongs to
        // tile 4, and we'd permanently learn "2 = <4's colour>", corrupting
        // future classifications. We accept any colour for [value] (the
        // user's theme may differ from canonical), but require that after
        // this sample would be added, the palette classifies the sample
        // back to [value]. Theme adaptation is preserved; pollution is not.
        val paletteWithCandidate = learnedColors.toMutableMap().apply {
            val current = this[value]
            this[value] = if (current == null) {
                intArrayOf(rgb.first, rgb.second, rgb.third)
            } else {
                intArrayOf(
                    ((current[0] * 3 + rgb.first) / 4).coerceIn(0, 255),
                    ((current[1] * 3 + rgb.second) / 4).coerceIn(0, 255),
                    ((current[2] * 3 + rgb.third) / 4).coerceIn(0, 255)
                )
            }
        }
        val nearestPalette = paletteWithCandidate.minByOrNull { (v, ref) ->
            val d = (rgb.first - ref[0]).toDouble().pow(2.0) +
                (rgb.second - ref[1]).toDouble().pow(2.0) +
                (rgb.third - ref[2]).toDouble().pow(2.0)
            // Tiny epsilon tie-break ONLY. The epsilon (1e-4) is far smaller
            // than any real RGB distance (~0.5 minimum for distinct pixels)
            // so it cannot flip a genuine classification - it only resolves
            // equal distances deterministically.
            val epsilon = 0.0001
            if (v == value) d - epsilon else d
        }
        if (nearestPalette == null || nearestPalette.key != value) return false

        val current = learnedColors[value]
        val learned = if (current == null) {
            intArrayOf(rgb.first, rgb.second, rgb.third)
        } else {
            intArrayOf(
                ((current[0] * 3 + rgb.first) / 4).coerceIn(0, 255),
                ((current[1] * 3 + rgb.second) / 4).coerceIn(0, 255),
                ((current[2] * 3 + rgb.third) / 4).coerceIn(0, 255)
            )
        }
        learnedColors = learnedColors.toMutableMap().apply { put(value, learned) }
        return true
    }

    private fun loadLearnedColors(context: android.content.Context): Map<Int, IntArray> {
        val raw = context.getSharedPreferences(LEARNED_COLOR_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(LEARNED_COLOR_KEY, null) ?: return emptyMap()
        return raw.split(";").mapNotNull { token ->
            val parts = token.split(":", ",")
            if (parts.size != 4) return@mapNotNull null
            val value = parts[0].toIntOrNull() ?: return@mapNotNull null
            val red = parts[1].toIntOrNull() ?: return@mapNotNull null
            val green = parts[2].toIntOrNull() ?: return@mapNotNull null
            val blue = parts[3].toIntOrNull() ?: return@mapNotNull null
            if (value < 2 || value and (value - 1) != 0 ||
                red !in 0..255 || green !in 0..255 || blue !in 0..255) {
                return@mapNotNull null
            }
            // Drop any grayscale entry persisted by older builds (before the
            // rememberLearnedColor guard). Gray is never a real tile colour
            // and a stray gray entry at distance 0 hijacks classifyColor,
            // forcing every empty cell through the OCR-uncertain path.
            if (kotlin.math.abs(red - green) <= 5 && kotlin.math.abs(green - blue) <= 5) {
                return@mapNotNull null
            }
            value to intArrayOf(red, green, blue)
        }.toMap()
    }

    private fun persistLearnedColors() {
        val context = applicationContext ?: return
        val raw = learnedColors.entries.joinToString(";") { (value, rgb) ->
            "$value:${rgb[0]},${rgb[1]},${rgb[2]}"
        }
        context.getSharedPreferences(LEARNED_COLOR_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(LEARNED_COLOR_KEY, raw).apply()
    }

    private fun recognizeBoardValues(bitmap: Bitmap, scaleX: Float, scaleY: Float): OcrBoardResult {
        // Pad the crop by a few pixels in every direction. ML Kit's text
        // bounding boxes are sometimes slightly larger than the actual
        // glyph (anti-aliasing padding around characters), so digits at
        // the edge of the board can otherwise get clipped or have their
        // bounding boxes partially outside the crop and be discarded.
        // The grid mapping below still divides by (right - left) which
        // is now slightly larger, but the integer cell index is stable
        // because the centre of any cell stays in the same fractional
        // band after a small symmetric padding. The padding is
        // slightly larger on the top/left to give ML Kit extra context
        // for tiles at the corner of the board (e.g. (0,0) where the
        // digit sits right against the calibration edge).
        val padding = 28
        val left = (GameConfig.detectedXStart * scaleX)
            .toInt().coerceIn(0, bitmap.width - 1) - padding
        val top = (GameConfig.detectedYStart * scaleY)
            .toInt().coerceIn(0, bitmap.height - 1) - padding
        val right = (GameConfig.detectedXEnd * scaleX)
            .toInt().coerceIn(left + 1 + padding, bitmap.width) + padding
        val bottom = (GameConfig.detectedYEnd * scaleY)
            .toInt().coerceIn(top + 1 + padding, bitmap.height) + padding

        if (right - left < 16 || bottom - top < 16) {
            return OcrBoardResult(mutableMapOf(), false)
        }

        val boardBitmap = try {
            Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888).also { crop ->
                android.graphics.Canvas(crop).drawBitmap(bitmap, -left.toFloat(), -top.toFloat(), null)
            }
        } catch (e: Exception) {
            Log.w("Auto2048", "OCR crop failed: ${e.message}")
            return OcrBoardResult(mutableMapOf(), false)
        }
        val task = try {
            textRecognizer.process(InputImage.fromBitmap(boardBitmap, 0))
        } catch (e: Exception) {
            if (!boardBitmap.isRecycled) boardBitmap.recycle()
            Log.w("Auto2048", "OCR start failed: ${e.message}")
            return OcrBoardResult(mutableMapOf(), false)
        }

        return try {
            val recognized = Tasks.await(task, 2500, TimeUnit.MILLISECONDS)
            val cropWidth = right - left
            val cropHeight = bottom - top
            val values = mutableMapOf<Int, Int>()

            for (block in recognized.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val value = parseTileValue(element.text) ?: continue
                        val bounds = element.boundingBox ?: continue
                        // Only drop the element if its centre is outside
                        // the crop - the strict "any pixel outside" check
                        // discarded perfectly valid edge digits.
                        val centerX = bounds.centerX()
                        val centerY = bounds.centerY()
                        if (centerX < 0 || centerY < 0 ||
                            centerX >= cropWidth || centerY >= cropHeight) {
                            continue
                        }
                        val col = (centerX * 4 / cropWidth).coerceIn(0, 3)
                        val row = (centerY * 4 / cropHeight).coerceIn(0, 3)
                        values[row * 4 + col] = value
                    }
                }
            }
            OcrBoardResult(values, true)
        } catch (e: Exception) {
            Log.w("Auto2048", "OCR fallback failed: ${e.message}")
            OcrBoardResult(mutableMapOf(), false)
        } finally {
            if (task.isComplete) {
                if (!boardBitmap.isRecycled) boardBitmap.recycle()
            } else {
                task.addOnCompleteListener {
                    if (!boardBitmap.isRecycled) boardBitmap.recycle()
                }
            }
        }
    }

    private fun parseTileValue(text: String): Int? {
        val digits = text.filter(Char::isDigit)
        val value = digits.toIntOrNull() ?: return null
        if (value < 2 || value and (value - 1) != 0) return null
        return value
    }
}
