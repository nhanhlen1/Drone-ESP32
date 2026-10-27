import 'package:flutter_test/flutter_test.dart';
import 'package:esp32_drone_controller/main.dart';
import 'package:flutter/material.dart';

void main() {
  testWidgets('disconnected console shows unknown readings and locks arm', (
    tester,
  ) async {
    await tester.pumpWidget(const DroneApp());
    expect(find.text('DRONE /'), findsOneWidget);
    expect(find.text('MOCK / LOCKED'), findsOneWidget);
    expect(find.text('ARM'), findsOneWidget);
    final arm = tester.widget<FilledButton>(
      find.ancestor(
        of: find.text('ARM'),
        matching: find.byWidgetPredicate((w) => w is FilledButton),
      ),
    );
    expect(arm.onPressed, isNull);
    await tester.pumpWidget(const SizedBox());
  });
}
