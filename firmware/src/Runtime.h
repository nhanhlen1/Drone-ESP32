#pragma once
#include "config.h"
#include "gps/GpsManager.h"
#include "hardware_config.h"
#include "motors/EscDriver.h"
#include "network/Network.h"
#include "sensors/SensorManager.h"
#include <Arduino.h>
#include <atomic>
namespace runtime {
using namespace drone;
struct SensorFrame {
  hw::ImuSample imu;
  hw::SensorSnapshot sensors;
  bool initialized = false, calibrated = false, busy = false;
  uint32_t calibrationRequestId = 0;
  char calibration[96]{};
};
struct Status {
  SensorFrame sensors;
  Euler angles;
  Vec3 rates;
  std::array<float, 4> motors{};
  FlightState state = FlightState::BOOT;
  LoopStats timing;
  Checks prearm;
  uint32_t periodUs = 0, executionUs = 0;
  char reason[64]{}, commandResult[96]{};
  bool mock = !DRONE_ENABLE_REAL_ESC, angleMode = false;
};
struct LinkState {
  bool authenticated = false;
  uint32_t stamp = 0, generation = 0;
};
struct CalibrationRequest {
  char target[16]{};
  uint32_t requestId = 0;
};
extern std::atomic<bool> emergencyLatched;
extern QueueHandle_t sensorQueue, statusQueue, commandQueue, linkQueue,
    calibrationQueue;
extern Network network;
extern hw::GpsManager gps;
void sensorTask(void *);
void controlTask(void *);
void telemetry(const Status &);
} // namespace runtime
