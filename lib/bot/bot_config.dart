/// Central tunables for the 2048 bot.
///
/// Every magic number that used to be sprinkled across
/// `state_detector.dart`, `expectimax_solver.dart`, and `main.dart` is
/// gathered here so they can be tuned, tested and re-used without
/// grepping for literals.
///
/// Two layers of configuration are exposed:
///
///   1. **Top-level `const` defaults** — `_kSolverDepth`,
///      `_kSolverTimeBudget`, etc. These are usable as `const`
///      default-parameter values across the bot (you cannot reference
///      `BotConfig.defaults.solver.depth` from another const default).
///   2. **`BotConfig`** — an aggregate value-object that bundles every
///      group together for ergonomic `copyWith`-style overrides from
///      `main.dart` or integration tests.
///
/// To override a single knob without mutating the global, copy
/// [BotConfig.defaults] via [BotConfig.copyWith] and pass the resulting
/// instance down through the call stack.
library;

import 'game_state.dart';

// ─── Solver defaults ───────────────────────────────────────────────────

/// Default Expectimax search depth. Tuned for the Dart fallback solver;
/// the native Kotlin solver runs deeper.
const int kSolverDepth = 5;

/// Hard wall-clock budget for one solver call.
const Duration kSolverTimeBudget = Duration(milliseconds: 1500);

// ─── Detector defaults ─────────────────────────────────────────────────

/// Minimum confidence required to drive a non-`wait` action.
const double kMinConfidence = 0.6;

/// Default delay before re-probing when the detector returns
/// `WaitResult`.
const Duration kDetectorWaitDelay = Duration(milliseconds: 400);

// ─── Restart-tap defaults ──────────────────────────────────────────────

/// Gap between the primary and fallback restart taps.
const Duration kRestartTapDelay = Duration(milliseconds: 350);

/// First-tap target (Try Again / Keep Going dialogs).
const NormalizedPoint kRestartPrimary = NormalizedPoint(0.5, 0.78);

/// Second-chance target if the first tap misses (Android skins sometimes
/// render the button slightly higher).
const NormalizedPoint kRestartFallback = NormalizedPoint(0.5, 0.62);

// ─── Aggregate ─────────────────────────────────────────────────────────

/// Aggregate of every bot-level knob.
final class BotConfig {
  const BotConfig({
    this.solver = const SolverConfig(),
    this.detector = const DetectorSettings(),
    this.restart = const RestartSettings(),
  });

  /// Sensible defaults used when no overrides are supplied.
  static const BotConfig defaults = BotConfig();

  /// Solver-time limits (depth, time budget).
  final SolverConfig solver;

  /// Detector gating the OCR + solver pipeline.
  final DetectorSettings detector;

  /// Restart-tap geometry and pacing.
  final RestartSettings restart;

  /// Returns a copy with the supplied fields replaced.
  BotConfig copyWith({
    SolverConfig? solver,
    DetectorSettings? detector,
    RestartSettings? restart,
  }) {
    return BotConfig(
      solver: solver ?? this.solver,
      detector: detector ?? this.detector,
      restart: restart ?? this.restart,
    );
  }
}

/// Knobs that bound the Expectimax search itself.
final class SolverConfig {
  const SolverConfig({
    this.depth = kSolverDepth,
    this.timeBudget = kSolverTimeBudget,
  }) : assert(depth > 0, 'depth must be > 0');

  /// Plies to search before falling back to the heuristic.
  final int depth;

  /// Hard wall-clock budget for one solver call.
  final Duration timeBudget;
}

/// Knobs that govern the screen pre-check layer.
final class DetectorSettings {
  const DetectorSettings({
    this.minConfidence = kMinConfidence,
    this.waitDelay = kDetectorWaitDelay,
  }) : assert(minConfidence >= 0 && minConfidence <= 1);

  /// Minimum confidence required to drive a non-`wait` action.
  final double minConfidence;

  /// Default delay before re-probing when the detector returns
  /// `WaitResult`.
  final Duration waitDelay;
}

/// Knobs that govern the post-game "Restart" tap.
final class RestartSettings {
  const RestartSettings({
    this.tapDelay = kRestartTapDelay,
    this.primary = kRestartPrimary,
    this.fallback = kRestartFallback,
  });

  /// Gap between the primary and fallback taps.
  final Duration tapDelay;

  /// First-tap target (Try Again / Keep Going dialogs).
  final NormalizedPoint primary;

  /// Second-chance target if the first tap misses.
  final NormalizedPoint fallback;
}