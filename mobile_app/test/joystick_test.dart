import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:esp32_drone_controller/widgets/flight_widgets.dart';

void main() {
  testWidgets(
    'independent pointer capture supports simultaneous sticks and cancellation',
    (tester) async {
      final left = <(double, double, bool)>[];
      final right = <(double, double, bool)>[];
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SizedBox(
              height: 250,
              child: Row(
                children: [
                  Expanded(
                    child: FlightStick(
                      key: const Key('left'),
                      enabled: true,
                      throttle: true,
                      onUpdate: (x, y, held) => left.add((x, y, held)),
                    ),
                  ),
                  Expanded(
                    child: FlightStick(
                      key: const Key('right'),
                      enabled: true,
                      onUpdate: (x, y, held) => right.add((x, y, held)),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
      final l = await tester.startGesture(
        tester.getCenter(find.byKey(const Key('left'))) + const Offset(30, -30),
        pointer: 1,
      );
      final r = await tester.startGesture(
        tester.getCenter(find.byKey(const Key('right'))) +
            const Offset(-30, 30),
        pointer: 2,
      );
      expect(
        left.last.$2,
        0,
        reason: 'Touching above center must not jump throttle',
      );
      await l.moveBy(const Offset(0, -120));
      await tester.pump(const Duration(milliseconds: 60));
      expect(left.last.$3, true);
      expect(right.last.$3, true);
      expect(left.last.$2, greaterThan(.5));
      expect(right.last.$1, lessThan(0));
      await l.up();
      expect(left.last.$3, false);
      expect(right.last.$3, true);
      await r.cancel();
      expect(right.last.$3, false);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
