// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';

import 'package:auto_2048/main.dart';

void main() {
  testWidgets('shows automatic and hint-only Expectimax modes', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());
    await tester.pump();

    expect(find.text('РЕЖИМ EXPECTIMAX'), findsOneWidget);
    expect(find.text('АВТОМАТИЧНИЙ'), findsOneWidget);
    expect(find.text('ПІДКАЗКИ'), findsOneWidget);
    expect(find.text('СЕРВІС НЕ АКТИВНИЙ'), findsOneWidget);
    expect(find.text('ШВИДКІСТЬ АВТОРЕЖИМУ'), findsOneWidget);
    expect(find.text('КАЛІБРУВАТИ ПОЛЕ'), findsOneWidget);
    expect(find.text('ДІАГНОСТИКА'), findsOneWidget);
    expect(find.text('Зверху (Y1)'), findsNothing);
    expect(find.text('Показувати рамку'), findsNothing);

    await tester.ensureVisible(find.text('ПІДКАЗКИ'));
    await tester.tap(find.text('ПІДКАЗКИ'));
    await tester.pump();

    expect(find.text('ЗАПУСТИТИ ПІДКАЗКИ'), findsOneWidget);
    expect(find.text('СЕРВІС НЕ ПОТРІБЕН ДЛЯ ПІДКАЗОК'), findsOneWidget);
    expect(find.text('ШВИДКІСТЬ АВТОРЕЖИМУ'), findsNothing);
  });
}
