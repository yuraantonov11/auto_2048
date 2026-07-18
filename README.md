# auto_2048 🤖🎮

Android-застосунок на Flutter, який автоматично грає в 2048, використовуючи
комп'ютерний зір та Expectimax-солвер. Працює як оверлей над реальним
застосунком 2048: захоплює екран, розпізнає плитки, обирає оптимальний хід
і виконує свайп через AccessibilityService.

## Зміст

- [Як це працює](#як-це-працює)
- [Архітектура](#архітектура)
- [Вимоги](#вимоги)
- [Збірка та запуск](#збірка-та-запуск)
- [Калібрування](#калібрування)
- [Діагностика](#діагностика)
- [Налагодження](#налагодження)
- [Тестування](#тестування)
- [Структура проєкту](#структура-проєкту)
- [Відомі обмеження](#відомі-обмеження)

## Як це працює

1. **Vision** — `MediaProjectionService` + `VisionProcessor.kt` роблять
   скріншот екрану.
2. **Board Detection** — знаходить межі 4×4 ігрового поля (калібрування
   зберігається у `SharedPreferences`).
3. **OCR** — зчитує значення кожної з 16 плиток (2, 4, 8, …, 2048).
4. **State Detector** — дешева перевірка стану екрану
   (Playing / Won / Game Over / Menu) перед запуском важкого конвеєра.
5. **Solver** — Expectimax-алгоритм (`GameSolver.kt`) обирає найкращий
   напрямок із тайм-бюджетом 1.5 с.
6. **Control** — `Auto2048Service` (AccessibilityService) виконує
   свайп через `GestureDescription`.

Коли солвер виявляє термінальний стан (Won або Game Over), контролер
автоматично натискає «Try again» / «Restart» і починає нову сесію.

## Архітектура

```text
lib/                                    # Flutter UI (Dart)
  main.dart                             # Головний екран із кнопками
  bot/
    bot_config.dart                     # Центральні налаштування (час, глибина, координати)
    expectimax_solver.dart              # Pure-Dart Expectimax (тести / web)
    heuristics.dart                     # Оцінкова функція (snake + corner penalty)
    native_bridge.dart                  # MethodChannel → Kotlin
    state_detector.dart                 # FSM: playing / won / gameOver / menu
    game_state.dart                     # Sealed-типи + NormalizedPoint
    exceptions.dart                     # Sealed BotException hierarchy

android/app/src/main/kotlin/.../        # Native-сторона (Kotlin)
  MainActivity.kt                       # MethodChannel + MediaProjection
  VisionProcessor.kt                    # Board detect + OCR
  ScreenProbe.kt                        # Класифікація стану екрану
  GameSolver.kt                         # Продакшн Expectimax (depth=7, 1.5 с)
  GameConfig.kt                         # Магічні числа (кольори плиток, затримки)
  GridCoordinates.kt                    # Маппінг пікселів → клітинки
  SamplePointsStore.kt                  # Збереження калібрування
  Auto2048Service.kt                    # AccessibilityService для свайпів
  MediaProjectionService.kt             # Foreground для скрінкаптів
  OverlayService.kt                     # Debug-накладка (зелена рамка + точки)

test/                                   # Dart unit-тести
android/app/src/test/kotlin/.../        # Kotlin unit-тести (GameSolver)
.github/workflows/ci.yml                # CI: flutter analyze + test, gradle test, apk build
```

## Вимоги

- **Flutter SDK** ≥ 3.24 (тестується на `3.24.5`).
- **Android SDK** 34, build-tools 34.0.0, **JDK 17**.
- **Пристрій або емулятор** з Android 8.0+ (API 26+).
- Дозволи на пристрої:
  - **Accessibility Service** — для виконання свайпів.
  - **MediaProjection** — для захоплення екрану.
  - **Display over other apps** (`SYSTEM_ALERT_WINDOW`) — для
    debug-накладки.

## Збірка та запуск

```bash
# 1. Встановити залежності
flutter pub get

# 2. Увімкнути Accessibility Service для auto_2048
#    Settings → Accessibility → auto_2048 → On

# 3. Запустити на під'єднаному пристрої
flutter run -d <device-id>

# 4. Надати дозвіл MediaProjection при першому запуску
```

### Збірка релізного APK

```bash
flutter build apk --release
# або split per ABI:
flutter build apk --release --split-per-abi
```

## Калібрування

Кнопка **«КАЛІБРУВАТИ ПОЛЕ»** зберігає координати 4×4 сітки у
`SharedPreferences`. Після калібрування:

- `VisionProcessor` обмежує пошук поля збереженими межами
  (≈ у 5–10 разів швидше).
- Якщо поле не знайдено у межах, вмикається повний скан.

Якщо кольори плиток на вашому пристрої відрізняються — перевірте
`GameConfig.TILE_COLORS` та `COLOR_TOLERANCE`.

## Діагностика

Увімкніть **«ДІАГНОСТИКА» → «Режим відладки»**, щоб:

- Бачити зелену рамку ігрового поля (overlay).
- Бачити розпізнані координати клітинок (sample points).
- Спостерігати debug-логи в Logcat (`adb logcat | grep Auto2048`).

Типові проблеми:

| Симптом                              | Причина / рішення                                      |
| ------------------------------------ | ------------------------------------------------------ |
| Бот робить хаотичні ходи             | Не відкалібровано поле або кольори плиток не збігаються |
| `Log.e: MediaProjection FGS rejected`| Токен проекції відкликано — перезапустіть дозвіл       |
| `ACCESSIBILITY_SERVICE` не ввімкнено | Увімкніть у Settings → Accessibility                   |
| Бот зависає на «Game Over»           | Перевірте координати Restart у `bot_config.dart`       |

## Налагодження

```bash
# Логи в реальному часі
adb logcat -s Auto2048:V Auto2048Service:V GameSolver:V

# Тестовий запуск Dart-солвера в ізоляті
flutter test test/expectimax_solver_test.dart
flutter test test/heuristics_test.dart
flutter test test/state_detector_test.dart

# Тести Kotlin-солвера
cd android && ./gradlew :app:testDebugUnitTest
```

## Тестування

- **Dart (`flutter test`)** — `heuristics_test`, `expectimax_solver_test`,
  `state_detector_test`, `native_bridge_solve_result_test`.
- **Kotlin (`./gradlew :app:testDebugUnitTest`)** —
  `GameSolverTerminalTest`, `GameSolverUnknownCellTest`.

CI запускає обидві сторони на кожен push/PR (див. `.github/workflows/ci.yml`).

## Структура проєкту

| Каталог                                    | Призначення                                  |
| ------------------------------------------ | -------------------------------------------- |
| `lib/bot/`                                 | Чиста бізнес-логіка (тестується ізольовано)  |
| `lib/`                                     | UI + DI-збирання `StateDetector` / контролера |
| `android/app/src/main/kotlin/.../`         | Vision / Solver / AccessibilityService        |
| `android/app/src/test/kotlin/.../`         | Юніт-тести Kotlin-солвера                    |
| `test/`                                    | Юніт-тести Dart-солвера                      |
| `.github/workflows/`                       | CI                                           |

## Відомі обмеження

- Працює лише на Android (використовує `MediaProjection` + Accessibility).
- Розрахований на 4×4 поле стандартного 2048. Інші розміри не підтримуються.
- Кольори плиток залежать від конкретного застосунку 2048. Якщо використовуєте
  нестандартну тему — оновіть `GameConfig.TILE_COLORS`.
- Expectimax із глибиною 7 + бюджет 1.5 с може не встигати на старих
  пристроях. Зменшіть `MAX_DEPTH` у `GameConfig.kt` за потреби.