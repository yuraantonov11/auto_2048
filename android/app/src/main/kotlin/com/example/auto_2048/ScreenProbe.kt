package com.example.auto_2048

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
/**
 * Result of a single [ScreenProbe.probe] call. The two bits the solver
 * cares about - whether the screen is in a terminal state and, if so,
 * which one - are surfaced explicitly so callers can `when` over them
 * without inspecting a string.
 *
 * Construct via [ScreenProbeOutcome.fromModalState] (preferred) or via
 * the helper factories - direct construction is reserved for tests.
 */
sealed class ScreenProbeOutcome {
    /** The 4x4 board is in active play. OCR + solver should run normally. */
    data object Playing : ScreenProbeOutcome()

    /**
     * "You Win" overlay is on screen. The solver should stop, log the
     * win, tap "Try again" to begin a new run and reset heuristics.
     */
    data object Won : ScreenProbeOutcome()

    /**
     * "Game Over" overlay is on screen. The solver should stop, log
     * the loss, tap "Try again" to begin a new run and reset heuristics.
     */
    data object GameOver : ScreenProbeOutcome()

    /**
     * The probe could not classify the frame - usually an animation in
     * progress, a permission popup or a transient overlay. Callers
     * should retry shortly instead of trusting this outcome.
     */
    data object Unknown : ScreenProbeOutcome()

    companion object {
        /**
         * Map a raw [VisionProcessor.ModalState] enum value into the
         * richer [ScreenProbeOutcome] sealed hierarchy. Used as the
         * single point of translation between vision and solver so
         * both layers stay decoupled.
         */
        fun fromModalState(state: VisionProcessor.ModalState): ScreenProbeOutcome = when (state) {
            VisionProcessor.ModalState.Playing -> Playing
            VisionProcessor.ModalState.Won -> Won
            VisionProcessor.ModalState.GameOver -> GameOver
            VisionProcessor.ModalState.Unknown -> Unknown
        }
    }
}

/**
 * Cheap terminal-state pre-check that runs BEFORE the heavy solver.
 *
 * The probe does NOT spin up its own [android.media.projection]
 * capture session. Doing so while the long-lived `BotCap` virtual
 * display is alive triggers `MediaProjection#createVirtualDisplay`
 * to throw "Don't re-use the resultData to retrieve the same
 * projection instance, and don't take multiple captures by invoking
 * MediaProjection#createVirtualDisplay multiple times on the same
 * instance.", which Android interprets as the user revoking screen
 * capture - it calls `MediaProjection.Callback#onStop()`, the bot's
 * `BotCap` display is torn down, the projection data is wiped, and
 * the next capture attempt asks the user for permission again. The
 * cycle repeats forever; OCR never reads the actual board.
 *
 * Instead, every successful [VisionProcessor.analyze] call refreshes
 * a tiny cache (`VisionProcessor.lastModalState` plus
 * `VisionProcessor.lastCaptureGeneration`) using the SAME bitmap that
 * already fed the solver. The probe simply reads that cache - no
 * VirtualDisplay, no ImageReader, no second capture, no risk of
 * killing the projection.
 *
 * Lifecycle:
 *  - Created once per MainActivity and reused across probes.
 *  - [shutdown] is called from `onDestroy` to release the worker
 *    thread. The probe thread is only used so callers stay
 *    non-blocking even on the cache-miss path.
 */
class ScreenProbe {

    private val probeThread = HandlerThread("auto2048-probe").apply { start() }
    private val probeHandler = Handler(probeThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Read the most recent modal-state classification without spinning
     * up a second capture session.
     *
     * - [projection] is accepted for backwards compatibility with the
     *   old call sites but is ignored - the cache is the source of
     *   truth. Passing `null` does NOT mean "no projection" any more;
     *   it only means "no argument supplied".
     * - The callback always fires on the main thread.
     *
     * Outcomes:
     * - Playing / Won / GameOver when the latest frame the solver
     *   already consumed classifies cleanly.
     * - Unknown with diagnostics `"No frame analysed yet"` if no
     *   capture has succeeded since the last `clearExpectedBoard()` /
     *   solver restart. The caller should retry shortly.
     */
    @Suppress("UNUSED_PARAMETER")
    fun probe(projection: MediaProjection?, onResult: (ScreenProbeOutcome, String) -> Unit) {
        probeHandler.post {
            val gen = VisionProcessor.lastCaptureGeneration
            val state = VisionProcessor.lastModalState
            val diagnostics = VisionProcessor.lastModalDiagnostics.ifBlank {
                "No frame analysed yet"
            }

            // When no analysis has run yet (gen=0), assume Playing state
            // rather than Unknown, so the solver proceeds instead of waiting.
            // The full analysis will happen when getGridState is called.
            val effectiveState = if (gen == 0L) {
                Logger.d("ScreenProbe", "ScreenProbe: no analysis yet (gen=0), assuming Playing")
                VisionProcessor.ModalState.Playing
            } else {
                state
            }

            val outcome = ScreenProbeOutcome.fromModalState(effectiveState)
                        Logger.d(
                            "ScreenProbe",
                "ScreenProbe ${if (gen == 0L) "MISS" else "cache-hit"} ${effectiveState.name} gen=$gen: $diagnostics"
            )
            mainHandler.post { onResult(outcome, diagnostics) }
        }
    }

    fun shutdown() {
        probeThread.quitSafely()
    }
}