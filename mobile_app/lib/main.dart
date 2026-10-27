import 'package:flutter/material.dart';
import 'controllers/flight_controller.dart';
import 'services/drone_link.dart';
import 'screens/console.dart';

void main() => runApp(const DroneApp());

class DroneApp extends StatefulWidget {
  const DroneApp({super.key});
  @override
  State<DroneApp> createState() => _DroneAppState();
}

class _DroneAppState extends State<DroneApp> with WidgetsBindingObserver {
  late final DroneLink link;
  late final FlightController flight;
  @override
  void initState() {
    super.initState();
    link = DroneLink();
    flight = FlightController(link);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    flight.foreground = state == AppLifecycleState.resumed;
    if (!flight.foreground) flight.releaseInputs();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    flight.dispose();
    link.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'DRONE / Bench Console',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xFF62E6C4),
        brightness: Brightness.dark,
        surface: const Color(0xFF111A29),
        primary: const Color(0xFF62E6C4),
      ),
      scaffoldBackgroundColor: const Color(0xFF080F1C),
      inputDecorationTheme: const InputDecorationTheme(
        border: OutlineInputBorder(),
        isDense: true,
      ),
      cardTheme: const CardThemeData(color: Color(0xFF111A29), elevation: 0),
      textTheme: const TextTheme(
        titleLarge: TextStyle(fontWeight: FontWeight.w700, letterSpacing: -.6),
      ),
    ),
    home: Console(flight: flight),
  );
}
