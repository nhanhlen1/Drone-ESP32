#pragma once
// Normalized control setpoints and finite/range validation.
#include <cmath>

namespace drone {
struct Command {
  float throttle = 0, roll = 0, pitch = 0, yaw = 0;
  bool valid() const {
    return std::isfinite(throttle) && std::isfinite(roll) && std::isfinite(pitch) &&
           std::isfinite(yaw) && throttle >= 0 && throttle <= 1 && std::abs(roll) <= 1 &&
           std::abs(pitch) <= 1 && std::abs(yaw) <= 1;
  }
};
} // namespace drone
