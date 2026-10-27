#include "EscDriver.h"
#include <algorithm>
#include <cmath>
static_assert(!DRONE_ENABLE_REAL_ESC || (config::ESC_SIGNAL_VERIFIED &&
                                         config::MOTOR_MAPPING_VERIFIED &&
                                         config::IMU_MOUNT_VERIFIED &&
                                         config::BATTERY_CONFIG_VERIFIED &&
                                         config::REAL_FAILSAFE_VERIFIED),
              "Real ESC output forbidden: complete measured bench acceptance "
              "and independent failsafe review first");
namespace drone {
void EscDriver::begin() {
  for (auto pin : pins::ESC)
    pinMode(pin, INPUT); // No boot ARM pulse.
  cutoff();
}
void EscDriver::cutoff() {
#if DRONE_ENABLE_REAL_ESC
  if (attached_) {
    for (int i = 0; i < 4; ++i) {
      ledcWrite(i, 0);
      ledcDetachPin(pins::ESC[i]);
      pinMode(pins::ESC[i], INPUT);
    }
  }
#endif
  attached_ = false;
  output_.fill(0);
}
void EscDriver::write(const std::array<float, 4> &demand, float dt,
                      bool enable) {
  if (!enable || !std::isfinite(dt) || dt <= 0 || dt > 0.02f) {
    cutoff();
    return;
  }
  for (float v : demand)
    if (!std::isfinite(v)) {
      cutoff();
      return;
    }
#if DRONE_ENABLE_REAL_ESC
  if (!attached_) {
    for (int i = 0; i < 4; ++i) {
      ledcSetup(i, config::PWM_HZ, 16);
      ledcAttachPin(pins::ESC[i], i);
    }
    attached_ = true;
  }
#endif
  for (int i = 0; i < 4; ++i) {
    float desired = std::clamp(demand[i], 0.f, config::THROTTLE_LIMIT);
    float slew = config::THROTTLE_SLEW_PER_SEC * dt;
    output_[i] += std::clamp(desired - output_[i], -slew, slew);
#if DRONE_ENABLE_REAL_ESC
    float us = config::PWM_MIN_US +
               output_[i] * (config::PWM_MAX_US - config::PWM_MIN_US);
    ledcWrite(i,
              static_cast<uint32_t>(us * config::PWM_HZ * 65535.f / 1000000.f));
#endif
  }
}
} // namespace drone
