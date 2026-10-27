import 'package:flutter_test/flutter_test.dart';
import 'package:esp32_drone_controller/controllers/flight_controller.dart';
import 'package:esp32_drone_controller/services/drone_link.dart';

class FakeLink extends DroneLink {
  final sent = <Map<String, dynamic>>[];
  @override
  bool send(String type, [Map<String, dynamic> payload = const {}]) {
    if (!authenticated) return false;
    sent.add({'type': type, ...payload});
    return true;
  }
}

void main() {
  test('expo dead zone, limits, sign and nonfinite protection', () {
    expect(FlightController.shape(.04), 0);
    expect(FlightController.shape(-.04), 0);
    expect(FlightController.shape(9), 1);
    expect(FlightController.shape(-9), -1);
    expect(FlightController.shape(double.nan), 0);
    expect(FlightController.shape(.5), lessThan(.5));
    expect(FlightController.shape(-.5), -FlightController.shape(.5));
  });

  late FakeLink link;
  late FlightController flight;
  setUp(() {
    link = FakeLink()..session = 'test-session';
    flight = FlightController(link, startTimer: false);
    flight.receive({'type': 'telemetry', 'state': 'DISARMED'});
  });
  tearDown(() {
    flight.dispose();
    link.dispose();
  });

  test(
    'no packets before interaction; release retains throttle but stops control',
    () {
      flight.tick();
      expect(link.sent, isEmpty);
      flight.left(.5, .7, true);
      flight.right(-.4, .3, true);
      flight.tick();
      expect(link.sent.last['type'], 'control');
      expect(link.sent.last['throttle'], .7);
      expect(link.sent.last['yaw'], lessThan(0));
      expect(link.sent.last['roll'], lessThan(0));
      flight.releaseInputs();
      link.sent.clear();
      flight.tick();
      expect(flight.throttle, .7);
      expect(flight.yaw, 0);
      expect(flight.roll, 0);
      expect(link.sent.where((p) => p['type'] == 'control'), isEmpty);
    },
  );

  test(
    'background stops control packets and emergency resets latched throttle',
    () {
      flight.left(0, .9, true);
      flight.foreground = false;
      flight.tick();
      expect(link.sent, isEmpty);
      flight.stop();
      expect(link.sent.single['type'], 'estop');
      expect(flight.throttle, 0);
      expect(flight.leftHeld, false);
    },
  );

  test(
    'arm and calibration gates require fresh DISARMED and zero throttle',
    () {
      expect(flight.canArm, true);
      flight.left(0, .1, true);
      expect(flight.canArm, false);
      flight.receive({'type': 'telemetry', 'state': 'ACTIVE'});
      expect(flight.canConfigure, false);
      flight.receive({'type': 'telemetry', 'state': 'FAILSAFE'});
      expect(flight.canArm, false);
    },
  );

  test(
    'expired interaction pulse stops stale control even with a live socket',
    () async {
      flight.left(0, .5, true);
      await Future<void>.delayed(const Duration(milliseconds: 230));
      flight.tick();
      expect(link.sent.where((p) => p['type'] == 'control'), isEmpty);
    },
  );

  test('missing or invalid coordinates do not populate an offline track', () {
    flight.receive({
      'type': 'telemetry',
      'gps': {'lat': double.nan, 'lon': 20, 'fresh': true},
    });
    expect(flight.trail, isEmpty);
    flight.receive({
      'type': 'telemetry',
      'gps': {'lat': 10, 'lon': 20, 'fresh': false},
    });
    expect(flight.trail, isEmpty);
    flight.receive({
      'type': 'telemetry',
      'gps': {'lat': 10, 'lon': 20, 'fresh': true},
    });
    expect(flight.trail, [(10.0, 20.0)]);
  });
}
