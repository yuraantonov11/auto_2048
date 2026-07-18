/// High-level states the bot can observe on the 2048 game surface.
///
/// The native solver/OCR pipeline is expensive and assumes the live game
/// board is in view. When the screen shows a modal (Game Over dialog,
/// "Try Again" prompt, interstitial ad, settings page) the OCR pipeline
/// produces garbage grids that cascade into corrupted solver logs and
/// crash-prone loop iterations. The FSM defined in this module gates the
/// expensive pipeline until the surface is verified as `playing`.
library;

enum GameState {
  /// The live 4x4 board is visible and the player can make moves.
  playing,

  /// The 2048 tile was just produced. The native 2048 app shows a "You
  /// Win!" modal and offers "Keep going" / "Restart" buttons. The bot
  /// must log the win, tap "Restart", and resume a fresh session.
  won2048,

  /// A Game Over / "Try Again" dialog has been detected. The board can
  /// no longer accept moves; the controller should tap restart and wait.
  gameOver,

  /// A non-game menu is open (main menu, settings, store tab, ...).
  menu,

  /// The pre-check could not classify the surface with enough confidence.
  /// The controller should pause the solver and retry after a delay -
  /// usually because the previous swipe animation is still playing.
  unknown,
}

/// Terminal states where the bot should stop, log the run, and tap the
/// in-game "Restart" button to begin a new session.
const Set<GameState> _restartableStates = {
  GameState.won2048,
  GameState.gameOver,
};

/// Immutable observation returned by [StateDetector].
///
/// The bot uses [confidence] to decide whether to trust the classification
/// or fall back to [GameState.unknown]. [reasons] is a list of human
/// readable signals so logs / UI can show *why* a decision was made.
class GameStateSnapshot {
  const GameStateSnapshot({
    required this.state,
    required this.confidence,
    required this.reasons,
    this.grid,
  }) : assert(confidence >= 0.0 && confidence <= 1.0);

  /// Construct a snapshot from a single high-signal heuristic, defaulting
  /// confidence to the provided [confidence] and copying [reason] verbatim.
  factory GameStateSnapshot.fromReason(
    GameState state,
    double confidence,
    String reason, {
    List<int>? grid,
  }) {
    return GameStateSnapshot(
      state: state,
      confidence: confidence,
      reasons: [reason],
      grid: grid,
    );
  }

  final GameState state;
  final double confidence;
  final List<String> reasons;
  final List<int>? grid;

  /// Whether the snapshot has enough certainty to act on.
  bool get isReliable => confidence >= 0.6;

  /// Whether the solver/OCR pipeline is allowed to run.
  bool get allowsSolver => state == GameState.playing && isReliable;

  /// Whether the snapshot represents a terminal game state.
  bool get isTerminal => _restartableStates.contains(state) && isReliable;

  @override
  String toString() =>
      'GameStateSnapshot($state, conf=${confidence.toStringAsFixed(2)}, '
      'reasons=$reasons)';
}

/// A screen-relative point in normalized coordinates ([0, 1]). The native
/// side converts to absolute pixels using `screenWidth/screenHeight`.
class NormalizedPoint {
  const NormalizedPoint(this.x, this.y);

  final double x;
  final double y;

  Map<String, double> toMap() => {'x': x, 'y': y};

  @override
  String toString() =>
      'NormalizedPoint(${x.toStringAsFixed(3)}, '
      '${y.toStringAsFixed(3)})';
}
