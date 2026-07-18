/// Typed exception hierarchy for the 2048 bot.
///
/// The bot throws [BotException] subclasses for every error condition a
/// caller might reasonably want to recover from. Plain [ArgumentError]
/// is reserved for *programming* errors (the wrong argument type for
/// a Dart built-in).
///
/// The hierarchy is sealed so call sites can pattern-match exhaustively:
///
/// ```dart
/// try {
///   final move = await decideMoveDart(board);
/// } on TerminalBoardException {
///   // Game is over, restart.
/// } on InvalidBoardException catch (e) {
///   // OCR returned garbage, log and skip.
/// }
/// ```
library;

/// Base class for every error this package raises. Sealed so callers
/// can pattern-match exhaustively.
sealed class BotException implements Exception {
  const BotException(this.message, {this.cause});

  /// Human-readable description of the failure. Never empty.
  final String message;

  /// Underlying cause when this exception wraps another. `null` when the
  /// exception was raised directly.
  final Object? cause;

  @override
  String toString() {
    final base = '$runtimeType($message)';
    if (cause == null) return base;
    return '$base [cause: $cause]';
  }
}

/// The supplied board is malformed: wrong length, contains negative
/// values, or holds tiles that 2048 cannot legally produce.
final class InvalidBoardException extends BotException {
  const InvalidBoardException(super.message, {super.cause});
}

/// The caller asked for a direction that is not one of
/// `UP` / `DOWN` / `LEFT` / `RIGHT`.
final class IllegalDirectionException extends BotException {
  const IllegalDirectionException(super.message, {super.cause});
}

/// The native bridge is not reachable (accessibility service disabled,
/// overlay permission missing, method not implemented on this build).
///
/// The bot uses this to switch into the pure-Dart solver or to surface
/// a "service required" UI hint.
final class ProbeUnavailableException extends BotException {
  const ProbeUnavailableException(super.message, {super.cause});
}

/// The board has no legal moves (full and no adjacent equals). Distinct
/// from [InvalidBoardException] because it is a *terminal*, not a
/// *malformed*, state.
final class TerminalBoardException extends BotException {
  const TerminalBoardException(super.message, {super.cause});
}

/// The wall-clock budget for a solver step elapsed before the search
/// completed. The solver returns the best move it had so far.
final class SolverTimeoutException extends BotException {
  const SolverTimeoutException(super.message, {super.cause});
}