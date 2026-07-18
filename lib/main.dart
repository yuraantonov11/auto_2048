import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'bot/game_state.dart';
import 'bot/native_bridge.dart';

void main() {
  runApp(const MyApp());
}

enum SolveMode { automatic, hints }

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.purple, brightness: Brightness.dark),
      ),
      home: const AutoBotScreen(),
    );
  }
}

class AutoBotScreen extends StatefulWidget {
  const AutoBotScreen({super.key});

  @override
  State<AutoBotScreen> createState() => _AutoBotScreenState();
}

class _AutoBotScreenState extends State<AutoBotScreen> with WidgetsBindingObserver {
  static const platform = MethodChannel('com.antonov.auto2048/gestures');
  static final MethodChannelBridge _bridge = MethodChannelBridge();

  bool? _serviceRunning;
  bool _botActive = false;
  bool _debugMode = false;
  SolveMode _solveMode = SolveMode.automatic;
  static const double _maxAutomaticSpeed = 1.0;
  double _automaticSpeed = 0.55;
  String _hintState = "IDLE";
  Timer? _solverStatusTimer;
  String _statusMessage = "";
  String _lastMove = "NONE";
  String _solverError = "";
  String _recognitionDiagnostics = "";
  String _screenState = "";
  int _wins = 0;
  int _losses = 0;
  Timer? _screenStateTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkServiceStatus();
    _syncSolverStatus();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkServiceStatus();
      _syncSolverStatus();
    } else if (state == AppLifecycleState.paused || state == AppLifecycleState.inactive) {
      _solverStatusTimer?.cancel();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _solverStatusTimer?.cancel();
    _screenStateTimer?.cancel();
    super.dispose();
  }

  Future<void> _checkServiceStatus() async {
    try {
      final running = await platform.invokeMethod<bool>('isServiceRunning');
      if (mounted) {
        setState(() {
          _serviceRunning = running;
          if (running == true) {
            _statusMessage = "";
          } else {
            _statusMessage = "Увімкніть сервіс доступності у налаштуваннях Android";
          }
        });
      }
    } catch (_) {}
  }

  Future<void> _syncSolverStatus() async {
    try {
      final status = await platform.invokeMethod<Map>('getSolverStatus');
      if (status == null || !mounted) return;

      final running = status['running'] == true;
      final automatic = status['automatic'] != false;
      setState(() {
        _botActive = running;
        _solveMode = automatic ? SolveMode.automatic : SolveMode.hints;
        _lastMove = status['lastMove'] as String? ?? "NONE";
        _solverError = status['lastError'] as String? ?? "";
        _automaticSpeed = (status['speed'] as num?)?.toDouble() ?? _automaticSpeed;
        _hintState = status['hintState'] as String? ?? "IDLE";
        _recognitionDiagnostics = status['recognitionDiagnostics'] as String? ?? "";
        _debugMode = status['debugMode'] == true;
      });

      if (running) {
        _startStatusPolling();
      } else {
        _solverStatusTimer?.cancel();
      }
    } catch (_) {}
    // Always refresh the screen-state badge so the UI shows the
    // current modal even when the bot is idle.
    _startScreenStatePolling();
  }

  void _startScreenStatePolling() {
    _screenStateTimer?.cancel();
    _screenStateTimer = Timer.periodic(
      const Duration(seconds: 3),
      (_) => _probeScreen(),
    );
    _probeScreen();
  }

  Future<void> _probeScreen() async {
    final snapshot = await _bridge.probeScreen();
    if (snapshot == null || !mounted) return;
    if (snapshot.state == GameState.playing ||
        snapshot.state == GameState.unknown ||
        snapshot.state == GameState.menu) {
      if (_screenState.isNotEmpty) {
        setState(() => _screenState = "");
      }
      return;
    }
    // Only update counters when we transition into a new terminal - a
    // repeated probe of the same state shouldn't double-count.
    final newState = snapshot.state.name;
    if (newState == _screenState) return;
    setState(() {
      _screenState = newState;
      if (snapshot.state == GameState.won2048) _wins += 1;
      if (snapshot.state == GameState.gameOver) _losses += 1;
    });
  }

  void _startStatusPolling() {
    if (_solverStatusTimer?.isActive == true) return;
    _solverStatusTimer = Timer.periodic(
      const Duration(milliseconds: 700),
      (_) => _syncSolverStatus(),
    );
  }

  Future<void> _openAccessibilitySettings() async {
    try {
      await platform.invokeMethod('openAccessibilitySettings');
    } catch (_) {}
  }

  Future<void> _startInGameCalibration() async {
    try {
      await platform.invokeMethod('toggleOverlay', {'show': true});
      await platform.invokeMethod('setCalibrationMode', {'enabled': true});
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(e.message ?? 'Не вдалося відкрити калібрування.')),
      );
      return;
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Перейдіть до 2048, вирівняйте рамку з полем і натисніть ЗБЕРЕГТИ.'),
          duration: Duration(seconds: 5),
        ),
      );
    }
  }

  Future<void> _setDebugMode(bool enabled) async {
    try {
      await platform.invokeMethod('setDebugMode', {'enabled': enabled});
      if (mounted) setState(() => _debugMode = enabled);
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() => _debugMode = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(e.message ?? 'Не вдалося увімкнути діагностику.')),
      );
    }
  }

  Future<void> _setAutomaticSpeed(double speed) async {
    setState(() => _automaticSpeed = speed);
    try {
      await platform.invokeMethod('setAutomaticSpeed', {'speed': speed});
    } catch (_) {}
  }

  Future<void> _toggleBot() async {
    if (_botActive) {
      try {
        await platform.invokeMethod('stopSolver');
      } finally {
        _solverStatusTimer?.cancel();
        if (mounted) {
          setState(() {
            _botActive = false;
            _hintState = "IDLE";
            _lastMove = "NONE";
            _solverError = "";
          });
        }
      }
      return;
    }

    try {
      await platform.invokeMethod('startSolver', {
        'automatic': _solveMode == SolveMode.automatic,
        'speed': _automaticSpeed,
      });
      if (mounted) {
        setState(() {
          _botActive = true;
          _hintState = _solveMode == SolveMode.hints ? "SCANNING" : "IDLE";
          _lastMove = "NONE";
          _solverError = "";
        });
      }
      _startStatusPolling();
    } on PlatformException catch (e) {
      if (!mounted) return;
      final message = switch (e.code) {
        'SERVICE_OFF' => 'Увімкніть сервіс доступності для автоматичного режиму.',
        'OVERLAY_REQUIRED' => 'Дозвольте показ поверх інших програм для підказок.',
        _ => e.message ?? 'Не вдалося запустити розв’язувач.',
      };
      setState(() {
        _botActive = false;
        _solverError = message;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF121212),
      appBar: AppBar(title: const Text('2048 EXPECTIMAX'), centerTitle: true),
      body: SingleChildScrollView(
        child: Column(
          children: [
            _buildStatusBanner(),
            const SizedBox(height: 20),
            _buildSolveModePanel(),
            if (_solveMode == SolveMode.automatic) ...[
              const SizedBox(height: 8),
              _buildAutomaticSpeedControl(),
            ],
            const SizedBox(height: 20),
            _buildControls(),
            _buildSolverStatus(),
            const Divider(color: Colors.white10, height: 40),
            _buildCalibrationAction(),
            const SizedBox(height: 12),
            _buildDiagnosticsPanel(),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusBanner() {
    final serviceActive = _serviceRunning == true;
    final hintsWithoutService = _solveMode == SolveMode.hints && !serviceActive;
    final color = serviceActive ? Colors.green : (hintsWithoutService ? Colors.blueAccent : Colors.red);
    final title = serviceActive
        ? "СЕРВІС АКТИВНИЙ"
        : (hintsWithoutService ? "СЕРВІС НЕ ПОТРІБЕН ДЛЯ ПІДКАЗОК" : "СЕРВІС ВИМКНЕНО");

    // Screen-state badge - tells the user at a glance whether the bot
    // sees the board, the win modal or the Game Over modal. Updated by
    // [_probeScreen] running every 3s.
    final screenBadge = switch (_screenState) {
      'won2048' => ('ПЕРЕМОГА 🎉', Colors.greenAccent),
      'gameOver' => ('GAME OVER', Colors.redAccent),
      'playing' => ('ГРА', Colors.lightGreenAccent),
      _ => ('ОЧІКУВАННЯ', Colors.white38),
    };

    return GestureDetector(
      onTap: !serviceActive && !hintsWithoutService ? _openAccessibilitySettings : null,
      child: Container(
        width: double.infinity,
        color: color.withValues(alpha: 0.15),
        padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 20),
        child: Column(
          children: [
            Text(
              title,
              textAlign: TextAlign.center,
              style: TextStyle(color: color, fontWeight: FontWeight.bold, fontSize: 16),
            ),
            if (serviceActive) ...[
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: screenBadge.$2.withValues(alpha: 0.20),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: screenBadge.$2, width: 1),
                ),
                child: Text(
                  '${screenBadge.$1}   перемоги: $_wins   поразки: $_losses',
                  style: TextStyle(color: screenBadge.$2, fontWeight: FontWeight.w600, fontSize: 12),
                ),
              ),
            ],
            if (!serviceActive && !hintsWithoutService) ...[
              const SizedBox(height: 8),
              Text(
                _statusMessage,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white54, fontSize: 12),
              ),
              const SizedBox(height: 6),
              const Text(
                "Натисніть, щоб відкрити налаштування",
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.blueAccent,
                  fontSize: 12,
                  decoration: TextDecoration.underline,
                ),
              ),
            ],
            if (hintsWithoutService) ...[
              const SizedBox(height: 8),
              const Text(
                "Expectimax лише показуватиме напрямок — свайп виконуєте ви.",
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white54, fontSize: 12),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildCalibrationAction() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: SizedBox(
        width: double.infinity,
        child: OutlinedButton.icon(
          onPressed: _botActive ? null : _startInGameCalibration,
          icon: const Icon(Icons.crop_free),
          label: const Text("КАЛІБРУВАТИ ПОЛЕ"),
        ),
      ),
    );
  }

  Widget _buildSolveModePanel() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Column(
        children: [
          const Text(
            "РЕЖИМ EXPECTIMAX",
            style: TextStyle(fontWeight: FontWeight.bold, color: Colors.purpleAccent),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: ChoiceChip(
                  label: const Text("АВТОМАТИЧНИЙ"),
                  selected: _solveMode == SolveMode.automatic,
                  onSelected: _botActive
                      ? null
                      : (_) => setState(() {
                            _solveMode = SolveMode.automatic;
                            _hintState = "IDLE";
                            _lastMove = "NONE";
                            _solverError = "";
                          }),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: ChoiceChip(
                  label: const Text("ПІДКАЗКИ"),
                  selected: _solveMode == SolveMode.hints,
                  onSelected: _botActive
                      ? null
                      : (_) => setState(() {
                            _solveMode = SolveMode.hints;
                            _hintState = "IDLE";
                            _lastMove = "NONE";
                            _solverError = "";
                          }),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            _solveMode == SolveMode.automatic
                ? "Бот сканує поле, обирає хід Expectimax і виконує свайп."
                : "Бот постійно сканує поле та показує стрілкою рекомендований хід.",
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white54, fontSize: 11),
          ),
        ],
      ),
    );
  }

  Widget _buildAutomaticSpeedControl() {
    final label = switch (_automaticSpeed) {
      < 0.34 => "ПОВІЛЬНО",
      < 0.67 => "ЗБАЛАНСОВАНО",
      < 0.9 => "ШВИДКО",
      _ => "МАКСИМУМ",
    };

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text("ШВИДКІСТЬ АВТОРЕЖИМУ", style: TextStyle(fontSize: 12, color: Colors.white70)),
              Text(label, style: const TextStyle(fontSize: 12, color: Colors.greenAccent)),
            ],
          ),
          Slider(
            value: _automaticSpeed,
            min: 0.0,
            max: _maxAutomaticSpeed,
            divisions: 20,
            onChanged: _setAutomaticSpeed,
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              TextButton.icon(
                style: TextButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 6)),
                onPressed: () => _setAutomaticSpeed(_maxAutomaticSpeed),
                icon: const Icon(Icons.bolt, size: 18, color: Colors.amberAccent),
                label: const Text("МАКСИМУМ", style: TextStyle(fontSize: 11, color: Colors.amberAccent)),
              ),
              Text(
                "${(_automaticSpeed * 100).round()} %",
                style: const TextStyle(fontSize: 12, color: Colors.white70),
              ),
              TextButton.icon(
                style: TextButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 6)),
                onPressed: () => _setAutomaticSpeed(0.55),
                icon: const Icon(Icons.balance, size: 18, color: Colors.lightBlueAccent),
                label: const Text("НОРМА", style: TextStyle(fontSize: 11, color: Colors.lightBlueAccent)),
              ),
            ],
          ),
          const Text(
            "Швидкість оновлюється на льоту; інтервали та траєкторії свайпів змінюються на кожному ході.",
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white38, fontSize: 10),
          ),
        ],
      ),
    );
  }

  Widget _buildControls() {
    final serviceReady = _serviceRunning == true;
    final serviceRequired = _solveMode == SolveMode.automatic;
    final canStart = !serviceRequired || serviceReady;
    final startLabel = _solveMode == SolveMode.automatic ? "ЗАПУСТИТИ БОТА" : "ЗАПУСТИТИ ПІДКАЗКИ";

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 40),
      child: ElevatedButton(
        onPressed: _botActive || canStart ? _toggleBot : null,
        style: ElevatedButton.styleFrom(
          backgroundColor: _botActive ? Colors.redAccent : (canStart ? Colors.greenAccent : Colors.grey),
          foregroundColor: Colors.black,
          minimumSize: const Size(double.infinity, 60),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(30)),
        ),
        child: Text(_botActive ? "ЗУПИНИТИ" : (canStart ? startLabel : "СЕРВІС НЕ АКТИВНИЙ")),
      ),
    );
  }

  Widget _buildSolverStatus() {
    if (!_botActive && _lastMove == "NONE" && _solverError.isEmpty) {
      return const SizedBox.shrink();
    }

    final direction = switch (_lastMove) {
      "UP" => "ВГОРУ",
      "DOWN" => "ВНИЗ",
      "LEFT" => "ВЛІВО",
      "RIGHT" => "ВПРАВО",
      _ => "ОЧІКУВАННЯ ПОЛЯ",
    };
    final isHintMode = _solveMode == SolveMode.hints;
    final statusText = isHintMode
        ? switch (_hintState) {
            "SCANNING" => "Expectimax: сканування поля...",
            "READY_NEW" => "НОВИЙ ХІД: $direction",
            "READY" => "ПІДТВЕРДЖЕНО: $direction",
            _ => "Expectimax: очікування поля",
          }
        : "Expectimax: $direction";
    final statusColor = switch (_hintState) {
      "SCANNING" => Colors.orangeAccent,
      "READY_NEW" => Colors.greenAccent,
      _ => Colors.cyanAccent,
    };

    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 14, 24, 0),
      child: Column(
        children: [
          if (_botActive)
            Text(
              statusText,
              style: TextStyle(color: statusColor, fontWeight: FontWeight.bold),
            ),
          if (_solverError.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              _solverError,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.orangeAccent, fontSize: 12),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildDiagnosticsPanel() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: ExpansionTile(
        title: const Text("ДІАГНОСТИКА", style: TextStyle(fontSize: 13)),
        children: [
          SwitchListTile(
            title: const Text("Режим налагодження"),
            subtitle: const Text("Показує розпізнані числа, точки та сумнівні клітинки поверх гри."),
            value: _debugMode,
            onChanged: _setDebugMode,
          ),
          if (_recognitionDiagnostics.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
              child: Text(
                _recognitionDiagnostics,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white54, fontSize: 11),
              ),
            ),
        ],
      ),
    );
  }
}
