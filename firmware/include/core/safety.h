#pragma once
// Bench flight state machine, arming conditions and latched emergency stop.
#include <cmath>
#include <cstdint>

namespace drone {
enum class FlightState {
  BOOT,
  SELF_TEST,
  DISARMED,
  PRE_ARM_CHECK,
  ARMED_IDLE,
  ACTIVE,
  FAILSAFE,
  EMERGENCY_STOP
};
inline const char *stateName(FlightState s) {
  switch (s) {
  case FlightState::BOOT:
    return "BOOT";
  case FlightState::SELF_TEST:
    return "SELF_TEST";
  case FlightState::DISARMED:
    return "DISARMED";
  case FlightState::PRE_ARM_CHECK:
    return "PRE_ARM_CHECK";
  case FlightState::ARMED_IDLE:
    return "ARMED_IDLE";
  case FlightState::ACTIVE:
    return "ACTIVE";
  case FlightState::FAILSAFE:
    return "FAILSAFE";
  case FlightState::EMERGENCY_STOP:
    return "EMERGENCY_STOP";
  }
  return "INVALID";
}
struct Checks {
  bool imu = false, calibrated = false, authenticated = false, hardware = false, battery = false,
       timing = false, explicitArmConfirmation = false;
  const char *failure(bool confirm = false) const {
    if (!imu)
      return "imu";
    if (!calibrated)
      return "calibration";
    if (!authenticated)
      return "session";
    if (!hardware)
      return "hardware_interlock";
    if (!battery)
      return "battery";
    if (!timing)
      return "timing";
    if (confirm && !explicitArmConfirmation)
      return "confirm_arm";
    return nullptr;
  }
};
// This state machine implements BENCH CUTOFF only. There is no landing/flight
// failsafe here: a real-aircraft integration must supply and validate that policy.
class StateMachine {
  FlightState state_ = FlightState::BOOT;
  const char *reason_ = "boot";

public:
  static constexpr uint32_t timeoutMs = 300;
  void beginSelfTest() {
    if (state_ == FlightState::BOOT) {
      state_ = FlightState::SELF_TEST;
      reason_ = "self_test";
    }
  }
  void finishSelfTest(bool ok) {
    if (state_ == FlightState::SELF_TEST) {
      state_ = ok ? FlightState::DISARMED : FlightState::FAILSAFE;
      reason_ = ok ? "disarmed" : "self_test_failed";
    }
  }
  bool requestArm(Checks c, float throttle) {
    if (state_ != FlightState::DISARMED)
      return false;
    state_ = FlightState::PRE_ARM_CHECK;
    const char *why = c.failure(true);
    if (why || !std::isfinite(throttle) || throttle > 0.01f || throttle < 0) {
      reason_ = why ? why : "throttle_not_low";
      state_ = FlightState::DISARMED;
      return false;
    }
    state_ = FlightState::ARMED_IDLE;
    reason_ = "armed_bench";
    return true;
  }
  void update(Checks c, float throttle, uint32_t nowMs, uint32_t lastControlMs) {
    if (!armed())
      return;
    const char *why = c.failure();
    if (why || uint32_t(nowMs - lastControlMs) > timeoutMs || !std::isfinite(throttle) ||
        throttle < 0 || throttle > 1) {
      state_ = FlightState::FAILSAFE;
      reason_ = why ? why
                    : (uint32_t(nowMs - lastControlMs) > timeoutMs ? "control_timeout"
                                                                   : "invalid_command");
      return;
    }
    state_ = throttle > 0.02f ? FlightState::ACTIVE : FlightState::ARMED_IDLE;
  }
  void disarm() {
    if (state_ != FlightState::BOOT && state_ != FlightState::SELF_TEST &&
        state_ != FlightState::EMERGENCY_STOP) {
      state_ = FlightState::DISARMED;
      reason_ = "disarmed";
    }
  }
  void emergencyStop() {
    state_ = FlightState::EMERGENCY_STOP;
    reason_ = "emergency_stop_latched_reboot_required";
  }
  FlightState state() const { return state_; }
  const char *reason() const { return reason_; }
  bool armed() const { return state_ == FlightState::ARMED_IDLE || state_ == FlightState::ACTIVE; }
};
} // namespace drone
