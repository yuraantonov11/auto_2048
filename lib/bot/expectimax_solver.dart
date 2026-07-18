/// Pure-Dart Expectimax solver for the 2048 game.
///
/// The bot uses the native Kotlin solver in production. This
/// implementation is the fallback for tests, web/desktop targets, and
/// for isolating heuristics regressions from the bridge layer.
///
/// Execute on a background isolate via `compute(runSolver, request)`.
library;

import 'dart:math' as math;

import 'exceptions.dart';
import 'heuristics.dart';

/// Tunables for [ExpectimaxSolver].
///
/// Mirrors the native solver's configuration knobs but keeps the
/// surface small and explicit.
final class ExpectimaxConfig {
  const ExpectimaxConfig({
    this.depth = 5,
    this.timeBudget = const Duration(milliseconds: 1500),
    this.heuristics = const HeuristicsConfig(),
  }) : assert(depth > 0, 'depth must be > 0');

  /// Plies to search before falling back to the heuristic.
  final int depth;

  /// Hard wall-clock budget for one solver call.
  final Duration timeBudget;

  /// Heuristic config used by the leaf evaluator.
  final HeuristicsConfig heuristics;
}

/// One solver invocation.
final class SolveRequest {
  const SolveRequest({
    required this.grid,
    this.config = const ExpectimaxConfig(),
  }) : assert(grid.length == kBoardCells, 'grid must be 16 cells');

  final List<int> grid;
  final ExpectimaxConfig config;
}

/// Outcome of a single Expectimax search.
final class SolveResult {
  const SolveResult({
    required this.move,
    required this.score,
    required this.explored,
    required this.duration,
  });

  /// Best direction found. `null` only when [SolveRequest.grid] is
  /// terminal.
  final String? move;

  /// Score of [move], or `-infinity` when the starting position is
  /// terminal.
  final double score;

  /// Approximate node count explored.
  final int explored;

  /// Wall-clock time spent in the search.
  final Duration duration;
}

/// Pure solver — no I/O, no logging, safe to call from any isolate.
final class ExpectimaxSolver {
  const ExpectimaxSolver();

  /// Returns the best move for [request].
  ///
  /// Throws [TerminalBoardException] when [request.grid] has no legal
  /// moves. Throws [SolverTimeoutException] when the time budget
  /// elapses before the search completes.
  SolveResult solve(SolveRequest request) {
    final cfg = request.config;
    assert(cfg.timeBudget > Duration.zero, 'timeBudget must be > 0');
    final startMs = _now();
    final deadlineMs = startMs + cfg.timeBudget.inMilliseconds;
    final state = _SolverState(cfg: cfg, deadlineMs: deadlineMs, startMs: startMs);

    if (Heuristics.hasNoMoves(request.grid)) {
      throw const TerminalBoardException('No legal moves on starting board.');
    }

    final roots = _scoreRoot(request.grid, state);
    final best = _argmax(roots);

    return SolveResult(
      move: best?.direction,
      score: best?.score ?? double.negativeInfinity,
      explored: state.explored,
      duration: Duration(milliseconds: _now() - startMs),
    );
  }

  /// Entry point for `compute()` — must be a top-level / static
  /// function.
  static SolveResult runSolver(SolveRequest request) =>
      const ExpectimaxSolver().solve(request);

  // ── Search ────────────────────────────────────────────────────────────

  List<_RootMove> _scoreRoot(List<int> board, _SolverState state) {
    final legal = _legalMoves(board);
    if (legal.isEmpty) return const <_RootMove>[];

    // Move ordering is critical: the better-ordered root prunes more
    // of the chance layer.
    final ordered = MoveOrdering.rank(board, config: state.cfg.heuristics)
        .where(legal.contains)
        .toList(growable: false);

    return [
      for (final dir in ordered)
        _RootMove(
          direction: dir,
          score: _expectimax(
            Heuristics.simulateMove(board, dir),
            state.cfg.depth - 1,
            state,
            isChance: true,
          ),
        ),
    ];
  }

  /// Recursive Expectimax step.
  ///
  /// At a chance node we sample every empty cell with the 2/4 spawn
  /// distribution (0.9 / 0.1). At a max node we evaluate every legal
  /// direction using [Heuristics.evaluate] at depth 0.
  double _expectimax(
    List<int> board,
    int depth,
    _SolverState state, {
    required bool isChance,
  }) {
    state.explored++;
    _maybeThrow(state);

    if (depth <= 0) {
      return Heuristics.evaluate(board, config: state.cfg.heuristics);
    }

    if (Heuristics.hasNoMoves(board)) {
      return double.negativeInfinity;
    }

    if (isChance) {
      final empties = _emptyCellsWithIndex(board);
      if (empties.isEmpty) {
        return _expectimax(board, depth - 1, state, isChance: false);
      }

      var weighted = 0.0;
      for (final entry in empties) {
        final (idx, _) = entry;
        // 2-tile spawn (90 %).
        final with2 = _setTile(board, idx, 2);
        weighted +=
            0.9 * _expectimax(with2, depth - 1, state, isChance: false);
        // 4-tile spawn (10 %), capped to ensure no out-of-range value.
        final with4 = _setTile(board, idx, 4);
        weighted +=
            0.1 * _expectimax(with4, depth - 1, state, isChance: false);
      }
      return weighted / empties.length;
    }

    // Max node.
    final legal = _legalMoves(board);
    if (legal.isEmpty) return double.negativeInfinity;

    final ordered = MoveOrdering.rank(board, config: state.cfg.heuristics)
        .where(legal.contains)
        .toList(growable: false);

    var best = double.negativeInfinity;
    for (final dir in ordered) {
      final next = Heuristics.simulateMove(board, dir);
      final v = _expectimax(next, depth - 1, state, isChance: true);
      best = math.max(best, v);
    }
    return best;
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  void _maybeThrow(_SolverState state) {
    if (_now() > state.deadlineMs) {
      throw SolverTimeoutException(
        'Search exceeded ${state.cfg.timeBudget.inMilliseconds} ms after '
        '${state.explored} nodes.',
      );
    }
  }

  /// Subset of [kAllDirections] that actually changes [board].
  List<String> _legalMoves(List<int> board) {
    return [for (final d in kAllDirections) if (Heuristics.movesBoard(board, d)) d];
  }

  /// `(index, currentValue)` for every empty cell.
  List<(int, int)> _emptyCellsWithIndex(List<int> board) {
    final out = <(int, int)>[];
    for (var i = 0; i < board.length; i++) {
      if (board[i] == 0) out.add((i, 0));
    }
    return out;
  }

  /// Returns a fresh board with [board[idx] = value].
  List<int> _setTile(List<int> board, int idx, int value) {
    final copy = List<int>.of(board);
    copy[idx] = value;
    return copy;
  }

  /// Best `(direction, score)` or `null` when [entries] is empty.
  _RootMove? _argmax(List<_RootMove> entries) {
    if (entries.isEmpty) return null;
    var best = entries.first;
    for (final candidate in entries.skip(1)) {
      if (candidate.score > best.score) best = candidate;
    }
    return best;
  }

  int _now() => DateTime.now().millisecondsSinceEpoch;
}

class _RootMove {
  const _RootMove({required this.direction, required this.score});
  final String direction;
  final double score;
}

class _SolverState {
  _SolverState({
    required this.cfg,
    required this.deadlineMs,
    required this.startMs,
  });
  final ExpectimaxConfig cfg;
  final int deadlineMs;
  final int startMs;
  int explored = 0;
}
