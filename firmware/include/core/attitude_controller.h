#pragma once
// Outer angle and inner angular-rate control, with explicit reset on disarm.
#include "command.h"
#include "pid.h"

namespace drone {
class CascadedController {
public:
  std::array<Pid, 2> attitude{{Pid(Gains(4, 0, 0, 3, .5f)), Pid(Gains(4, 0, 0, 3, .5f))}};
  std::array<Pid, 3> rate{{Pid(Gains(.08f, .04f, .001f, .35f, .15f)),
                           Pid(Gains(.08f, .04f, .001f, .35f, .15f)),
                           Pid(Gains(.08f, .04f, 0, .25f, .1f))}};
  float maxAngle = .35f, maxRate = 3.f, maxYawRate = 2.f;
  void reset() {
    for (auto &p : attitude)
      p.reset();
    for (auto &p : rate)
      p.reset();
  }
  Vec3 step(Command c, Euler a, Vec3 rates, float dt, bool angleMode) {
    if (!c.valid() || !rates.finite() || !std::isfinite(a.roll) || !std::isfinite(a.pitch) ||
        !std::isfinite(a.yaw)) {
      reset();
      return {};
    }
    float r = angleMode ? attitude[0].step(c.roll * maxAngle, a.roll, dt) : c.roll * maxRate;
    float p = angleMode ? attitude[1].step(c.pitch * maxAngle, a.pitch, dt) : c.pitch * maxRate;
    if (!angleMode) {
      attitude[0].reset();
      attitude[1].reset();
    }
    return {rate[0].step(clamp(r, -maxRate, maxRate), rates.x, dt),
            rate[1].step(clamp(p, -maxRate, maxRate), rates.y, dt),
            rate[2].step(c.yaw * maxYawRate, rates.z, dt)};
  }
};
} // namespace drone
