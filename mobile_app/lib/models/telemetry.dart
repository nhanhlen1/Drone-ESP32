/// Telemetry is never fabricated; absent readings stay null on the dashboard.
class Telemetry {
  final Map<String, dynamic> raw;
  final DateTime received;
  Telemetry(this.raw) : received = DateTime.now();

  dynamic at(String path) {
    dynamic value = raw;
    for (final key in path.split('.')) {
      if (value is! Map) return null;
      value = value[key];
    }
    return value;
  }

  double? number(String path) {
    final value = at(path);
    return value is num && value.isFinite ? value.toDouble() : null;
  }

  String get state => at('state')?.toString() ?? 'UNKNOWN';
  bool get disarmed => state == 'DISARMED';
  bool get armed => state == 'ARMED_IDLE' || state == 'ACTIVE';
  bool get fresh => DateTime.now().difference(received).inMilliseconds < 600;
  double? get roll => number('attitude.roll');
  double? get pitch => number('attitude.pitch');
  double? get yaw => number('attitude.yaw');
  double? get lat => number('gps.lat');
  double? get lon => number('gps.lon');
}
