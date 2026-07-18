/// Screen pre-check layer for the 2048 bot.
///
/// Full OCR + Expectimax assume the live 4×4 board is in view. When the
/// surface shows a modal (Win, Game Over, Restart prompt, menu,
/// interstitial) OCR returns garbage that crashes the solver.
/// [StateDetector] gates the expensive pipeline with a cheap pre-check
/// and returns a sealed [DetectorResult] the controller can pattern-match
/// on.
///
/// Composition: any number of [ScreenStateProbe]s feed a
/// [SnapshotMerger]; the winning snapshot is mapped to an action via
/// [StateActionMapper].
///
/// ```dart
/// final detector = StateDetector(bridge: MethodChannelBridge());
/// switch (await detector.detect()) {
///   case SolveResult(:final grid):
///     final move = solver.decide(grid);
///   case RestartResult():
///     await detector.tapRestart();
///   case WaitResult(:final retryAfter):
///     await Future<void>.delayed(retryAfter);
/// }
/// ```
library;

import 'dart:async';

import 'bot_config.dart';
import 'exceptions.dart';
import 'game_state.dart';
import 'heuristics.dart';
import 'native_bridge.dart';

// ─────────────────────────────── Probes ───────────────────────────────

/// Strategy that inspects the current screen and returns a snapshot
/// of what it sees. Pure: it may perform I/O but never mutates game
/// state.
abstract interface class ScreenStateProbe {
  /// Best guess about the current screen. Returns `null` when the
  /// probe cannot produce a snapshot (e.g. native bridge unavailable,
  /// timed out).
  Future<GameStateSnapshot?> probe();
}

/// Defers to the native bridge's `probeScreen()` method. Wrapped in a
/// short timeout so a slow native call never stalls the bot.
final class NativeScreenProbe implements ScreenStateProbe {
  const NativeScreenProbe(this._bridge, {this.timeout = const Duration(milliseconds: 250)});

  final INativeBridge _bridge;
  final Duration timeout;

  @override
  Future<GameStateSnapshot?> probe() async {
    try {
      return await _bridge.probeScreen().timeout(timeout);
    } on TimeoutException {
      return null;
    } on BotException {
      return null;
    } catch (_) {
      return null;
    }
  }
}

/// Pure-Dart probe that inspects the last observed grid when the
/// native bridge is unavailable or returns nothing.
///
/// The grid is injected through [seed] right before each detection
/// cycle, replacing the old `_GridHeuristicsContext.lastGrid` static
/// field. Keeping the grid per-call (instead of per-instance) means a
/// fresh detector never accidentally reads a stale grid from a
/// previous game.
final class GridHeuristicsProbe implements ScreenStateProbe {
  // Not const: the probe carries a mutable grid set via [seed].
  GridHeuristicsProbe();

  /// Most recent 16-cell grid handed to the probe via [seed]. Cleared
  /// at the start of every cycle so a probe that ran out of order
  /// never reports a misleading snapshot.
  // ignore: prefer_final_fields
  List<int>? _lastGrid;

  /// Updates the grid the probe will inspect on its next [probe] call.
  void seed(List<int>? grid) => _lastGrid = grid;

  @override
  Future<GameStateSnapshot?> probe() async {
    final grid = _lastGrid;
    return grid == null ? null : analyze(grid);
  }

  /// Pure analysis exposed for unit tests and reuse. Throws
  /// [InvalidBoardException] when the grid is malformed.
  static GameStateSnapshot analyze(List<int> grid) {
    final maxTile = Heuristics.maxTile(grid);
    final maxIdx = Heuristics.maxTileIndex(grid);
    final empty = Heuristics.emptyCells(grid);
    const anchor = kDefaultAnchorCorner;
    final reasons = <String>[];

    if (maxTile >= 2048) {
      reasons.add('max tile $maxTile at index $maxIdx >= 2048');
      return GameStateSnapshot.fromReason(
        GameState.won2048,
        0.95,
        reasons.join('; '),
        grid: grid,
      );
    }

    if (empty == kBoardCells) {
      return GameStateSnapshot.fromReason(
        GameState.menu,
        0.7,
        'empty board - likely menu or splash',
        grid: grid,
      );
    }

    if (Heuristics.hasNoMoves(grid)) {
      reasons.add('no legal moves; empty=$empty');
      return GameStateSnapshot.fromReason(
        GameState.gameOver,
        0.9,
        reasons.join('; '),
        grid: grid,
      );
    }

    reasons.add('max=$maxTile empty=$empty anchor=${maxIdx == anchor}');
    return GameStateSnapshot.fromReason(
      GameState.playing,
      0.85,
      reasons.join('; '),
      grid: grid,
    );
  }
}

// ──────────────────────────── Result types ─────────────────────────────

/// Action the controller should take after a [StateDetector.detect].
sealed class DetectorResult {
  const DetectorResult({required this.snapshot});

  /// Observation that produced this result. Useful for logging / UI.
  final GameStateSnapshot snapshot;
}

/// The surface is the live board. Proceed with OCR + solver using
/// [grid] as the cached observation.
final class SolveResult extends DetectorResult {
  const SolveResult({required super.snapshot, required this.grid});

  /// Cached grid that may be reused by the solver without re-OCR.
  final List<int> grid;
}

/// The surface is a terminal state (Win or Game Over). Stop the
/// solver, log the run, and tap Restart.
final class RestartResult extends DetectorResult {
  const RestartResult({required super.snapshot, required this.reason});

  /// Why the detector decided to restart.
  final RestartReason reason;
}

/// The surface is not actionable yet (unknown, menu, animation
/// playing). Sleep for [retryAfter] and retry the pre-check.
final class WaitResult extends DetectorResult {
  const WaitResult({required super.snapshot, required this.retryAfter});

  /// Suggested delay before the next [StateDetector.detect] call.
  final Duration retryAfter;
}

/// Why the detector triggered a restart.
enum RestartReason { won2048, gameOver }

// ─────────────────────────────── Merger ────────────────────────────────

/// Combines multiple [GameStateSnapshot]s into a single best guess.
///
/// Default policy: highest confidence wins; ties go to the earlier
/// probe so probe-list priority is deterministic.
final class SnapshotMerger {
  const SnapshotMerger();

  /// Returns the best snapshot in [snapshots] or `null` if every probe
  /// returned `null`. `probeOrder` is the original probe list so ties
  /// can be broken deterministically.
  GameStateSnapshot? merge(
    List<GameStateSnapshot?> snapshots,
    List<ScreenStateProbe> probeOrder,
  ) {
    GameStateSnapshot? best;
    var bestProbeIndex = -1;
    for (var i = 0; i < snapshots.length; i++) {
      final s = snapshots[i];
      if (s == null) continue;
      if (best == null ||
          s.confidence > best.confidence ||
          (s.confidence == best.confidence && i < bestProbeIndex)) {
        best = s;
        bestProbeIndex = i;
      }
    }
    return best;
  }
}

// ─────────────────────────── Restart tapper ────────────────────────────

/// Coordinates of the in-game "Restart" button in normalized space.
final class RestartCoordinates {
  const RestartCoordinates({
    this.primary = kRestartPrimary,
    this.fallback = kRestartFallback,
  });

  /// First-tap target (Try Again / Keep Going dialogs).
  final NormalizedPoint primary;

  /// Second-chance target if the first tap misses (Android skins
  /// sometimes render the button slightly higher).
  final NormalizedPoint fallback;
}

/// Taps the in-game Restart button with primary + fallback coordinates.
///
/// Best-effort: failures are swallowed because the next detection
/// cycle will re-attempt.
final class RestartTapper {
  RestartTapper(this._bridge, {this.config = const RestartTapperConfig()});

  final INativeBridge _bridge;
  final RestartTapperConfig config;

  Future<void> tap() async {
    await _bridge.tapAtNormalized(config.coordinates.primary);
    await Future<void>.delayed(config.tapDelay);
    await _bridge.tapAtNormalized(config.coordinates.fallback);
  }
}

/// Tunables for [RestartTapper].
final class RestartTapperConfig {
  const RestartTapperConfig({
    this.coordinates = const RestartCoordinates(),
    this.tapDelay = kRestartTapDelay,
  });

  final RestartCoordinates coordinates;
  final Duration tapDelay;
}

// ─────────────────────────── Action mapper ─────────────────────────────

/// Maps a [GameStateSnapshot] to a [DetectorResult] according to
/// `minConfidence` and `waitDelay`.
final class StateActionMapper {
  const StateActionMapper({required this.minConfidence, required this.waitDelay});

  final double minConfidence;
  final Duration waitDelay;

  DetectorResult map(GameStateSnapshot snapshot, List<int> grid) {
    if (!snapshot.isReliable || snapshot.confidence < minConfidence) {
      return WaitResult(snapshot: snapshot, retryAfter: waitDelay);
    }
    return switch (snapshot.state) {
      GameState.playing => SolveResult(snapshot: snapshot, grid: grid),
      GameState.won2048 => RestartResult(
          snapshot: snapshot,
          reason: RestartReason.won2048,
        ),
      GameState.gameOver => RestartResult(
          snapshot: snapshot,
          reason: RestartReason.gameOver,
        ),
      GameState.menu => WaitResult(snapshot: snapshot, retryAfter: waitDelay),
      GameState.unknown => WaitResult(snapshot: snapshot, retryAfter: waitDelay),
    };
  }
}

// ──────────────────────────── Detector config ──────────────────────────

/// Tunables for [StateDetector].
final class DetectorConfig {
  const DetectorConfig({
    this.minConfidence = kMinConfidence,
    this.waitDelay = kDetectorWaitDelay,
    this.merger = const SnapshotMerger(),
    this.restart = const RestartTapperConfig(),
  });

  /// Minimum confidence required to drive a non-`wait` action.
  final double minConfidence;

  /// Default delay before re-probing when the detector returns
  /// [WaitResult].
  final Duration waitDelay;

  /// Merger used to combine probe outputs.
  final SnapshotMerger merger;

  /// Restart tapper config.
  final RestartTapperConfig restart;
}

// ─────────────────────────────── Detector ──────────────────────────────

/// Screen pre-check. Composes any number of probes (Strategy
/// pattern), merges results, and maps the winning snapshot to a sealed
/// [DetectorResult] the controller can pattern-match on.
final class StateDetector {
  StateDetector({
    required INativeBridge bridge,
    List<ScreenStateProbe>? probes,
    DetectorConfig config = const DetectorConfig(),
  })  // ignore: prefer_initializing_formals
      : _probes = probes ??
            <ScreenStateProbe>[
              NativeScreenProbe(bridge),
                            GridHeuristicsProbe(),
            ],
        _config = config,
        _mapper = StateActionMapper(
          minConfidence: config.minConfidence,
          waitDelay: config.waitDelay,
        ),
        _restartTapper = RestartTapper(bridge, config: config.restart);

  final List<ScreenStateProbe> _probes;
  final DetectorConfig _config;
  final StateActionMapper _mapper;
  final RestartTapper _restartTapper;

  /// Runs the pre-check. `lastGrid` is the most recently observed
  /// 16-cell grid; it powers the pure-Dart fallback probe.
  Future<DetectorResult> detect({List<int>? lastGrid}) async {
      for (final p in _probes) {
        if (p is GridHeuristicsProbe) p.seed(lastGrid);
      }
      final snapshots = <GameStateSnapshot?>[
        for (final p in _probes) await p.probe(),
      ];
      final merged = _config.merger.merge(snapshots, _probes);

    if (merged == null) {
      return WaitResult(
        snapshot: GameStateSnapshot.fromReason(
          GameState.unknown,
          0.0,
          'no probe produced a snapshot',
        ),
        retryAfter: _config.waitDelay,
      );
    }

    return _mapper.map(merged, lastGrid ?? const <int>[]);
  }

  /// Tap the configured Restart coordinates. Delegates to
  /// [RestartTapper] so tests can substitute the tapper independently.
  Future<void> tapRestart() => _restartTapper.tap();

  /// The configured list of probes (read-only). Useful for diagnostics.
  List<ScreenStateProbe> get probes => List.unmodifiable(_probes);
}
