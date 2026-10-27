#pragma once
#include <cstdint>
namespace config {
constexpr char VERSION[] = "0.1.0-bench";
constexpr char SSID[] = "ESP32_DRONE";
constexpr uint32_t CONTROL_HZ = 250, COMMAND_TIMEOUT_MS = 300;
constexpr uint32_t GPS_BAUD = 9600;
// MUST be changed only after measured acceptance documented in docs/testing.md.
constexpr bool ESC_SIGNAL_VERIFIED = false;
constexpr bool MOTOR_MAPPING_VERIFIED = false;
constexpr bool IMU_MOUNT_VERIFIED = false;
constexpr bool BATTERY_CONFIG_VERIFIED = false;
constexpr bool REAL_FAILSAFE_VERIFIED = false;
constexpr int PWM_HZ = 50, PWM_MIN_US = 1000, PWM_MAX_US = 2000;
constexpr float THROTTLE_LIMIT = 0.15f, THROTTLE_SLEW_PER_SEC = 0.25f;
constexpr int BATTERY_CELLS = 0;
constexpr float MIN_CELL_VOLTAGE = 3.5f, MAX_CELL_VOLTAGE = 4.25f;
} // namespace config
#if __has_include("local_secrets.h")
#include "local_secrets.h"
#endif
#ifndef DRONE_AP_PASSWORD
#define DRONE_AP_PASSWORD ""
#endif
#ifndef DRONE_SESSION_TOKEN
#define DRONE_SESSION_TOKEN ""
#endif
#ifndef DRONE_ENABLE_REAL_ESC
#define DRONE_ENABLE_REAL_ESC 0
#endif
