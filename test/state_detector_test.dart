import 'package:auto_2048/bot/game_state.dart';
import 'package:auto_2048/bot/heuristics.dart';
import 'package:auto_2048/bot/native_bridge.dart';
import 'package:auto_2048/bot/state_detector.dart';
import 'package:flutter_test/flutter_test.dart';

/// Minimal fake implementation of the typed [INativeBridge] surface.
/// The detector only calls `probeScreen`, `tapAtNormalized`, and the
/// tap delay - everything else is a no-op so tests stay focused.
class _FakeBridge implements INativeBridge {
  _FakeBridge({this.probe});

  final GameStateSnapshot? Function()? probe;
  final List<NormalizedPoint> taps = <NormalizedPoint>[];

  @override
  Future<GameStateSnapshot?> probeScreen() async => probe?.call();

  @override
  Future<List<int>> currentGrid() async => List<int>.filled(16, 0);

  @override
  Future<Map<String, dynamic>> solverStatus() async => const {};

  @override
  Future<SolveStepResult?> solveStep() async => null;

  @override
  Future<void> swipe(String direction) async {}

  @override
  Future<void> tapAtNormalized(NormalizedPoint point) async {
    taps.add(point);
  }

  @override
  Future<bool> isServiceRunning() async => true;

  // Typed variants - default implementations delegate to the legacy
  // null-returning methods so the typed `BridgeCallResult` wiring is
  // exercised but the tests stay simple.
  @override
  Future<BridgeCallResult<GameStateSnapshot?>> probeScreenTyped() async {
    final r = await probeScreen();
    return r == null
        ? const BridgeCallOk(null)
        : BridgeCallOk<GameStateSnapshot?>(r);
  }

  @override
  Future<BridgeCallResult<List<int>>> currentGridTyped() async {
    return BridgeCallOk<List<int>>(await currentGrid());
  }

  @override
  Future<BridgeCallResult<void>> swipeTyped(String direction) async {
    await swipe(direction);
    return const BridgeCallOk<void>(null);
  }

  @override
  Future<BridgeCallResult<void>> tapAtNormalizedTyped(NormalizedPoint point) async {
    await tapAtNormalized(point);
    return const BridgeCallOk<void>(null);
  }
}

void main() {
  group('GridHeuristicsProbe.analyze', () {
    test('flags a 2048 tile as a win', () {
      final grid = <int>[
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 2048,
      ];
      final snapshot = GridHeuristicsProbe.analyze(grid);
      expect(snapshot.state, GameState.won2048);
      expect(snapshot.isReliable, isTrue);
    });

    test('flags a full, no-move board as game over', () {
      final grid = <int>[
        2, 4, 2, 4,
        4, 2, 4, 2,
        2, 4, 2, 4,
        4, 2, 4, 2,
      ];
      expect(Heuristics.hasNoMoves(grid), isTrue);
      final snapshot = GridHeuristicsProbe.analyze(grid);
      expect(snapshot.state, GameState.gameOver);
    });

    test('empty board reads as menu', () {
      final snapshot =
          GridHeuristicsProbe.analyze(List<int>.filled(16, 0));
      expect(snapshot.state, GameState.menu);
    });

    test('mid-game board reads as playing', () {
      final grid = <int>[
        0, 0, 0, 0,
        0, 2, 0, 0,
        0, 0, 4, 0,
        0, 0, 0, 8,
      ];
      final snapshot = GridHeuristicsProbe.analyze(grid);
      expect(snapshot.state, GameState.playing);
    });
  });

  group('StateDetector.detect', () {
    test('returns SolveResult on a fresh board with legal moves', () async {
      final bridge = _FakeBridge(probe: () => null);
      final detector = StateDetector(bridge: bridge);
      final grid = <int>[
        0, 0, 0, 0,
        0, 2, 0, 0,
        0, 0, 4, 0,
        0, 0, 0, 8,
      ];
      final result = await detector.detect(lastGrid: grid);
      expect(result, isA<SolveResult>());
      final solve = result as SolveResult;
      expect(solve.grid, grid);
    });

    test('returns RestartResult when 2048 tile is on the board', () async {
      final bridge = _FakeBridge(probe: () => null);
      final detector = StateDetector(bridge: bridge);
      final grid = <int>[
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 1024, 2048,
      ];
      final result = await detector.detect(lastGrid: grid);
      expect(result, isA<RestartResult>());
      expect((result as RestartResult).reason, RestartReason.won2048);
    });

    test('returns RestartResult(gameOver) when board is terminal', () async {
      final bridge = _FakeBridge(probe: () => null);
      final detector = StateDetector(bridge: bridge);
      final grid = <int>[
        2, 4, 2, 4,
        4, 2, 4, 2,
        2, 4, 2, 4,
        4, 2, 4, 2,
      ];
      final result = await detector.detect(lastGrid: grid);
      expect(result, isA<RestartResult>());
      expect((result as RestartResult).reason, RestartReason.gameOver);
    });

    test('returns WaitResult when grid is empty', () async {
      final bridge = _FakeBridge(probe: () => null);
      final detector = StateDetector(bridge: bridge);
      final result = await detector.detect(
        lastGrid: List<int>.filled(16, 0),
      );
      expect(result, isA<WaitResult>());
    });

    test('returns WaitResult when no grid and no probe', () async {
      final bridge = _FakeBridge(probe: () => null);
      final detector = StateDetector(bridge: bridge);
      final result = await detector.detect();
      expect(result, isA<WaitResult>());
    });

    test('native probe wins when highly confident', () async {
      final bridge = _FakeBridge(
        probe: () => GameStateSnapshot.fromReason(
          GameState.won2048,
          0.99,
          'native vision detected You Win dialog',
        ),
      );
      final detector = StateDetector(bridge: bridge);
      final grid = <int>[
        0, 0, 0, 0,
        0, 2, 0, 0,
        0, 0, 4, 0,
        0, 0, 0, 8,
      ];
      final result = await detector.detect(lastGrid: grid);
      expect(result.snapshot.state, GameState.won2048);
      expect(result, isA<RestartResult>());
    });

    test('low-confidence native probe falls back to dart heuristics',
        () async {
      final bridge = _FakeBridge(
        probe: () => GameStateSnapshot.fromReason(
          GameState.unknown,
          0.1,
          'native side is uncertain',
        ),
      );
      final detector = StateDetector(bridge: bridge);
      final grid = <int>[
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        2, 4, 8, 16,
      ];
      final result = await detector.detect(lastGrid: grid);
      expect(result, isA<SolveResult>());
    });
  });

  group('StateDetector.tapRestart', () {
    test('taps both primary and fallback coordinates', () async {
      final bridge = _FakeBridge();
      final detector = StateDetector(bridge: bridge);
      await detector.tapRestart();
      expect(bridge.taps.length, 2);
    });
  });

  group('SnapshotMerger', () {
    test('returns the highest-confidence snapshot', () {
      const merger = SnapshotMerger();
      final lo = GameStateSnapshot.fromReason(
        GameState.unknown,
        0.1,
        'low',
      );
      final hi = GameStateSnapshot.fromReason(
        GameState.playing,
        0.9,
        'high',
      );
      final merged = merger.merge(<GameStateSnapshot?>[lo, hi], const [
        GridHeuristicsProbe(),
        GridHeuristicsProbe(),
      ]);
      expect(merged, hi);
    });

    test('returns null when every probe returned null', () {
      const merger = SnapshotMerger();
      final merged = merger.merge(
        const <GameStateSnapshot?>[null, null],
        const [GridHeuristicsProbe(), GridHeuristicsProbe()],
      );
      expect(merged, isNull);
    });

    test('breaks confidence ties by probe order', () {
      const merger = SnapshotMerger();
      final first = GameStateSnapshot.fromReason(
        GameState.playing,
        0.5,
        'first',
      );
      final second = GameStateSnapshot.fromReason(
        GameState.playing,
        0.5,
        'second',
      );
      final merged = merger.merge(<GameStateSnapshot?>[first, second], const [
        GridHeuristicsProbe(),
        GridHeuristicsProbe(),
      ]);
      expect(merged, first);
    });
  });
}
