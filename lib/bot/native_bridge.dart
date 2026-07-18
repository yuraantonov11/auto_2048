import 'dart:async';

import 'package:flutter/services.dart';

import 'exceptions.dart';
import 'game_state.dart';

// ─────────────────────────────── Result type ────────────────────────────

/// Outcome of a single native bridge call.
///
/// Sealed so callers can pattern-match exhaustively on
/// [BridgeCallOk] / [BridgeCallErr]. Use this when the caller needs to
/// distinguish "native side returned null" from "bridge was not
/// reachable". The legacy methods on [INativeBridge] keep their
/// "null on failure" semantics so we do not break the existing
/// controller code.
sealed class BridgeCallResult<T> {
  const BridgeCallResult();
}

/// Native side completed successfully with [value].
///
/// `value` may be `null` when the underlying call intentionally
/// returns no data (e.g. `probeScreen()` on a build that does not yet
/// expose the pre-check).
final class BridgeCallOk<T> extends BridgeCallResult<T> {
  const BridgeCallOk(this.value);
  final T value;
}

/// Native side failed with [error] (platform exception, missing
/// plugin, timeout, ...).
final class BridgeCallErr<T> extends BridgeCallResult<T> {
  const BridgeCallErr(this.error);
  final BotException error;
}

/// Convenience guard for the legacy null-on-failure surface.
T? orNull<T>(BridgeCallResult<T> r) => switch (r) {
      BridgeCallOk<T>(:final value) => value,
      BridgeCallErr<T>() => null,
    };

/// Outcome of a single [INativeBridge.solveStep] call.
///
/// Native side may respond with a raw direction string (the happy
/// path) OR with a structured [SolveStepTerminalResult] when the
/// solver detected a Won / Game Over overlay, restarted the game, or
/// the board was already terminal before a move ran.
///
/// Instances are constructed through [SolveStepResult.fromNative] -
/// direct construction is reserved for unit tests.
sealed class SolveStepResult {
  const SolveStepResult();

  /// Decode whatever the Kotlin side returned (String direction, Map
  /// status object, or `null`) into the right [SolveStepResult]
  /// subtype. Returns `null` for unknown payloads so the controller
  /// can retry the loop instead of crashing.
  static SolveStepResult? fromNative(dynamic raw) {
    if (raw == null) return null;
    if (raw is String) {
      final upper = raw.toUpperCase();
      const valid = {'UP', 'DOWN', 'LEFT', 'RIGHT'};
      if (valid.contains(upper)) return SolveStepMove(upper);
      return null;
    }
    if (raw is Map) {
      final status = raw['status'] as String? ?? '';
      switch (status) {
        case 'restarted':
          final state = raw['state'] as String?;
          return SolveStepTerminalResult.restarted(state ?? 'unknown');
        case 'terminal':
          final gridRaw = raw['grid'];
          final grid = gridRaw is List
              ? gridRaw.whereType<num>().map((e) => e.toInt()).toList()
              : const <int>[];
          return SolveStepTerminalResult.alreadyTerminal(grid);
      }
      return null;
    }
    return null;
  }
}

/// Native solver picked [direction] as the next move.
final class SolveStepMove extends SolveStepResult {
  const SolveStepMove(this.direction);
  final String direction;
}

/// Native solver detected a terminal state and (where possible)
/// restarted the game automatically. Exposes which terminal state it
/// observed so Dart can update the UI badge.
final class SolveStepTerminalResult extends SolveStepResult {
  const SolveStepTerminalResult.restarted(this.state)
      : alreadyTerminalGrid = const <int>[];
  const SolveStepTerminalResult.alreadyTerminal(this.alreadyTerminalGrid)
      : state = 'terminal';

  /// `won`, `gameover`, `terminal`, or `unknown`. Lowercased so it can
  /// be pattern-matched directly against enum-like constants.
  final String state;

  /// Grid the solver observed when it concluded the game is already
  /// terminal (no possible moves). Empty when the result is a
  /// successful restart.
  final List<int> alreadyTerminalGrid;
}

/// Abstract surface for everything the controller needs from Android.
///
/// The default implementation [MethodChannelBridge] talks to the existing
/// `com.antonov.auto2048/gestures` MethodChannel. Tests can substitute a
/// fake implementation without spinning up the platform channel.
abstract class INativeBridge {
  /// Lightweight pre-check that runs before the full grid OCR pipeline.
  /// Implementations should return a snapshot describing what is on
  /// screen. The detector layer combines this with grid heuristics.
  ///
  /// Returns `null` when the native side does not yet expose a
  /// pre-check (current Android implementation); the detector then
  /// falls back to pure Dart heuristics over the last observed grid.
  Future<GameStateSnapshot?> probeScreen();

  /// Latest observed grid (16 tile values, 0 = empty). Empty list when
  /// no grid has been observed yet or the native side could not parse
  /// one.
  Future<List<int>> currentGrid();

  /// Snapshot of the last solver status map returned by `getSolverStatus`.
  /// Returns an empty map when nothing has been received.
  Future<Map<String, dynamic>> solverStatus();

  /// Trigger a single solver step (capture + decide move). Returns the
  /// chosen direction (`UP`/`DOWN`/`LEFT`/`RIGHT`) or `null` if the
  /// solver could not produce a move.
  ///
  /// If the native side short-circuits (terminal state detected, the
  /// board was already won/lost, etc.) it returns a [SolveStepResult]
  /// with `status != "move"` so the controller can react to the
  /// outcome instead of replaying a gesture.
  Future<SolveStepResult?> solveStep();

  /// Run a directional swipe through the accessibility service.
  Future<void> swipe(String direction);

  /// Tap an absolute pixel coordinate. Implementations may forward to a
  /// native `tapAt` method if available; otherwise the call no-ops.
  Future<void> tapAtNormalized(NormalizedPoint point);

  /// Whether the accessibility Auto2048 service is currently running.
  Future<bool> isServiceRunning();

  // ── Typed (non-throwing) variants ──────────────────────────────────────
  //
  // These mirror the null-on-failure methods above but expose a sealed
  // [BridgeCallResult] so callers can distinguish "bridge unreachable"
  // from "native returned null". They are optional - new code should
  // prefer them; legacy code can keep using the null-returning methods.

  /// Typed variant of [probeScreen]. Never throws.
  Future<BridgeCallResult<GameStateSnapshot?>> probeScreenTyped();

  /// Typed variant of [currentGrid]. Never throws.
  Future<BridgeCallResult<List<int>>> currentGridTyped();

  /// Typed variant of [swipe]. Never throws.
  Future<BridgeCallResult<void>> swipeTyped(String direction);

  /// Typed variant of [tapAtNormalized]. Never throws.
  Future<BridgeCallResult<void>> tapAtNormalizedTyped(NormalizedPoint point);
}

/// Default [INativeBridge] backed by `MethodChannel('com.antonov.auto2048/gestures')`.
///
/// All channel calls are dispatched through `async`/`await` and never
/// block the UI thread - the platform channel itself uses a background
/// thread on the Flutter side, and any native I/O happens off the main
/// thread on the Kotlin side.
class MethodChannelBridge implements INativeBridge {
  MethodChannelBridge({MethodChannel? channel})
    : _channel =
          channel ?? const MethodChannel('com.antonov.auto2048/gestures');

  final MethodChannel _channel;

  // Methods supported by the current native side. Keeping them as named
  // constants makes it easy to grep / refactor and prevents typos.
  static const String _kProbeScreen = 'probeScreen';
  static const String _kCurrentGrid = 'getGridState';
  static const String _kSolverStatus = 'getSolverStatus';
  static const String _kSolveStep = 'solveStep';
  static const String _kTapAt = 'tapAt';
  static const String _kIsServiceRunning = 'isServiceRunning';

  @override
  Future<GameStateSnapshot?> probeScreen() async {
    try {
      final raw = await _channel.invokeMethod<dynamic>(_kProbeScreen);
      if (raw is! Map) return null;
      final stateName = raw['state'] as String?;
      if (stateName == null) return null;
      final state = GameState.values.firstWhere(
        (s) => s.name == stateName,
        orElse: () => GameState.unknown,
      );
      final confidence = (raw['confidence'] as num?)?.toDouble() ?? 0.5;
      final reason = raw['reason'] as String? ?? '';
      return GameStateSnapshot.fromReason(state, confidence, reason);
    } on MissingPluginException {
      // Native side does not yet expose the probe - signal callers to
      // use the Dart fallback.
      return null;
    } on PlatformException {
      return null;
    }
  }

  @override
  Future<List<int>> currentGrid() async {
    try {
      final raw = await _channel.invokeMethod<String>(_kCurrentGrid);
      if (raw is! String) return const <int>[];
      return _parseGridState(raw);
    } on PlatformException {
      return const <int>[];
    } on MissingPluginException {
      return const <int>[];
    }
  }

  @override
  Future<Map<String, dynamic>> solverStatus() async {
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        _kSolverStatus,
      );
      if (raw == null) return const <String, dynamic>{};
      return raw.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException {
      return const <String, dynamic>{};
    } on MissingPluginException {
      return const <String, dynamic>{};
    }
  }

  @override
  Future<SolveStepResult?> solveStep() async {
    try {
      final raw = await _channel.invokeMethod<dynamic>(_kSolveStep);
      return SolveStepResult.fromNative(raw);
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  @override
  Future<void> swipe(String direction) async {
    final upper = direction.toUpperCase();
    final method = 'swipe$upper';
    try {
      await _channel.invokeMethod(method);
    } on PlatformException {
      // Swipes can race with the accessibility service lifecycle -
      // silently ignore transient failures and let the controller retry.
    } on MissingPluginException {
      // Method not implemented on this build.
    }
  }

  @override
  Future<void> tapAtNormalized(NormalizedPoint point) async {
    try {
      await _channel.invokeMethod(_kTapAt, point.toMap());
    } on MissingPluginException {
      // Tap is optional - the restart tap is best-effort.
    } on PlatformException {
      // Same rationale as swipe().
    }
  }

  @override
  Future<bool> isServiceRunning() async {
    try {
      final running = await _channel.invokeMethod<bool>(_kIsServiceRunning);
      return running ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  // Parse the "GRID_STATE:a,b,c,..." format the native side returns.
  static List<int> _parseGridState(String raw) {
    final stripped = raw.startsWith('GRID_STATE:')
        ? raw.substring('GRID_STATE:'.length)
        : raw;
    return stripped
        .split(',')
        .map((s) => int.tryParse(s.trim()) ?? 0)
        .where((v) => v >= 0)
        .toList(growable: false);
  }

  // ── Typed variants ────────────────────────────────────────────────────

  @override
  Future<BridgeCallResult<GameStateSnapshot?>> probeScreenTyped() async {
  try {
    final raw = await _channel.invokeMethod<dynamic>(_kProbeScreen);
    if (raw is! Map) return const BridgeCallOk(null);
    final stateName = raw['state'] as String?;
    if (stateName == null) return const BridgeCallOk(null);
    final state = GameState.values.firstWhere(
      (s) => s.name == stateName,
      orElse: () => GameState.unknown,
    );
    final confidence = (raw['confidence'] as num?)?.toDouble() ?? 0.5;
    final reason = raw['reason'] as String? ?? '';
    return BridgeCallOk(
      GameStateSnapshot.fromReason(state, confidence, reason),
    );
  } on PlatformException catch (e) {
    return BridgeCallErr(ProbeUnavailableException(e.message ?? 'probeScreen failed'));
  } on MissingPluginException {
    return const BridgeCallErr(ProbeUnavailableException('probeScreen missing'));
  }
  }

  @override
  Future<BridgeCallResult<List<int>>> currentGridTyped() async {
  try {
    final raw = await _channel.invokeMethod<String>(_kCurrentGrid);
    if (raw is! String) return const BridgeCallOk(<int>[]);
    return BridgeCallOk(_parseGridState(raw));
  } on PlatformException catch (e) {
    return BridgeCallErr(ProbeUnavailableException(e.message ?? 'currentGrid failed'));
  } on MissingPluginException {
    return const BridgeCallErr(ProbeUnavailableException('currentGrid missing'));
  }
  }

  @override
  Future<BridgeCallResult<void>> swipeTyped(String direction) async {
  final upper = direction.toUpperCase();
  final method = 'swipe$upper';
  try {
    await _channel.invokeMethod(method);
    return const BridgeCallOk(null);
  } on PlatformException catch (e) {
    return BridgeCallErr(ProbeUnavailableException(e.message ?? 'swipe failed'));
  } on MissingPluginException {
    return const BridgeCallErr(ProbeUnavailableException('swipe missing'));
  }
  }

  @override
  Future<BridgeCallResult<void>> tapAtNormalizedTyped(NormalizedPoint point) async {
  try {
    await _channel.invokeMethod(_kTapAt, point.toMap());
    return const BridgeCallOk(null);
  } on PlatformException catch (e) {
    return BridgeCallErr(ProbeUnavailableException(e.message ?? 'tapAt failed'));
  } on MissingPluginException {
    return const BridgeCallErr(ProbeUnavailableException('tapAt missing'));
  }
  }
}
