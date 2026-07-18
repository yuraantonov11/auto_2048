/// Pure-Dart evaluation function for the 2048 bot.
///
/// The heuristic mirrors ovolve's 2048-AI reference (the most cited
/// Expectimax heuristic for 2048) with two strict 2048-bot additions:
///
///   1. **Strict corner penalty** — when the largest tile is *not* in
///      [HeuristicsConfig.anchorCorner] the score receives a massive
///      negative penalty proportional to `log2(maxTile)`. Without this
///      penalty the solver occasionally chases a "good" intermediate
///      move that shifts the max tile out of the snake's head, which
///      then never re-anchors and the bot gets stuck.
///
///   2. **Anchor-aware monotonicity bonus** — the standard
///      `min(ascending, descending)` per row / column is amplified with
///      [HeuristicsConfig.monoPower] = 4 to keep the gradient steep
///      away from the anchor corner.
///
/// All public APIs are pure: they take inputs and return values without
/// touching global state, the file system or the network. They are
/// safe to call from any isolate.
library;

import 'dart:math' as math;

import 'exceptions.dart';

/// Number of rows / columns of the 2048 board.
const int kBoardSize = 4;

/// Total number of cells (rows × columns).
const int kBoardCells = 16;

/// Bottom-right corner is index `3 * 4 + 3 = 15`.
///
/// Keep this in sync with `GameSolver.ANCHOR_CORNER` on the Android side.
const int kDefaultAnchorCorner = kBoardCells - 1;

/// All four cardinal directions the solver can emit.
const List<String> kAllDirections = <String>['UP', 'DOWN', 'LEFT', 'RIGHT'];

// ──────────────────────────── Tunable weights ────────────────────────────

/// Tunable weights for [Heuristics.evaluate].
///
/// The class is an immutable value object: pass it through
/// [HeuristicsConfig.copyWith] to derive adjusted variants without
/// mutating the original. Defaults mirror the native Kotlin solver and
/// add the strict corner penalty.
///
/// ```dart
/// const cfg = HeuristicsConfig.defaults.copyWith(cornerPenaltyWeight: 5000);
/// final score = Heuristics.evaluate(board, config: cfg);
/// ```
final class HeuristicsConfig {
  const HeuristicsConfig({
    this.snakeWeight = 20.0,
    // Empty cells are the flexibility budget. A stuck board always
    // loses. Bumped from 270 → 700 so the bot prefers moves that leave
    // space over moves that fill the board.
    this.emptyWeight = 700.0,
    this.monoWeight = 80.0,
    this.smoothWeight = 0.1,
    this.mergeWeight = 10.0,
    this.cornerBonusWeight = 1000.0,
    // Strict penalty when the max tile is NOT in [anchorCorner]. Twice
    // the bonus makes it strictly worse than leaving the board alone.
    this.cornerPenaltyWeight = 2000.0,
    this.monoPower = 4.0,
    this.anchorCorner = kDefaultAnchorCorner,
  });

  /// Sensible defaults used when no config is supplied.
  static const HeuristicsConfig defaults = HeuristicsConfig();

  /// Snake-order weighting (exponential decay from the anchor).
  final double snakeWeight;

  /// Empty cell reward — a stuck board always loses.
  final double emptyWeight;

  /// Monotonicity reward (rows / columns go one direction).
  final double monoWeight;

  /// Smoothness reward (orthogonal neighbours are close in log-scale).
  final double smoothWeight;

  /// Mergeable pair bonus — adjacent equal tiles ready to combine.
  final double mergeWeight;

  /// Bonus when the max tile is in [anchorCorner].
  final double cornerBonusWeight;

  /// Strict penalty when the max tile is NOT in [anchorCorner]. Must be
  /// large enough to dominate the other signals when the bot would
  /// otherwise shift the max tile.
  final double cornerPenaltyWeight;

  /// Exponent for monotonicity's `|log2(a) - log2(b)|^p` term. Power
  /// = 4 amplifies large gradients and demotes small ones.
  final double monoPower;

  /// Index of the corner used as the snake anchor.
  final int anchorCorner;

  /// Returns a copy with the supplied fields replaced.
  HeuristicsConfig copyWith({
    double? snakeWeight,
    double? emptyWeight,
    double? monoWeight,
    double? smoothWeight,
    double? mergeWeight,
    double? cornerBonusWeight,
    double? cornerPenaltyWeight,
    double? monoPower,
    int? anchorCorner,
  }) {
    return HeuristicsConfig(
      snakeWeight: snakeWeight ?? this.snakeWeight,
      emptyWeight: emptyWeight ?? this.emptyWeight,
      monoWeight: monoWeight ?? this.monoWeight,
      smoothWeight: smoothWeight ?? this.smoothWeight,
      mergeWeight: mergeWeight ?? this.mergeWeight,
      cornerBonusWeight: cornerBonusWeight ?? this.cornerBonusWeight,
      cornerPenaltyWeight: cornerPenaltyWeight ?? this.cornerPenaltyWeight,
      monoPower: monoPower ?? this.monoPower,
      anchorCorner: anchorCorner ?? this.anchorCorner,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is HeuristicsConfig &&
        snakeWeight == other.snakeWeight &&
        emptyWeight == other.emptyWeight &&
        monoWeight == other.monoWeight &&
        smoothWeight == other.smoothWeight &&
        mergeWeight == other.mergeWeight &&
        cornerBonusWeight == other.cornerBonusWeight &&
        cornerPenaltyWeight == other.cornerPenaltyWeight &&
        monoPower == other.monoPower &&
        anchorCorner == other.anchorCorner;
  }

  @override
  int get hashCode => Object.hash(
        snakeWeight,
        emptyWeight,
        monoWeight,
        smoothWeight,
        mergeWeight,
        cornerBonusWeight,
        cornerPenaltyWeight,
        monoPower,
        anchorCorner,
      );

  @override
  String toString() => 'HeuristicsConfig('
      'snake=$snakeWeight, empty=$emptyWeight, mono=$monoWeight, '
      'smooth=$smoothWeight, merge=$mergeWeight, '
      'cornerBonus=$cornerBonusWeight, cornerPenalty=$cornerPenaltyWeight, '
      'monoPower=$monoPower, anchor=$anchorCorner)';
}

// ──────────────────────────── Per-component score ────────────────────────────

/// Breakdown of a single [Heuristics.evaluate] call.
///
/// Returned by [Heuristics.evaluateDetailed] so the controller can log
/// or display the individual components without re-running the
/// expensive computation. The [total] matches what plain
/// [Heuristics.evaluate] returns.
final class HeuristicScore {
  const HeuristicScore({
    required this.total,
    required this.snake,
    required this.empty,
    required this.monotonicity,
    required this.smoothness,
    required this.corner,
    required this.merges,
  });

  /// Sum of all weighted components, before clamping. Use this as the
  /// canonical comparison value.
  final double total;

  /// Weighted contribution of the snake-order signal.
  final double snake;

  /// Weighted contribution of the empty-cell reward.
  final double empty;

  /// Weighted contribution of the monotonicity signal.
  final double monotonicity;

  /// Weighted contribution of the smoothness signal.
  final double smoothness;

  /// Weighted contribution of the corner signal (positive bonus when
  /// anchored, negative penalty when displaced).
  final double corner;

  /// Weighted contribution of the mergeable-pair bonus.
  final double merges;
}

// ──────────────────────────── Static helpers ────────────────────────────

/// Stateless utilities for evaluating 2048 boards. Every method is
/// pure, total, and side-effect free so it can be called from any
/// isolate, repeated freely, and substituted with fakes in tests.
abstract final class Heuristics {
  Heuristics._();

  // Snake ordering: rank 0 is the anchor, rank 15 is the worst
  // placement. [snakeOrder][k] is the cell index with rank k.
  static const List<int> snakeOrder = <int>[
    15, 14, 13, 12,
    11, 10,  9,  8,
     7,  6,  5,  4,
     3,  2,  1,  0,
  ];

  /// `positionWeight[p]` = snake rank of position `p`
  /// (`0` = anchor, `15` = tail). Inverse lookup of [snakeOrder].
  static final List<int> positionWeight = _buildPositionWeight();

  static List<int> _buildPositionWeight() {
    final w = List<int>.filled(kBoardCells, 0);
    for (var k = 0; k < kBoardCells; k++) {
      w[snakeOrder[k]] = k;
    }
    return List<int>.unmodifiable(w);
  }

  /// Pre-computed `log2(value)` lookup. Index `0` is treated as `0`;
  /// indices `1..17` cover every possible tile from `2` up to `131072`
  /// so the evaluator never calls `math.log` in the hot path.
  static final List<double> _logTable = _buildLogTable();

  static List<double> _buildLogTable() {
    final t = List<double>.filled(18, 0.0);
    for (var i = 1; i < t.length; i++) {
      t[i] = math.log(i) / math.ln2;
    }
    return List<double>.unmodifiable(t);
  }

  /// Returns `log2(value)` clamped to the lookup table range.
  static double log2(int value) {
    if (value <= 0) return 0.0;
    if (value < _logTable.length) return _logTable[value];
    // Out-of-range (shouldn't happen for legal 2048 boards). Fall back
    // to a real log so the heuristic stays defined.
    return math.log(value) / math.ln2;
  }

  // ── Board queries ─────────────────────────────────────────────────────

  /// Combined heuristic score for [board].
  ///
  /// The score has six signals:
  ///   1. Snake order — `log2(value) * (16 - rank)` summed over all cells.
  ///   2. Empty cells — flexibility budget (count of zero cells).
  ///   3. Monotonicity — `min(asc, desc)` per row / column with [HeuristicsConfig.monoPower].
  ///   4. Smoothness — `-sum |log2(a) - log2(b)|` over orthogonal pairs.
  ///   5. Corner — bonus if max tile is in [HeuristicsConfig.anchorCorner].
  ///   6. Merges — count of orthogonal equal pairs.
  ///
  /// Returns `-infinity` if [board] is terminal (no legal moves).
  ///
  /// Throws [InvalidBoardException] when [board] does not have exactly
  /// [kBoardCells] cells or contains a negative value.
  static double evaluate(List<int> board, {HeuristicsConfig? config}) {
    return evaluateDetailed(board, config: config).total;
  }

  /// Same as [evaluate] but exposes every weighted component so the
  /// caller can log / display them.
  ///
  /// The returned [HeuristicScore.total] matches [evaluate].
  static HeuristicScore evaluateDetailed(
    List<int> board, {
    HeuristicsConfig? config,
  }) {
    final cfg = config ?? HeuristicsConfig.defaults;
    _assertBoard(board);

    if (hasNoMoves(board)) {
      return const HeuristicScore(
        total: double.negativeInfinity,
        snake: double.negativeInfinity,
        empty: double.negativeInfinity,
        monotonicity: double.negativeInfinity,
        smoothness: double.negativeInfinity,
        corner: double.negativeInfinity,
        merges: double.negativeInfinity,
      );
    }

    var emptyCount = 0.0;
    var snake = 0.0;
    var maxTile = 0;
    var maxIdx = -1;
    for (var i = 0; i < kBoardCells; i++) {
      final v = board[i];
      if (v == 0) {
        emptyCount += 1.0;
        continue;
      }
      // Snake weight: closer to the anchor scores more. Multiplying by
      // tile value (in log-space) keeps the snake proportional to
      // actual tile size.
      snake += log2(v) * (kBoardCells - positionWeight[i]);
      if (v > maxTile) {
        maxTile = v;
        maxIdx = i;
      }
    }

    final mono = monotonicity(board, config: cfg);
    final smooth = smoothness(board);
    final merges = mergeablePairs(board).toDouble();

    // Strict corner logic: bonus when anchored, penalty when displaced.
    final double cornerTerm;
    if (maxTile == 0) {
      cornerTerm = 0.0;
    } else if (maxIdx == cfg.anchorCorner) {
      cornerTerm = log2(maxTile) * cfg.cornerBonusWeight;
    } else {
      cornerTerm = -log2(maxTile) * cfg.cornerPenaltyWeight;
    }

    final total = cfg.snakeWeight * snake +
        cfg.emptyWeight * emptyCount +
        cfg.monoWeight * mono +
        cfg.smoothWeight * smooth +
        cornerTerm +
        cfg.mergeWeight * merges;

    return HeuristicScore(
      total: total,
      snake: cfg.snakeWeight * snake,
      empty: cfg.emptyWeight * emptyCount,
      monotonicity: cfg.monoWeight * mono,
      smoothness: cfg.smoothWeight * smooth,
      corner: cornerTerm,
      merges: cfg.mergeWeight * merges,
    );
  }

  /// Monotonicity score: `min(ascending, descending)` penalty per row
  /// and column, raised to [HeuristicsConfig.monoPower]. Returns the
  /// negated total — larger (more positive) means more monotonic.
  static double monotonicity(List<int> board, {HeuristicsConfig? config}) {
    final cfg = config ?? HeuristicsConfig.defaults;
    _assertBoard(board);
    var total = 0.0;

    for (var r = 0; r < kBoardSize; r++) {
      var inc = 0.0;
      var dec = 0.0;
      for (var c = 0; c < kBoardSize - 1; c++) {
        final a = log2(board[r * kBoardSize + c]);
        final b = log2(board[r * kBoardSize + c + 1]);
        final penalty = math.pow((a - b).abs(), cfg.monoPower).toDouble();
        if (a > b) {
          inc += penalty;
        } else {
          dec += penalty;
        }
      }
      total += math.min(inc, dec);
    }

    for (var c = 0; c < kBoardSize; c++) {
      var inc = 0.0;
      var dec = 0.0;
      for (var r = 0; r < kBoardSize - 1; r++) {
        final a = log2(board[r * kBoardSize + c]);
        final b = log2(board[(r + 1) * kBoardSize + c]);
        final penalty = math.pow((a - b).abs(), cfg.monoPower).toDouble();
        if (a > b) {
          inc += penalty;
        } else {
          dec += penalty;
        }
      }
      total += math.min(inc, dec);
    }

    return -total;
  }

  /// Negative sum of `|log2(a) - log2(b)|` over orthogonal neighbours.
  /// Larger (less negative) means neighbours are closer in log-space.
  static double smoothness(List<int> board) {
    _assertBoard(board);
    var s = 0.0;
    for (var r = 0; r < kBoardSize; r++) {
      for (var c = 0; c < kBoardSize - 1; c++) {
        final a = board[r * kBoardSize + c];
        final b = board[r * kBoardSize + c + 1];
        if (a != 0 && b != 0) {
          s -= (log2(a) - log2(b)).abs();
        }
      }
    }
    for (var c = 0; c < kBoardSize; c++) {
      for (var r = 0; r < kBoardSize - 1; r++) {
        final a = board[r * kBoardSize + c];
        final b = board[(r + 1) * kBoardSize + c];
        if (a != 0 && b != 0) {
          s -= (log2(a) - log2(b)).abs();
        }
      }
    }
    return s;
  }

  /// Count of orthogonal adjacent equal non-zero pairs.
  static int mergeablePairs(List<int> board) {
    _assertBoard(board);
    var n = 0;
    for (var r = 0; r < kBoardSize; r++) {
      for (var c = 0; c < kBoardSize - 1; c++) {
        final a = board[r * kBoardSize + c];
        final b = board[r * kBoardSize + c + 1];
        if (a != 0 && a == b) n++;
      }
    }
    for (var c = 0; c < kBoardSize; c++) {
      for (var r = 0; r < kBoardSize - 1; r++) {
        final a = board[r * kBoardSize + c];
        final b = board[(r + 1) * kBoardSize + c];
        if (a != 0 && a == b) n++;
      }
    }
    return n;
  }

  /// Number of empty cells on [board].
  static int emptyCells(List<int> board) {
    _assertBoard(board);
    var n = 0;
    for (final v in board) {
      if (v == 0) n++;
    }
    return n;
  }

  /// Index of the largest tile, or `-1` if the board is empty.
  static int maxTileIndex(List<int> board) {
    _assertBoard(board);
    var maxV = 0;
    var maxI = -1;
    for (var i = 0; i < board.length; i++) {
      if (board[i] > maxV) {
        maxV = board[i];
        maxI = i;
      }
    }
    return maxI;
  }

  /// Largest tile value, or `0` if the board is empty.
  static int maxTile(List<int> board) {
    _assertBoard(board);
    var maxV = 0;
    for (final v in board) {
      if (v > maxV) maxV = v;
    }
    return maxV;
  }

  /// True when the game is genuinely terminal: the board is full *and*
  /// every legal move leaves it unchanged. A board with empty cells is
  /// never terminal — the spawn keeps the game alive even if every
  /// swipe is currently a no-op.
  static bool hasNoMoves(List<int> board) {
    _assertBoard(board);
    if (emptyCells(board) > 0) return false;
    for (final dir in kAllDirections) {
      if (movesBoard(board, dir)) return false;
    }
    return true;
  }

  /// True when swiping in [direction] would change the board.
  static bool movesBoard(List<int> board, String direction) {
    _assertBoard(board);
    _assertDirection(direction);
    final next = simulateMove(board, direction);
    for (var i = 0; i < kBoardCells; i++) {
      if (board[i] != next[i]) return true;
    }
    return false;
  }

  /// Pure simulation of a swipe in [direction]. Returns a new 16-cell
  /// list with tiles collapsed and merged using the canonical 2048
  /// rules. Does not spawn a new tile.
  ///
  /// Throws [IllegalDirectionException] when [direction] is not one of
  /// [kAllDirections].
  static List<int> simulateMove(List<int> board, String direction) {
    _assertBoard(board);
    _assertDirection(direction);
    return switch (direction) {
      'LEFT' => _slideRows(board, reversed: false),
      'RIGHT' => _slideRows(board, reversed: true),
      'UP' => _slideCols(board, reversed: false),
      'DOWN' => _slideCols(board, reversed: true),
      _ =>
        throw IllegalDirectionException('Unsupported direction: $direction'),
    };
  }

  static List<int> _reverse(List<int> list) => list.reversed.toList();

  static List<int> _slideRows(List<int> board, {required bool reversed}) {
    final out = List<int>.filled(kBoardCells, 0);
    for (var r = 0; r < kBoardSize; r++) {
      var line = List<int>.generate(
        kBoardSize,
        (c) => board[r * kBoardSize + c],
        growable: false,
      );
      if (reversed) line = _reverse(line);
      final collapsed = _collapse(line);
      final justified = List<int>.filled(kBoardSize, 0);
      for (var i = 0; i < collapsed.length && i < kBoardSize; i++) {
        justified[i] = collapsed[i];
      }
      final ordered = reversed ? _reverse(justified) : justified;
      for (var c = 0; c < kBoardSize; c++) {
        out[r * kBoardSize + c] = ordered[c];
      }
    }
    return out;
  }

  static List<int> _slideCols(List<int> board, {required bool reversed}) {
    final out = List<int>.filled(kBoardCells, 0);
    for (var c = 0; c < kBoardSize; c++) {
      var line = List<int>.generate(
        kBoardSize,
        (r) => board[r * kBoardSize + c],
        growable: false,
      );
      if (reversed) line = _reverse(line);
      final collapsed = _collapse(line);
      final justified = List<int>.filled(kBoardSize, 0);
      for (var i = 0; i < collapsed.length && i < kBoardSize; i++) {
        justified[i] = collapsed[i];
      }
      final ordered = reversed ? _reverse(justified) : justified;
      for (var r = 0; r < kBoardSize; r++) {
        out[r * kBoardSize + c] = ordered[r];
      }
    }
    return out;
  }

  /// Collapse a 4-element line: shift non-zero tiles to the front,
  /// merge one pair of adjacent equals, then re-pad with zeros.
  static List<int> _collapse(List<int> line) {
    final compact = <int>[];
    for (final v in line) {
      if (v != 0) compact.add(v);
    }
    final merged = <int>[];
    var skip = false;
    for (var i = 0; i < compact.length; i++) {
      if (skip) {
        skip = false;
        continue;
      }
      if (i + 1 < compact.length && compact[i] == compact[i + 1]) {
        merged.add(compact[i] * 2);
        skip = true;
      } else {
        merged.add(compact[i]);
      }
    }
    while (merged.length < kBoardSize) {
      merged.add(0);
    }
    return merged;
  }

  // ── Assertions ────────────────────────────────────────────────────────

  static void _assertBoard(List<int> board) {
    if (board.length != kBoardCells) {
      throw InvalidBoardException(
        'Board must have $kBoardCells cells, got ${board.length}.',
      );
    }
    for (final v in board) {
      if (v < 0) {
        throw InvalidBoardException(
          'Board cells must be non-negative, found $v.',
        );
      }
    }
  }

  static void _assertDirection(String direction) {
    if (!kAllDirections.contains(direction)) {
      throw IllegalDirectionException(
        'Direction must be one of $kAllDirections, got "$direction".',
      );
    }
  }
}

// ──────────────────────────── Move ordering ────────────────────────────

/// Cheap one-ply move ordering for the Expectimax solver.
///
/// The Expectimax root enumerates directions best-first so that even
/// when the search is cut off by the time budget the partial result is
/// the strongest legal move.
///
/// ```dart
/// final ordered = MoveOrdering.rank(board);
///
/// for (final move in ordered) {
///   final next = Heuristics.simulateMove(board, move);
///   if (!Heuristics.movesBoard(board, move)) continue; // skip no-ops
///   // ...
/// }
/// ```
abstract final class MoveOrdering {
  MoveOrdering._();

  /// Returns [kAllDirections] sorted by descending one-ply heuristic
  /// score. Moves that produce the same score are ordered by their
  /// index in [kAllDirections] to keep results deterministic.
  static List<String> rank(
    List<int> board, {
    HeuristicsConfig? config,
  }) {
    double scoreFor(String dir) {
      final next = Heuristics.simulateMove(board, dir);
      return Heuristics.evaluate(next, config: config);
    }

    final indexed = <_Ranked>[
      for (var i = 0; i < kAllDirections.length; i++)
        _Ranked(kAllDirections[i], scoreFor(kAllDirections[i]), i),
    ];

    indexed.sort((a, b) {
      final byScore = b.score.compareTo(a.score);
      if (byScore != 0) return byScore;
      return a.order.compareTo(b.order);
    });

    return [for (final r in indexed) r.move];
  }
}

class _Ranked {
  const _Ranked(this.move, this.score, this.order);
  final String move;
  final double score;
  final int order;
}