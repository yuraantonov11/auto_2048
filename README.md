# auto_2048 🤖🎮

Android Flutter-б表明 що грає в 2048 автоматично, використовуючи computer vision та AI solver.

## Як це працює

1. **Vision** — захоплює скріншот екрану через MediaProjection API
2. **Board Detection** — знаходить координати ігрового поля (4x4 сітка) на екрані
3. **OCR** — зчитує значення кожної плитки (2, 4, 8, ... 2048)
4. **Solver** — запускає expectimax algorithm для знаходження оптимального ходу
5. **Control** — виконує свайп через AccessibilityService / ADB

## Архітектура

```
lib/                    # Flutter UI (Dart)
  main.dart             # Головний екран з кнопками керування

android/app/src/main/kotlin/.../
  MainActivity.kt       # Точка входу, bringup
  VisionProcessor.kt    # Board detection + OCR (Kotlin)
  ScreenProbe.kt        # Стан гри (Playing/Won/GameOver)
  OverlayService.kt     # Debug overlay (рамка + точки)
  Solver.kt             # Expectimax solver
  Swiper.kt             # ADB swipe execution
```

## Debug режим

Увімкніть "ДІАГНОСТИКА" → "Режим відладки" на головному екрані, щоб:
- Бачити зелену рамку ігрового поля
- Бачити розпізнані координати клітинок
- Спостерігати debug-логи в Logcat

## Калібрування

Кнопка "КАЛІБРУВАТИ ПОЛЕ" — зберігає координати поля в SharedPreferences для подальшого використання.
