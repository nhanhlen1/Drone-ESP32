import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';

typedef StickUpdate = void Function(double x, double y, bool held);

/// Each stick owns one pointer; two sticks can therefore be held simultaneously.
class FlightStick extends StatefulWidget {
  final bool throttle;
  final bool enabled;
  final double throttleValue;
  final StickUpdate onUpdate;
  const FlightStick({
    super.key,
    this.throttle = false,
    required this.enabled,
    this.throttleValue = 0,
    required this.onUpdate,
  });
  @override
  State<FlightStick> createState() => _FlightStickState();
}

class _FlightStickState extends State<FlightStick> {
  int? pointer;
  Offset value = Offset.zero;
  Timer? pulse;
  Size size = Size.zero;
  Offset throttleAnchor = Offset.zero;
  double anchorThrottle = 0;
  void update(Offset point) {
    final radius = math.min(size.width, size.height) * .37;
    final center = Offset(size.width / 2, size.height / 2);
    final delta = point - center;
    var x = (delta.dx / radius).clamp(-1.0, 1.0);
    var y = widget.throttle
        ? (anchorThrottle * 2 - 1 - (point.dy - throttleAnchor.dy) / radius)
              .clamp(-1.0, 1.0)
        : (-delta.dy / radius).clamp(-1.0, 1.0);
    if (!widget.throttle) {
      final magnitude = math.sqrt(x * x + y * y);
      if (magnitude > 1) {
        x /= magnitude;
        y /= magnitude;
      }
    }
    setState(() => value = Offset(x, y));
    emit();
  }

  void emit() => widget.onUpdate(
    value.dx,
    widget.throttle ? (value.dy + 1) / 2 : value.dy,
    true,
  );
  void release() {
    if (pointer == null) return;
    pulse?.cancel();
    pointer = null;
    value = Offset(0, widget.throttle ? widget.throttleValue * 2 - 1 : 0);
    widget.onUpdate(0, widget.throttleValue, false);
    if (mounted) setState(() {});
  }

  @override
  void didUpdateWidget(covariant FlightStick oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.enabled && pointer != null) {
      // Avoid calling a parent notifier synchronously during build.
      pulse?.cancel();
      pointer = null;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onUpdate(0, widget.throttleValue, false);
      });
    }
  }

  @override
  void dispose() {
    pulse?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      size = Size(constraints.maxWidth, constraints.maxHeight);
      final shown = pointer == null
          ? Offset(0, widget.throttle ? widget.throttleValue * 2 - 1 : 0)
          : value;
      return RawGestureDetector(
        gestures: widget.enabled
            ? {
                EagerGestureRecognizer:
                    GestureRecognizerFactoryWithHandlers<
                      EagerGestureRecognizer
                    >(() => EagerGestureRecognizer(), (instance) {}),
              }
            : const {},
        child: Listener(
          behavior: HitTestBehavior.opaque,
          onPointerDown: (event) {
            if (!widget.enabled || pointer != null) return;
            pointer = event.pointer;
            throttleAnchor = event.localPosition;
            anchorThrottle = widget.throttleValue;
            update(event.localPosition);
            pulse = Timer.periodic(
              const Duration(milliseconds: 50),
              (_) => emit(),
            );
          },
          onPointerMove: (event) {
            if (pointer == event.pointer) update(event.localPosition);
          },
          onPointerUp: (event) {
            if (pointer == event.pointer) release();
          },
          onPointerCancel: (event) {
            if (pointer == event.pointer) release();
          },
          child: CustomPaint(
            painter: _StickPainter(shown, widget.enabled, widget.throttle),
            child: const SizedBox.expand(),
          ),
        ),
      );
    },
  );
}

class _StickPainter extends CustomPainter {
  final Offset value;
  final bool enabled, throttle;
  _StickPainter(this.value, this.enabled, this.throttle);
  @override
  void paint(Canvas canvas, Size size) {
    final c = Offset(size.width / 2, size.height / 2);
    final r = math.min(size.width, size.height) * .37;
    final color = enabled ? const Color(0xFF62E6C4) : const Color(0xFF5A6679);
    canvas.drawCircle(c, r, Paint()..color = const Color(0xFF151F30));
    canvas.drawCircle(
      c,
      r,
      Paint()
        ..color = color.withValues(alpha: .35)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );
    final axis = Paint()
      ..color = color.withValues(alpha: .15)
      ..strokeWidth = 1;
    canvas.drawLine(c - Offset(r, 0), c + Offset(r, 0), axis);
    canvas.drawLine(c - Offset(0, r), c + Offset(0, r), axis);
    canvas.drawCircle(
      c,
      r * .4,
      Paint()
        ..color = color.withValues(alpha: .1)
        ..style = PaintingStyle.stroke,
    );
    final knob = c + Offset(value.dx * r, -value.dy * r);
    canvas.drawLine(
      c,
      knob,
      Paint()
        ..color = color.withValues(alpha: .35)
        ..strokeWidth = 3,
    );
    canvas.drawCircle(
      knob,
      r * .22,
      Paint()..color = color.withValues(alpha: .18),
    );
    canvas.drawCircle(knob, r * .15, Paint()..color = color);
    if (!enabled) {
      final text = TextPainter(
        text: const TextSpan(
          text: 'INPUT LOCKED',
          style: TextStyle(
            color: Colors.white54,
            fontSize: 10,
            letterSpacing: 2,
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      text.paint(canvas, Offset(c.dx - text.width / 2, c.dy + r + 8));
    }
  }

  @override
  bool shouldRepaint(covariant _StickPainter oldDelegate) =>
      oldDelegate.value != value || oldDelegate.enabled != enabled;
}

class ArtificialHorizon extends StatelessWidget {
  final double? roll, pitch;
  const ArtificialHorizon({super.key, this.roll, this.pitch});
  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(18),
    child: CustomPaint(
      painter: _HorizonPainter(
        roll ?? 0,
        pitch ?? 0,
        roll != null && pitch != null,
      ),
      child: SizedBox(
        height: 170,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Text(
              roll == null
                  ? 'NO ATTITUDE DATA'
                  : 'ROLL ${roll!.toStringAsFixed(1)}°   PITCH ${pitch!.toStringAsFixed(1)}°',
              style: const TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.bold,
                shadows: [Shadow(blurRadius: 4, color: Colors.black)],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _HorizonPainter extends CustomPainter {
  final double roll, pitch;
  final bool valid;
  _HorizonPainter(this.roll, this.pitch, this.valid);
  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.rotate(-roll * math.pi / 180);
    // FLU positive pitch is nose down, so the horizon moves upward.
    final shift = -pitch.clamp(-90, 90) * size.height / 90;
    canvas.translate(0, shift);
    final extent = size.longestSide * 3;
    canvas.drawRect(
      Rect.fromLTRB(-extent, -extent, extent, 0),
      Paint()
        ..color = valid ? const Color(0xFF244F70) : const Color(0xFF233044),
    );
    canvas.drawRect(
      Rect.fromLTRB(-extent, 0, extent, extent),
      Paint()
        ..color = valid ? const Color(0xFF75573C) : const Color(0xFF20293A),
    );
    final white = Paint()
      ..color = Colors.white70
      ..strokeWidth = 1.5;
    canvas.drawLine(Offset(-extent, 0), Offset(extent, 0), white);
    for (var degrees = -30; degrees <= 30; degrees += 10) {
      if (degrees == 0) continue;
      final y = -degrees * size.height / 90;
      canvas.drawLine(Offset(-18, y), Offset(18, y), white);
    }
    canvas.restore();
    final gold = Paint()
      ..color = const Color(0xFFFFD779)
      ..strokeWidth = 3
      ..style = PaintingStyle.stroke;
    final c = Offset(size.width / 2, size.height / 2);
    canvas.drawLine(c - const Offset(45, 0), c - const Offset(12, 0), gold);
    canvas.drawLine(c + const Offset(12, 0), c + const Offset(45, 0), gold);
    canvas.drawCircle(c, 5, gold);
  }

  @override
  bool shouldRepaint(covariant _HorizonPainter oldDelegate) =>
      roll != oldDelegate.roll ||
      pitch != oldDelegate.pitch ||
      valid != oldDelegate.valid;
}

class OfflineTrail extends StatelessWidget {
  final List<(double, double)> points;
  const OfflineTrail({super.key, required this.points});
  @override
  Widget build(BuildContext context) => SizedBox(
    height: 180,
    child: CustomPaint(
      painter: _TrailPainter(points),
      child: const SizedBox.expand(),
    ),
  );
}

class _TrailPainter extends CustomPainter {
  final List<(double, double)> points;
  _TrailPainter(List<(double, double)> points) : points = List.of(points);
  @override
  void paint(Canvas canvas, Size size) {
    final grid = Paint()
      ..color = Colors.white.withValues(alpha: .07)
      ..strokeWidth = 1;
    for (double x = 0; x < size.width; x += 24) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), grid);
    }
    for (double y = 0; y < size.height; y += 24) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }
    if (points.isEmpty) return;
    final lat0 = points.first.$1, lon0 = points.first.$2;
    final projected = points
        .map(
          (p) => Offset(
            (p.$2 - lon0) * math.cos(lat0 * math.pi / 180),
            p.$1 - lat0,
          ),
        )
        .toList();
    double minX = projected.first.dx,
        maxX = minX,
        minY = projected.first.dy,
        maxY = minY;
    for (final p in projected) {
      minX = math.min(minX, p.dx);
      maxX = math.max(maxX, p.dx);
      minY = math.min(minY, p.dy);
      maxY = math.max(maxY, p.dy);
    }
    final span = math.max(math.max(maxX - minX, maxY - minY), .0001);
    Offset toScreen(Offset p) => Offset(
      20 +
          (p.dx - (minX + maxX) / 2) / span * (size.width - 40) +
          (size.width - 40) / 2,
      size.height / 2 - (p.dy - (minY + maxY) / 2) / span * (size.height - 40),
    );
    final path = Path()
      ..moveTo(toScreen(projected.first).dx, toScreen(projected.first).dy);
    for (final p in projected.skip(1)) {
      final v = toScreen(p);
      path.lineTo(v.dx, v.dy);
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = const Color(0xFF62E6C4)
        ..strokeWidth = 2
        ..style = PaintingStyle.stroke,
    );
    canvas.drawCircle(
      toScreen(projected.first),
      5,
      Paint()..color = const Color(0xFFFFD779),
    );
    canvas.drawCircle(
      toScreen(projected.last),
      6,
      Paint()..color = const Color(0xFF62E6C4),
    );
  }

  @override
  bool shouldRepaint(covariant _TrailPainter oldDelegate) => true;
}
