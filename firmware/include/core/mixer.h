#pragma once
// Explicit Quad X motor geometry and coupled moment desaturation.
#include "math.h"
#include <limits>

namespace drone {
// Geometry is explicitly ordered by physical ESC GPIO channel. x/y are +/-1;
// yawSign is the reaction torque sign, the opposite of propeller rotation viewed above.
struct MotorGeometry {
  float x, y, yawSign;
};
class Mixer {
  std::array<MotorGeometry, 4> geometry_{{{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}}};
  bool valid_ = false;

public:
  bool configure(const std::array<MotorGeometry, 4> &g) {
    bool corners[4] = {false, false, false, false};
    float sx = 0, sy = 0, sz = 0, sxz = 0, syz = 0;
    for (const auto &m : g) {
      if (std::abs(m.x) != 1 || std::abs(m.y) != 1 || std::abs(m.yawSign) != 1)
        return false;
      int k = (m.x > 0 ? 2 : 0) + (m.y > 0 ? 1 : 0);
      if (corners[k])
        return false;
      corners[k] = true;
      sx += m.x;
      sy += m.y;
      sz += m.yawSign;
      sxz += m.x * m.yawSign;
      syz += m.y * m.yawSign;
    }
    if (sx || sy || sz || sxz || syz)
      return false;
    geometry_ = g;
    valid_ = true;
    return true;
  }
  bool valid() const { return valid_; }
  std::array<float, 4> mix(float throttle, Vec3 torque) const {
    std::array<float, 4> out{{0, 0, 0, 0}};
    if (!valid_ || !std::isfinite(throttle) || throttle < 0 || throttle > 1 || !torque.finite())
      return out;
    float lo = std::numeric_limits<float>::max(), hi = -lo;
    for (int i = 0; i < 4; i++) {
      const auto &m = geometry_[i];
      out[i] = torque.x * m.y - torque.y * m.x + torque.z * m.yawSign;
      if (!std::isfinite(out[i]))
        return {{0, 0, 0, 0}};
      lo = std::fmin(lo, out[i]);
      hi = std::fmax(hi, out[i]);
    }
    // Scale all moments together if requested torque span cannot fit the motor range.
    float scale = hi - lo > 1 ? 1 / (hi - lo) : 1;
    lo *= scale;
    hi *= scale;
    float collective = clamp(throttle, -lo, 1 - hi);
    for (float &m : out)
      m = clamp(collective + m * scale, 0, 1);
    return out;
  }
};
} // namespace drone
