# Drone Bench — Flutter Android console

Flutter 3.35.7 / Dart 3.9.2. Android project and lockfile are included. The console uses the firmware's authenticated JSON WebSocket protocol. It contains no real Wi-Fi password, session token, fabricated sensor values, Internet map dependency, or automatic reconnect/ARM behavior.

```sh
flutter pub get --enforce-lockfile
flutter analyze
flutter test
flutter build apk --debug
# Local Android phone with USB debugging:
flutter install --debug
```

Debug APK: `build/app/outputs/flutter-apk/app-debug.apk`. Release delivery needs your own Android signing key and reviewed Gradle signing configuration. The generated release template uses debug signing until you replace it; do not distribute it as a production-signed release. Android requires JDK 17+ (validated toolchain uses JDK 21), SDK platform 36, build-tools 35.0.0 and NDK 27.0.12077973 and CMake 3.22.1 for this Flutter release. The cloud helper pins a full Temurin 21.0.9+10 JDK (a JRE without javac cannot build APKs).

On this cloud machine the SDKs live outside the checkout:

```sh
export PATH=/workspace/tooling/flutter/bin:$PATH
export PUB_CACHE=/workspace/tooling/pub-cache
export XDG_CONFIG_HOME=/workspace/tooling/config
export ANALYZER_STATE_LOCATION_OVERRIDE=/workspace/tooling/dart-server
export FLUTTER_SUPPRESS_ANALYTICS=true CI=true
export ANDROID_HOME=/workspace/tooling/android-sdk
export ANDROID_USER_HOME=/workspace/tooling/android-user
export GRADLE_USER_HOME=/workspace/tooling/gradle
flutter --suppress-analytics analyze
flutter --suppress-analytics test
flutter --suppress-analytics build apk --debug
```

Join the firmware AP manually. Press **Connect**, keep the default `ws://192.168.4.1:81/ws`, and enter the separately provisioned session token. It stays in process memory and is not written to storage or packets after authentication. Local `ws://` is unencrypted; use the isolated WPA2 AP and an appropriately provisioned token. INTERNET and local cleartext WebSocket access are configured in the main Android manifest. No location permissions or online tiles are needed because the map is a relative offline coordinate trace. Do not treat it as navigation or flight control guidance.

**Control:** left horizontal yaw; left vertical throttle; right horizontal roll and vertical pitch. Two independent pointers work simultaneously. Roll/pitch/yaw center on release; throttle retains its value and uses relative vertical dragging so touching at a high position does not jump to a high throttle. Expo is adjustable while DISARMED. The default dead zone is 6%. Rightward yaw stick requests negative FLU yaw (right turn); rightward roll requests positive roll; forward pitch stick requests positive pitch (nose down). The horizon moves up for positive FLU pitch. Read `../docs/wiring.md` for signs.

Control packets are sent at 20 Hz only while at least one stick has live contact pulses and telemetry is fresh (<600 ms). Releasing both sticks, changing tabs, backgrounding the app, or losing contact pulses stops control packets. Heartbeat 5 Hz cannot replace control freshness. While one stick remains held, the throttle lever intentionally retains its last value; release both to cease control. Do not use this bench policy for airborne operation. ARM requires fresh DISARMED telemetry, throttle zero, then a separate confirmation. STOP sends emergency stop immediately and resets local controls. Verify its physical cutoff on the bench before any motor test.

Setup provides complete staged PID gains, a confirmation before applying RAM-only configuration, gyro/six-face accelerometer/magnetometer/barometer commands and explicit calibration persistence. Configuration buttons are locked unless fresh telemetry says DISARMED. The firmware independently enforces the same constraints. Calibration outcomes and rejected commands appear in firmware diagnostics; a socket send is not evidence of successful calibration.

Tests exercise a real local WebSocket handshake/auth/session timestamps and sequences, independent joystick pointers and cancellation, throttle retention/no jump, expo/dead zone, foreground and contact expiry, ARM/calibration gates, invalid GPS data and the disconnected dashboard. ESP32/phone Wi-Fi behavior, USB flashing, physical IMU signs, actual RTT/drop rate and Android device lifecycle still require hardware acceptance.

The Dart UI and services are portable to iOS, but an iOS host scaffold/signing and Xcode build have not been generated or validated on this Linux machine. On a Mac, review Flutter iOS setup and add the platform with `flutter create --platforms=ios .`, then configure local network permissions and signing.
