#include "../Runtime.h"
#include <ArduinoJson.h>
namespace runtime {
void telemetry(const Status &s) {
  static StaticJsonDocument<4096> doc;
  doc.clear();
  doc["v"] = 1;
  doc["type"] = "telemetry";
  doc["now_ms"] = millis();
  doc["firmware"] = config::VERSION;
  doc["mode"] = s.angleMode ? "angle" : "rate";
  doc["state"] = stateName(s.state);
  doc["mock"] = s.mock;
  doc["reason"] = s.reason;
  doc["command_result"] = s.commandResult;
  doc["failsafe"] = (s.state == FlightState::FAILSAFE ||
                     s.state == FlightState::EMERGENCY_STOP)
                        ? s.reason
                        : "";
  auto prearm = doc.createNestedObject("prearm");
  prearm["imu"] = s.prearm.imu;
  prearm["calibrated"] = s.prearm.calibrated;
  prearm["authenticated"] = s.prearm.authenticated;
  prearm["hardware"] = s.prearm.hardware;
  prearm["battery"] = s.prearm.battery;
  prearm["timing"] = s.prearm.timing;
  auto a = doc.createNestedObject("attitude");
  a["roll"] = s.angles.roll * 180 / pi;
  a["pitch"] = s.angles.pitch * 180 / pi;
  a["yaw"] = s.angles.yaw * 180 / pi;
  auto r = doc.createNestedObject("rates");
  r["x"] = s.rates.x;
  r["y"] = s.rates.y;
  r["z"] = s.rates.z;
  auto b = doc.createNestedObject("battery");
  const auto &hw = s.sensors.sensors;
  bool batteryFresh =
      hw.batteryValid && uint32_t(millis() - hw.batteryMs) < 500;
  bool baroFresh = hw.baroValid && uint32_t(millis() - hw.baroMs) < 500;
  if (batteryFresh) {
    b["voltage"] = hw.sourceVoltageV;
    b["bus_voltage"] = hw.busVoltageV;
    b["shunt_voltage"] = hw.shuntVoltageV;
  } else
    b["voltage"] = nullptr;
  if (hw.currentValid && batteryFresh)
    b["current"] = hw.currentA;
  else
    b["current"] = nullptr;
  if (baroFresh)
    doc["altitude"] = hw.relativeAltitudeM;
  else
    doc["altitude"] = nullptr;
  auto sensors = doc.createNestedObject("sensors");
  sensors["imu"] = s.sensors.imu.valid &&
                   uint32_t(micros() - s.sensors.imu.microsStamp) < 20000;
  sensors["bmp"] = baroFresh;
  sensors["ina"] = batteryFresh;
  sensors["calibrated"] = s.sensors.calibrated;
  sensors["calibration"] = s.sensors.calibration;
  sensors["imu_errors"] = hw.imuErrors;
  sensors["i2c_errors"] = hw.i2cErrors;
  auto raw = doc.createNestedObject("imu");
  auto accel = raw.createNestedArray("accel_g");
  for (float v : s.sensors.imu.accelG)
    accel.add(v);
  auto mag = raw.createNestedArray("mag_ut");
  for (float v : s.sensors.imu.magUt)
    mag.add(v);
  raw["mag_valid"] = s.sensors.imu.magValid;
  if (baroFresh) {
    doc["pressure_pa"] = hw.pressurePa;
    doc["temperature_c"] = hw.temperatureC;
  }
  auto g = doc.createNestedObject("gps");
  const auto &fix = gps.snapshot();
  g["fresh"] = fix.valid;
  g["fix"] = fix.fixType;
  g["satellites"] = fix.satellites;
  g["hdop"] = fix.hdop;
  if (fix.valid) {
    g["lat"] = fix.latitude;
    g["lon"] = fix.longitude;
    if (fix.altitudeValid)
      g["altitude"] = fix.altitudeM;
    if (fix.speedValid)
      g["speed"] = fix.speedMps;
  }
  if (fix.homeValid) {
    g["home_lat"] = fix.homeLatitude;
    g["home_lon"] = fix.homeLongitude;
  }
  g["altitude_valid"] = fix.altitudeValid;
  g["speed_valid"] = fix.speedValid;
  g["uart_active"] = fix.uartActive;
  g["checksum_failures"] = fix.checksumFailures;
  auto d = doc.createNestedObject("diagnostics");
  d["hz"] = s.periodUs ? 1000000.f / s.periodUs : 0;
  d["jitter_us"] = s.timing.worstJitterUs;
  d["misses"] = s.timing.deadlineMisses;
  d["execution_us"] = s.executionUs;
  d["worst_execution_us"] = s.timing.worstExecutionUs;
  d["packet_loss"] = network.packetLoss();
  d["latency_ms"] = network.latencyMs();
  d["rejected"] = network.rejected();
  auto outputs = doc.createNestedArray("outputs");
  for (float v : s.motors)
    outputs.add(v);
  static char buffer[4096];
  serializeJson(doc, buffer, sizeof(buffer));
  network.sendTelemetry(buffer);
  static uint32_t serialAt = 0;
  if (uint32_t(millis() - serialAt) > 1000) {
    serialAt = millis();
    Serial.println(buffer);
  }
}
} // namespace runtime
