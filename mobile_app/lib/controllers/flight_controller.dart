import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import '../models/telemetry.dart';
import '../services/drone_link.dart';

class FlightController extends ChangeNotifier {
  final DroneLink link;
  Telemetry? telemetry;
  Timer? _timer;
  double throttle = 0, yaw = 0, roll = 0, pitch = 0;
  double expo = .3;
  String mode = 'rate';
  bool leftHeld = false, rightHeld = false, foreground = true;
  final List<(double, double)> trail = [];
  int _ticks = 0;
  DateTime? _leftEvent, _rightEvent;

  FlightController(this.link, {bool startTimer = true}) {
    link.onMessage = receive;
    link.addListener(_linkChanged);
    if (startTimer) {
      _timer = Timer.periodic(const Duration(milliseconds: 50), (_) => tick());
    }
  }

  static double shape(double value, {double expo = .3, double deadZone = .06}) {
    if (!value.isFinite) return 0;
    final x = value.clamp(-1.0, 1.0);
    if (x.abs() <= deadZone) return 0;
    final normalized = (x.abs() - deadZone) / (1 - deadZone) * x.sign;
    return (1 - expo) * normalized + expo * math.pow(normalized, 3);
  }

  bool get usable => link.authenticated && (telemetry?.fresh ?? false);
  bool get canConfigure => usable && (telemetry?.disarmed ?? false);
  bool get canArm => canConfigure && throttle <= .001;

  void receive(Map<String, dynamic> packet) {
    if (packet['type'] == 'telemetry') {
      telemetry = Telemetry(packet);
      final t = telemetry!;
      if (t.lat != null && t.lon != null && t.at('gps.fresh') == true) {
        if (trail.isEmpty || trail.last != (t.lat!, t.lon!)) {
          trail.add((t.lat!, t.lon!));
        }
        if (trail.length > 1000) trail.removeAt(0);
      }
      notifyListeners();
    }
  }

  void left(double x, double verticalThrottle, bool held) {
    if (!foreground && held) return;
    // A rightward stick requests a right turn: negative yaw in FLU.
    yaw = held ? -shape(x, expo: expo) : 0;
    if (held) throttle = verticalThrottle.clamp(0, 1);
    leftHeld = held;
    _leftEvent = held ? DateTime.now() : null;
    notifyListeners();
  }

  void right(double x, double y, bool held) {
    if (!foreground && held) return;
    roll = held ? shape(x, expo: expo) : 0;
    pitch = held ? shape(y, expo: expo) : 0;
    rightHeld = held;
    _rightEvent = held ? DateTime.now() : null;
    notifyListeners();
  }

  void releaseInputs({bool resetThrottle = false}) {
    leftHeld = rightHeld = false;
    yaw = roll = pitch = 0;
    if (resetThrottle) throttle = 0;
    notifyListeners();
  }

  /// Widget hold pulses expire if a route disappears or OS stops pointer delivery.
  void tick() {
    _ticks++;
    final now = DateTime.now();
    final leftFresh =
        leftHeld &&
        _leftEvent != null &&
        now.difference(_leftEvent!).inMilliseconds < 200;
    final rightFresh =
        rightHeld &&
        _rightEvent != null &&
        now.difference(_rightEvent!).inMilliseconds < 200;
    if (foreground && usable && (leftFresh || rightFresh)) {
      link.send('control', {
        'throttle': throttle,
        'yaw': leftFresh ? yaw : 0,
        'roll': rightFresh ? roll : 0,
        'pitch': rightFresh ? pitch : 0,
        'mode': mode,
      });
    }
    if (foreground && link.authenticated && _ticks % 4 == 0) {
      link.send('heartbeat');
    }
    if (_ticks % 10 == 0) notifyListeners();
  }

  void stop() {
    link.send('estop');
    releaseInputs(resetThrottle: true);
  }

  void disarm() {
    link.send('disarm');
    releaseInputs(resetThrottle: true);
  }

  void _linkChanged() {
    if (!link.authenticated) {
      leftHeld = rightHeld = false;
      throttle = yaw = roll = pitch = 0;
      telemetry = null;
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _timer?.cancel();
    link.removeListener(_linkChanged);
    link.onMessage = null;
    super.dispose();
  }
}
