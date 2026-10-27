import 'dart:async';
import 'package:esp32_drone_controller/controllers/flight_controller.dart';
import 'package:esp32_drone_controller/services/drone_link.dart';
import 'package:esp32_drone_controller/screens/console.dart';
import 'package:esp32_drone_controller/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets(
    'focused connection dialog survives cancel and route transition',
    (tester) async {
      tester.view.physicalSize = const Size(800, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(const DroneApp());
      for (var attempt = 0; attempt < 3; attempt++) {
        await tester.tap(find.text('Connect'));
        await tester.pumpAndSettle();
        final fields = find.descendant(
          of: find.byType(AlertDialog),
          matching: find.byType(TextField),
        );
        await tester.tap(fields.last);
        await tester.enterText(fields.last, 'test-session-token');
        await tester.tap(find.widgetWithText(TextButton, 'Cancel'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 80));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      }
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('phone keyboard, back, and barrier dismissal keep inputs alive', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    await tester.pumpWidget(const DroneApp());
    for (final back in [true, false]) {
      await tester.tap(find.text('Connect'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('connection-token')),
        'test-token',
      );
      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      await tester.pumpAndSettle();
      if (back) {
        await tester.binding.handlePopRoute();
      } else {
        await tester.tapAt(const Offset(5, 5));
      }
      await tester.pump();
      tester.view.resetViewInsets();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets(
    'connection result after Console unmount does not open a socket',
    (tester) async {
      final link = _TrackingLink();
      final flight = FlightController(link, startTimer: false);
      final visible = ValueNotifier(true);
      await tester.pumpWidget(
        MaterialApp(
          home: ValueListenableBuilder<bool>(
            valueListenable: visible,
            builder: (_, show, child) =>
                show ? Console(flight: flight) : const SizedBox(),
          ),
        ),
      );
      await tester.tap(find.text('Connect'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('connection-token')),
        'test-token',
      );
      visible.value = false;
      await tester.pump();
      await tester.tap(find.widgetWithText(FilledButton, 'Connect'));
      await tester.pumpAndSettle();
      expect(link.connections, isEmpty);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      flight.dispose();
      link.dispose();
      visible.dispose();
    },
  );

  testWidgets('pending connection failure after unmount does not use context', (
    tester,
  ) async {
    final pending = Completer<void>();
    final link = _TrackingLink()..whenConnecting = () => pending.future;
    final flight = FlightController(link, startTimer: false);
    await tester.pumpWidget(MaterialApp(home: Console(flight: flight)));
    await tester.tap(find.text('Connect'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('connection-token')),
      'test-token',
    );
    await tester.tap(find.widgetWithText(FilledButton, 'Connect'));
    await tester.pumpAndSettle();
    expect(link.connections, ['ws://192.168.4.1:81/ws']);
    await tester.pumpWidget(const SizedBox());
    pending.completeError(StateError('test connection failure'));
    await tester.pump();
    expect(tester.takeException(), isNull);
    flight.dispose();
    link.dispose();
  });

  testWidgets('ARM confirmation cannot act on a removed Console', (
    tester,
  ) async {
    final link = _TrackingLink()..session = 'test';
    final flight = FlightController(link, startTimer: false);
    final visible = ValueNotifier(true);
    flight.receive({'type': 'telemetry', 'state': 'DISARMED'});
    await tester.pumpWidget(
      MaterialApp(
        home: ValueListenableBuilder<bool>(
          valueListenable: visible,
          builder: (_, show, child) =>
              show ? Console(flight: flight) : const SizedBox(),
        ),
      ),
    );
    await tester.tap(find.text('ARM'));
    await tester.pumpAndSettle();
    visible.value = false;
    await tester.pump();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();
    expect(link.commands, isNot(contains('arm')));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    flight.dispose();
    link.dispose();
    visible.dispose();
  });

  testWidgets('PID confirmation cannot send after editor disposal', (
    tester,
  ) async {
    final confirmation = Completer<bool>();
    final sent = <Map<String, dynamic>>[];
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: PidEditor(
              enabled: true,
              confirm: (_, message) => confirmation.future,
              send: sent.add,
            ),
          ),
        ),
      ),
    );
    final fields = find.byType(TextFormField);
    for (var i = 0; i < 12; i++) {
      await tester.ensureVisible(fields.at(i));
      await tester.enterText(fields.at(i), '0.1');
    }
    await tester.ensureVisible(find.text('Review & apply'));
    await tester.tap(find.text('Review & apply'));
    await tester.pump();
    await tester.pumpWidget(const SizedBox());
    confirmation.complete(true);
    await tester.pump();
    expect(sent, isEmpty);
    expect(tester.takeException(), isNull);
  });
}

class _TrackingLink extends DroneLink {
  final connections = <String>[];
  final commands = <String>[];
  Future<void> Function()? whenConnecting;

  @override
  Future<void> connect(String endpoint, String token) async {
    connections.add(endpoint);
    await whenConnecting?.call();
  }

  @override
  bool send(String type, [Map<String, dynamic> payload = const {}]) {
    commands.add(type);
    return true;
  }
}
