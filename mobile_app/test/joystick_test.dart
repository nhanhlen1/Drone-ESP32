import 'package:flutter/material.dart';
import 'package:esp32_drone_controller/controllers/flight_controller.dart';
import 'package:esp32_drone_controller/services/drone_link.dart';
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

  testWidgets(
    'held stick releases after disable and accepts a new pointer after re-enable',
    (tester) async {
      final enabled = ValueNotifier(true);
      final link = DroneLink();
      final flight = FlightController(link, startTimer: false);
      final updates = <bool>[];
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SizedBox(
              height: 250,
              child: ValueListenableBuilder<bool>(
                valueListenable: enabled,
                builder: (context, active, child) => FlightStick(
                  key: const Key('lifecycle-stick'),
                  enabled: active,
                  onUpdate: (x, y, held) {
                    updates.add(held);
                    flight.right(x, y, held);
                  },
                ),
              ),
            ),
          ),
        ),
      );
      final oldPointer = await tester.startGesture(
        tester.getCenter(find.byKey(const Key('lifecycle-stick'))) +
            const Offset(30, 0),
        pointer: 11,
      );
      await tester.pump(const Duration(milliseconds: 60));
      expect(flight.rightHeld, true);
      enabled.value = false;
      await tester.pump();
      expect(flight.rightHeld, false);
      expect(flight.roll, 0);
      final updateCount = updates.length;
      await tester.pump(const Duration(milliseconds: 300));
      expect(
        updates.length,
        updateCount,
        reason: 'disabled stick must stop its hold timer',
      );
      await oldPointer.up();
      enabled.value = true;
      await tester.pump();
      final newPointer = await tester.startGesture(
        tester.getCenter(find.byKey(const Key('lifecycle-stick'))),
        pointer: 12,
      );
      expect(flight.rightHeld, true);
      await newPointer.cancel();
      expect(flight.rightHeld, false);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      flight.dispose();
      link.dispose();
      enabled.dispose();
    },
  );

  testWidgets(
    'unmounting a held stick cancels pulses before controller disposal',
    (tester) async {
      final link = DroneLink();
      final flight = FlightController(link);
      var updates = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SizedBox(
              height: 250,
              child: FlightStick(
                key: const Key('teardown-stick'),
                enabled: true,
                onUpdate: (x, y, held) {
                  updates++;
                  flight.right(x, y, held);
                },
              ),
            ),
          ),
        ),
      );
      final pointer = await tester.startGesture(
        tester.getCenter(find.byKey(const Key('teardown-stick'))),
      );
      await tester.pump(const Duration(milliseconds: 60));
      expect(updates, greaterThan(0));
      await tester.pumpWidget(const SizedBox());
      flight.dispose();
      link.dispose();
      final updateCount = updates;
      await tester.pump(const Duration(milliseconds: 600));
      await pointer.cancel();
      expect(updates, updateCount);
      expect(tester.takeException(), isNull);
    },
  );
}
