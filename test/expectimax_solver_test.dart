import 'package:auto_2048/bot/exceptions.dart';
import 'package:auto_2048/bot/expectimax_solver.dart';
import 'package:auto_2048/bot/heuristics.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ExpectimaxSolver', () {
    test('throws TerminalBoardException on a full, no-move board', () {
      final board = <int>[
        2, 4, 2, 4,
        4, 2, 4, 2,
        2, 4, 2, 4,
        4, 2, 4, 2,
      ];
      const solver = ExpectimaxSolver();
      expect(
        () => solver.solve(SolveRequest(grid: board)),
        throwsA(isA<TerminalBoardException>()),
      );
    });

    test('returns a legal direction on a fresh board', () {
      final board = <int>[
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 2, 0, 0,
      ];
      const solver = ExpectimaxSolver();
      final result = solver.solve(
        SolveRequest(
          grid: board,
          config: const ExpectimaxConfig(
            depth: 2,
            timeBudget: Duration(seconds: 2),
          ),
        ),
      );
      expect(result.move, isNotNull);
      expect(kAllDirections, contains(result.move));
      expect(result.score, isNot(double.negativeInfinity));
    });

    test('keeps the max tile in the corner under heavy penalty', () {
      // Snake-ish board with the 128 anchored at index 15.
      final board = <int>[
        2,   4,   8,  16,
        4,   8,  16,  32,
        8,  16,  32,  64,
        16, 32,   0, 128,
      ];
      const solver = ExpectimaxSolver();
      final result = solver.solve(
        SolveRequest(
          grid: board,
          config: const ExpectimaxConfig(
            depth: 3,
            timeBudget: Duration(seconds: 2),
          ),
        ),
      );
      expect(result.move, isNotNull);
      final next = Heuristics.simulateMove(board, result.move!);
      expect(next[15], 128);
    });

    test('SolveRequest invariants are enforced', () {
      expect(
        () => SolveRequest(grid: <int>[1, 2, 3]),
        throwsA(isA<AssertionError>()),
      );
    });
  });
}
