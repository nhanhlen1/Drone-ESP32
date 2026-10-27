import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../controllers/flight_controller.dart';
import '../models/telemetry.dart';
import '../widgets/flight_widgets.dart';
import '../widgets/connection_dialog.dart';

class Console extends StatefulWidget {
  final FlightController flight;
  const Console({super.key, required this.flight});
  @override
  State<Console> createState() => _ConsoleState();
}

class _ConsoleState extends State<Console> {
  int page = 0;
  bool _dialogOpen = false;
  FlightController get f => widget.flight;
  Telemetry? get t => f.telemetry;
  String reading(String field, [String unit = '', int decimals = 1]) {
    final n = t?.number(field);
    return n == null ? '—' : '${n.toStringAsFixed(decimals)}$unit';
  }

  Future<bool> confirm(String title, String text) async {
    if (!mounted || _dialogOpen) return false;
    _dialogOpen = true;
    final flight = f;
    flight.releaseInputs();
    try {
      final result = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(title),
          content: Text(text),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('Confirm'),
            ),
          ],
        ),
      );
      return mounted && identical(widget.flight, flight) && result == true;
    } finally {
      _dialogOpen = false;
    }
  }

  Future<void> connection() async {
    if (!mounted || _dialogOpen) return;
    _dialogOpen = true;
    final flight = f;
    flight.releaseInputs();
    ConnectionDetails? details;
    try {
      details = await showDialog<ConnectionDetails>(
        context: context,
        builder: (_) => const ConnectionDialog(),
      );
    } finally {
      _dialogOpen = false;
    }
    if (!mounted || !identical(widget.flight, flight) || details == null) {
      return;
    }
    try {
      await flight.link.connect(details.endpoint, details.token);
    } catch (_) {
      if (mounted && identical(widget.flight, flight)) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Connection failed. Check AP, address and firmware serial diagnostics.',
            ),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: f,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: const FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.flight, color: Color(0xFF62E6C4)),
              SizedBox(width: 10),
              Text('DRONE /', style: TextStyle(letterSpacing: 3, fontSize: 18)),
              Text(
                ' BENCH',
                style: TextStyle(
                  fontSize: 11,
                  color: Colors.white38,
                  letterSpacing: 2,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton.icon(
            onPressed: f.link.authenticated
                ? () => f.link.disconnect()
                : connection,
            icon: Icon(f.usable ? Icons.wifi : Icons.wifi_off, size: 18),
            label: Text(f.link.authenticated ? 'Disconnect' : 'Connect'),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
              child: Row(
                children: [
                  Container(
                    width: 7,
                    height: 7,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: f.usable ? const Color(0xFF62E6C4) : Colors.orange,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '${f.link.status} · ${t?.state ?? 'UNKNOWN'}',
                      style: const TextStyle(
                        fontSize: 12,
                        color: Colors.white70,
                      ),
                    ),
                  ),
                  if (t != null && !t!.fresh)
                    const Text('STALE', style: TextStyle(color: Colors.orange)),
                  const SizedBox(width: 12),
                  Text(
                    t?.at('output_mock') == false
                        ? 'REAL OUTPUT'
                        : 'MOCK / LOCKED',
                    style: TextStyle(
                      fontSize: 11,
                      color: t?.at('output_mock') == false
                          ? Colors.redAccent
                          : Colors.amber,
                    ),
                  ),
                ],
              ),
            ),
            if (t?.at('failsafe') != null &&
                t!.at('failsafe') != false &&
                t!.at('failsafe') != '')
              Container(
                width: double.infinity,
                color: Colors.red.withValues(alpha: .2),
                padding: const EdgeInsets.all(10),
                child: Text(
                  'Failsafe: ${t!.at('failsafe')}',
                  style: const TextStyle(color: Colors.redAccent),
                ),
              ),
            Expanded(
              child: IndexedStack(
                index: page,
                children: [dashboard(), settings(), diagnostics()],
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: f.link.authenticated ? f.disarm : null,
                      icon: const Icon(Icons.lock_outline),
                      label: const Text('DISARM'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: f.canArm
                          ? () async {
                              if (await confirm(
                                    'Confirm ARM',
                                    'Remove all propellers. Secure the frame, verify supply and motor output lock. ARM is a separate action; no throttle command is sent by this dialog.',
                                  ) &&
                                  mounted &&
                                  f.canArm) {
                                f.link.send('arm', {'confirm': true});
                              }
                            }
                          : null,
                      icon: const Icon(Icons.lock_open),
                      label: const Text('ARM'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  FilledButton(
                    onPressed: f.link.authenticated
                        ? () {
                            HapticFeedback.heavyImpact();
                            f.stop();
                          }
                        : null,
                    style: FilledButton.styleFrom(
                      backgroundColor: const Color(0xFFE34D59),
                      foregroundColor: Colors.white,
                    ),
                    child: const Text('STOP'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        height: 64,
        selectedIndex: page,
        onDestinationSelected: (index) {
          f.releaseInputs();
          setState(() => page = index);
        },
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.gamepad_outlined),
            label: 'Control',
          ),
          NavigationDestination(icon: Icon(Icons.tune), label: 'Setup'),
          NavigationDestination(
            icon: Icon(Icons.monitor_heart_outlined),
            label: 'Diagnostics',
          ),
        ],
      ),
    ),
  );

  Widget card(Widget child) => Card(
    margin: const EdgeInsets.only(bottom: 12),
    child: Padding(padding: const EdgeInsets.all(16), child: child),
  );
  Widget metric(String title, String value, IconData icon) => SizedBox(
    width: 150,
    child: Row(
      children: [
        Icon(icon, color: Colors.white38, size: 20),
        const SizedBox(width: 9),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  color: Colors.white38,
                  fontSize: 10,
                  letterSpacing: 1,
                ),
              ),
              Text(
                value,
                style: const TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );

  Widget dashboard() => LayoutBuilder(
    builder: (context, constraints) => SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        children: [
          card(
            Wrap(
              spacing: 14,
              runSpacing: 16,
              children: [
                metric(
                  'BATTERY',
                  reading('battery.voltage', ' V', 2),
                  Icons.battery_5_bar,
                ),
                metric(
                  'CURRENT',
                  reading('battery.current', ' A', 2),
                  Icons.electric_bolt,
                ),
                metric(
                  'REL ALTITUDE',
                  reading('altitude', ' m', 2),
                  Icons.height,
                ),
                metric(
                  'GPS / SATELLITES',
                  '${t?.at('gps.fix') ?? '—'} / ${reading('gps.satellites', '', 0)}',
                  Icons.satellite_alt,
                ),
              ],
            ),
          ),
          ArtificialHorizon(roll: t?.roll, pitch: t?.pitch),
          const SizedBox(height: 12),
          Row(
            children: [
              const Text(
                'PILOT INPUT',
                style: TextStyle(
                  fontSize: 11,
                  color: Colors.white54,
                  letterSpacing: 2,
                ),
              ),
              const Spacer(),
              Text(
                '${(f.throttle * 100).round()}% THR',
                style: const TextStyle(color: Color(0xFF62E6C4), fontSize: 12),
              ),
              const SizedBox(width: 12),
              Text(
                f.mode.toUpperCase(),
                style: const TextStyle(fontSize: 11, color: Colors.white54),
              ),
            ],
          ),
          SizedBox(
            height: (constraints.maxWidth * .48).clamp(170, 280),
            child: Row(
              children: [
                Expanded(
                  child: FlightStick(
                    key: const Key('left-stick'),
                    throttle: true,
                    throttleValue: f.throttle,
                    enabled: page == 0 && f.usable && f.foreground,
                    onUpdate: f.left,
                  ),
                ),
                Expanded(
                  child: FlightStick(
                    key: const Key('right-stick'),
                    enabled: page == 0 && f.usable && f.foreground,
                    onUpdate: f.right,
                  ),
                ),
              ],
            ),
          ),
          const Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              Text(
                'YAW  /  THROTTLE',
                style: TextStyle(
                  fontSize: 10,
                  color: Colors.white38,
                  letterSpacing: 1,
                ),
              ),
              Text(
                'ROLL  /  PITCH',
                style: TextStyle(
                  fontSize: 10,
                  color: Colors.white38,
                  letterSpacing: 1,
                ),
              ),
            ],
          ),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 14),
            child: Text(
              'Hold a stick to transmit. Releasing both stops control packets; firmware applies its verified bench failsafe. Throttle remains where set. STOP cuts output.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: Colors.white38),
            ),
          ),
          card(
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'GPS / OFFLINE TRACE',
                  style: TextStyle(
                    letterSpacing: 2,
                    fontSize: 11,
                    color: Colors.white54,
                  ),
                ),
                OfflineTrail(points: f.trail),
                SelectableText(
                  '${reading('gps.lat', '°', 6)}, ${reading('gps.lon', '°', 6)}',
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                ),
                const SizedBox(height: 6),
                Text(
                  'Home ${reading('gps.home_lat', '°', 6)}, ${reading('gps.home_lon', '°', 6)}',
                  style: const TextStyle(fontSize: 11, color: Colors.amber),
                ),
                Text(
                  'HDOP ${reading('gps.hdop')} · ${reading('gps.speed', ' m/s')} · ${t?.at('gps.fresh') == true ? 'fresh fix' : 'no fresh fix'}',
                  style: const TextStyle(fontSize: 11, color: Colors.white54),
                ),
                const SizedBox(height: 8),
                const Text(
                  'No Internet tiles required. Local trace is relative and auto-scaled; this is not a navigation chart. Yellow is the first recorded point.',
                  style: TextStyle(fontSize: 10, color: Colors.white38),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );

  Widget settings() => ListView(
    padding: const EdgeInsets.symmetric(horizontal: 16),
    children: [
      card(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Pilot settings', style: TextStyle(fontSize: 18)),
            const SizedBox(height: 10),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'rate', label: Text('RATE / BENCH')),
                ButtonSegment(value: 'angle', label: Text('ANGLE')),
              ],
              selected: {f.mode},
              onSelectionChanged: f.canConfigure
                  ? (selection) => setState(() => f.mode = selection.first)
                  : null,
            ),
            const SizedBox(height: 10),
            Text(
              'Stick expo ${(f.expo * 100).round()}% · 6% dead zone',
              style: const TextStyle(color: Colors.white54, fontSize: 12),
            ),
            Slider(
              value: f.expo,
              min: 0,
              max: .8,
              divisions: 8,
              onChanged: f.canConfigure
                  ? (value) => setState(() => f.expo = value)
                  : null,
            ),
            const Text(
              'Altitude hold, position hold, RTH and auto land are unavailable.',
              style: TextStyle(color: Colors.white38, fontSize: 12),
            ),
          ],
        ),
      ),
      PidEditor(
        enabled: f.canConfigure,
        confirm: confirm,
        send: (gains) {
          if (f.canConfigure) f.link.send('pid', {'gains': gains});
        },
      ),
      card(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Sensor calibration', style: TextStyle(fontSize: 18)),
            const SizedBox(height: 8),
            const Text(
              'DISARMED only. Calibrate gyro stationary; six accelerometer faces one at a time; magnetometer away from motor current. Review diagnostics before saving.',
              style: TextStyle(color: Colors.white54, fontSize: 12),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final item in <String, String>{
                  'gyro': 'Gyro',
                  'accel_xp': 'Accel X+',
                  'accel_xn': 'Accel X−',
                  'accel_yp': 'Accel Y+',
                  'accel_yn': 'Accel Y−',
                  'accel_zp': 'Accel Z+',
                  'accel_zn': 'Accel Z−',
                  'mag': 'Magnetometer',
                  'baro': 'Baro reference',
                  'save': 'Save to flash',
                }.entries)
                  OutlinedButton(
                    onPressed: f.canConfigure
                        ? () async {
                            if (await confirm(
                                  item.value,
                                  item.key == 'save'
                                      ? 'Persist calibration explicitly? Flash is not written by telemetry or joystick updates.'
                                      : 'Follow docs/calibration.md. Propellers removed and frame stationary except during magnetometer rotation. Start ${item.value}?',
                                ) &&
                                mounted &&
                                f.canConfigure) {
                              f.link.send('calibrate', {'target': item.key});
                            }
                          }
                        : null,
                    child: Text(item.value),
                  ),
              ],
            ),
            const SizedBox(height: 14),
            const Text(
              'IMU orientation check: nose down → positive pitch, right side down → positive roll in the documented body frame; verify firmware frame conventions before accepting. Installation rotation is set in firmware configuration.',
              style: TextStyle(color: Colors.amber, fontSize: 12),
            ),
          ],
        ),
      ),
    ],
  );

  Widget diagnostics() => ListView(
    padding: const EdgeInsets.symmetric(horizontal: 16),
    children: [
      card(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('System health', style: TextStyle(fontSize: 18)),
            const SizedBox(height: 14),
            for (final entry in <String, String>{
              'Firmware': '${t?.at('firmware') ?? '—'}',
              'I2C IMU': '${t?.at('sensors.imu') ?? '—'}',
              'I2C BMP280': '${t?.at('sensors.bmp') ?? '—'}',
              'I2C INA219': '${t?.at('sensors.ina') ?? '—'}',
              'GPS UART / fresh':
                  '${t?.at('gps.uart') ?? '—'} / ${t?.at('gps.fresh') ?? '—'}',
              'Loop frequency': reading('diagnostics.hz', ' Hz'),
              'Loop jitter': reading('diagnostics.jitter_us', ' µs', 0),
              'Deadline misses': reading('diagnostics.misses', '', 0),
              'Packet loss': reading('diagnostics.packet_loss', '', 0),
              'Packet age': reading('diagnostics.latency_ms', ' ms', 0),
              'Received packets': '${f.link.receivedPackets}',
              'Last server ack': f.link.lastAck,
              'Wi-Fi quality': 'Not estimated (AP RSSI is not link quality)',
            }.entries)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 7),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        entry.key,
                        style: const TextStyle(color: Colors.white54),
                      ),
                    ),
                    Text(entry.value),
                  ],
                ),
              ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: f.link.authenticated
                  ? () => f.link.send('telemetry')
                  : null,
              icon: const Icon(Icons.refresh),
              label: const Text('Request telemetry'),
            ),
          ],
        ),
      ),
      card(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Event log', style: TextStyle(fontSize: 18)),
            const SizedBox(height: 12),
            if (f.link.errors.isEmpty)
              const Text(
                'No received errors in this session.',
                style: TextStyle(color: Colors.white38),
              ),
            for (final error in f.link.errors)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  error,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                    color: Colors.orangeAccent,
                  ),
                ),
              ),
            if (t?.at('log') != null)
              Text('${t?.at('log')}', style: const TextStyle(fontSize: 12)),
          ],
        ),
      ),
    ],
  );
}

class PidEditor extends StatefulWidget {
  final bool enabled;
  final Future<bool> Function(String, String) confirm;
  final void Function(Map<String, dynamic>) send;
  const PidEditor({
    super.key,
    required this.enabled,
    required this.confirm,
    required this.send,
  });
  @override
  State<PidEditor> createState() => _PidEditorState();
}

class _PidEditorState extends State<PidEditor> {
  final form = GlobalKey<FormState>();
  final values = <String, List<TextEditingController>>{
    for (final axis in ['roll', 'pitch', 'yaw', 'attitude'])
      axis: List.generate(3, (_) => TextEditingController()),
  };
  @override
  void dispose() {
    for (final axis in values.values) {
      for (final c in axis) {
        c.dispose();
      }
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 12),
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Form(
        key: form,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('PID tuning', style: TextStyle(fontSize: 18)),
            const SizedBox(height: 6),
            const Text(
              'Enter complete gains from your reviewed bench configuration. No generic tuning is presumed safe. Values are staged until confirmed.',
              style: TextStyle(color: Colors.white54, fontSize: 12),
            ),
            for (final entry in values.entries)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      entry.key.toUpperCase(),
                      style: const TextStyle(
                        fontSize: 10,
                        letterSpacing: 1.5,
                        color: Colors.white54,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: List.generate(
                        3,
                        (index) => Expanded(
                          child: Padding(
                            padding: EdgeInsets.only(right: index == 2 ? 0 : 8),
                            child: TextFormField(
                              controller: entry.value[index],
                              enabled: widget.enabled,
                              keyboardType:
                                  const TextInputType.numberWithOptions(
                                    decimal: true,
                                  ),
                              decoration: InputDecoration(
                                labelText: ['P', 'I', 'D'][index],
                              ),
                              validator: (value) {
                                final n = double.tryParse(value ?? '');
                                return n == null ||
                                        !n.isFinite ||
                                        n < 0 ||
                                        n > 20
                                    ? '0–20'
                                    : null;
                              },
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            const SizedBox(height: 16),
            FilledButton.tonalIcon(
              onPressed: widget.enabled
                  ? () async {
                      if (!mounted || form.currentState?.validate() != true) {
                        return;
                      }
                      final gains = <String, dynamic>{
                        for (final entry in values.entries)
                          entry.key: <String, double>{
                            for (var i = 0; i < 3; i++)
                              ['p', 'i', 'd'][i]: double.parse(
                                entry.value[i].text,
                              ),
                          },
                      };
                      if (await widget.confirm(
                            'Apply PID gains',
                            'Apply all rate and attitude gains while DISARMED? These are unverified parameters. These PID gains are RAM-only and reset after reboot.',
                          ) &&
                          mounted &&
                          widget.enabled) {
                        widget.send(gains);
                      }
                    }
                  : null,
              icon: const Icon(Icons.check),
              label: const Text('Review & apply'),
            ),
          ],
        ),
      ),
    ),
  );
}
