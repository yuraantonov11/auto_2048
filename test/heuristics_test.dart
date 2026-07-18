import 'package:auto_2048/bot/exceptions.dart';
import 'package:auto_2048/bot/heuristics.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('HeuristicsConfig', () {
    test('default values are sane', () {
      const c = HeuristicsConfig();
      expect(c.cornerPenaltyWeight, greaterThan(c.cornerBonusWeight));
      expect(c.monoPower, greaterThanOrEqualTo(2.0));
      expect(c.anchorCorner, kDefaultAnchorCorner);
    });

    test('copyWith replaces only supplied fields', () {
      const base = HeuristicsConfig(snakeWeight: 1.0, monoWeight: 2.0);
      final next = base.copyWith(snakeWeight: 7.0);
      expect(next.snakeWeight, 7.0);
      expect(next.monoWeight, 2.0);
    });

    test('value equality and hashCode', () {
      const a = HeuristicsConfig(snakeWeight: 3.0);
      const b = HeuristicsConfig(snakeWeight: 3.0);
      const c = HeuristicsConfig(snakeWeight: 4.0);
      expect(a, equals(b));
      expect(a.hashCode, equals(b.hashCode));
      expect(a, isNot(equals(c)));
    });
  });

  group('Heuristics.evaluate', () {
    test('returns -infinity on a terminal board', () {
      final board = <int>[
        2, 4, 2, 4,
        4, 2, 4, 2,
        2, 4, 2, 4,
        4, 2, 4, 2,
      ];
      expect(Heuristics.hasNoMoves(board), isTrue);
      expect(Heuristics.evaluate(board), double.negativeInfinity);
    });

    test('strict corner penalty dominates other signals', () {
      // Max tile (16) is in the corner - bonus applied.
      final anchored = <int>[
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 16,
      ];
      final anchoredScore = Heuristics.evaluate(anchored);

      // Same tile, displaced - strict penalty should drop the score
      // well below the anchored configuration.
      final displaced = <int>[
        0, 0, 0, 16,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
      ];
      final displacedScore = Heuristics.evaluate(displaced);

      expect(anchoredScore, greaterThan(displacedScore));
      // Penalty is 2x the bonus -> moving the max out of the corner must
      // be strictly worse than an empty start.
      final empty = <int>[
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
      ];
      expect(displacedScore, lessThan(Heuristics.evaluate(empty)));
    });

    test('detects monotonic snake vs chaotic board', () {
      // Snake is mostly full but has one empty cell so it is not
      // flagged as terminal.
      final snake = <int>[
        2,   4,   8,  16,
        4,   8,  16,  32,
        8,  16,   0,  64,
        16, 32,  64, 128,
      ];
      // Chaotic board also keeps empty cells so it is not terminal.
      final chaotic = <int>[
        2, 64,   4, 32,
        0,  8,  16,  0,
        4, 32,   0,  8,
        16, 2,  32, 64,
      ];
      expect(
        Heuristics.evaluate(snake),
        greaterThan(Heuristics.evaluate(chaotic)),
      );
    });

    test('empty cells count matches', () {
      final board = <int>[
        0, 2, 0, 4,
        0, 0, 8, 0,
        0, 16, 0, 0,
        32, 0, 0, 64,
      ];
      expect(Heuristics.emptyCells(board), 10);
      expect(Heuristics.maxTile(board), 64);
      expect(Heuristics.maxTileIndex(board), 15);
    });

    test('throws InvalidBoardException on malformed input', () {
      expect(() => Heuristics.evaluate([1, 2, 3]),
          throwsA(isA<InvalidBoardException>()));
      expect(() => Heuristics.evaluate(<int>[
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, -1,
          ]),
          throwsA(isA<InvalidBoardException>()));
    });

    test('evaluateDetailed returns all components', () {
      final board = <int>[
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 8,
      ];
      final score = Heuristics.evaluateDetailed(board);
      expect(score.total, equals(Heuristics.evaluate(board)));
      expect(score.corner, greaterThan(0));
      expect(score.snake, greaterThan(0));
    });
  });

  group('Heuristics.simulateMove', () {
    test('left compresses and merges correctly', () {
      final board = <int>[
        2, 2, 4, 0,
        0, 0, 0, 0,
        4, 4, 4, 4,
        8, 0, 8, 0,
      ];
      final moved = Heuristics.simulateMove(board, 'LEFT');
      expect(moved, <int>[
        4, 4, 0, 0,
        0, 0, 0, 0,
        8, 8, 0, 0,
        16, 0, 0, 0,
      ]);
    });

    test('right reverses the line before collapsing', () {
      final board = <int>[
        2, 2, 4, 0,
        0, 0, 0, 0,
        4, 4, 4, 4,
        8, 0, 8, 0,
      ];
      final moved = Heuristics.simulateMove(board, 'RIGHT');
      expect(moved, <int>[
        0, 0, 4, 4,
        0, 0, 0, 0,
        0, 0, 8, 8,
        0, 0, 0, 16,
      ]);
    });

    test('up collapses each column independently', () {
      final board = <int>[
        2, 0, 4, 0,
        2, 0, 4, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
      ];
      final moved = Heuristics.simulateMove(board, 'UP');
      expect(moved, <int>[
        4, 0, 8, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
      ]);
    });

    test('down reverses each column before collapsing', () {
      final board = <int>[
        2, 0, 4, 0,
        2, 0, 4, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
      ];
      final moved = Heuristics.simulateMove(board, 'DOWN');
      expect(moved, <int>[
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        4, 0, 8, 0,
      ]);
    });

    test('rejects illegal direction', () {
      final board = List<int>.filled(kBoardCells, 0);
      expect(() => Heuristics.simulateMove(board, 'NORTH'),
          throwsA(isA<IllegalDirectionException>()));
    });
  });

  group('MoveOrdering.rank', () {
    test('returns every direction in some order', () {
      final board = <int>[
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 32,
      ];
      final ordered = MoveOrdering.rank(board);
      expect(ordered.length, kAllDirections.length);
      expect(ordered.toSet(), kAllDirections.toSet());
    });

    test('result is deterministic given same input', () {
      final board = <int>[
        2, 4, 2, 4,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
      ];
      final a = MoveOrdering.rank(board);
      final b = MoveOrdering.rank(board);
      expect(a, equals(b));
    });
  });

  group('Heuristics.movesBoard', () {
    test('true when swipe changes the board', () {
      final board = <int>[
        2, 2, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
      ];
      expect(Heuristics.movesBoard(board, 'LEFT'), isTrue);
    });

    test('false when swipe is a no-op', () {
      // Adjacent tiles are different and already flush against the
      // target edge - collapse does not change the board.
      final board = <int>[
        2, 4, 2, 4,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
      ];
      expect(Heuristics.movesBoard(board, 'RIGHT'), isFalse);
    });
  });
}
