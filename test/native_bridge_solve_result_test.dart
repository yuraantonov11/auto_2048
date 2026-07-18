import 'package:auto_2048/bot/native_bridge.dart';
import 'package:flutter_test/flutter_test.dart';

/// Unit tests for [SolveStepResult.fromNative] - the bridge decoder for
/// the dual-mode response returned by the Kotlin `solveStep` handler.
/// The Kotlin side either replies with a direction string (happy path)
/// or with a structured map reporting a terminal-state detect + restart.
void main() {
  group('SolveStepResult.fromNative', () {
    test('returns SolveStepMove for a recognised direction string', () {
      final result = SolveStepResult.fromNative('LEFT');
      expect(result, isA<SolveStepMove>());
      expect((result as SolveStepMove).direction, 'LEFT');
    });

    test('normalises lowercase direction strings', () {
      final result = SolveStepResult.fromNative('right');
      expect(result, isA<SolveStepMove>());
      expect((result as SolveStepMove).direction, 'RIGHT');
    });

    test('returns null for an unknown direction string', () {
      expect(SolveStepResult.fromNative('DIAGONAL'), isNull);
      expect(SolveStepResult.fromNative(''), isNull);
    });

    test('returns null for null payloads', () {
      expect(SolveStepResult.fromNative(null), isNull);
    });

    test('returns SolveStepTerminalResult.restarted for a won restart', () {
      final result = SolveStepResult.fromNative(<String, dynamic>{
        'status': 'restarted',
        'state': 'won',
      });
      expect(result, isA<SolveStepTerminalResult>());
      final terminal = result as SolveStepTerminalResult;
      expect(terminal.state, 'won');
      expect(terminal.alreadyTerminalGrid, isEmpty);
    });

    test('returns SolveStepTerminalResult.restarted for a gameover restart', () {
      final result = SolveStepResult.fromNative(<String, dynamic>{
        'status': 'restarted',
        'state': 'gameover',
      });
      expect(result, isA<SolveStepTerminalResult>());
      final terminal = result as SolveStepTerminalResult;
      expect(terminal.state, 'gameover');
      expect(terminal.alreadyTerminalGrid, isEmpty);
    });

    test('returns SolveStepTerminalResult.alreadyTerminal with grid', () {
      final result = SolveStepResult.fromNative(<String, dynamic>{
        'status': 'terminal',
        'grid': <int>[2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2, 4, 8, 16, 32, 64],
      });
      expect(result, isA<SolveStepTerminalResult>());
      final terminal = result as SolveStepTerminalResult;
      expect(terminal.state, 'terminal');
      expect(terminal.alreadyTerminalGrid, hasLength(16));
    });

    test('returns null for a map with an unknown status', () {
      final result = SolveStepResult.fromNative(<String, dynamic>{
        'status': 'something_else',
      });
      expect(result, isNull);
    });

    test('returns null for non-string, non-map payloads', () {
      expect(SolveStepResult.fromNative(42), isNull);
      expect(SolveStepResult.fromNative(true), isNull);
      expect(SolveStepResult.fromNative(<Object>[1, 2, 3]), isNull);
    });
  });
}
