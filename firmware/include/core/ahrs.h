#pragma once
// Allocation-free attitude estimation, gravity innovation and magnetic trust gates.
#include "math.h"

namespace drone {
// Quaternion complementary AHRS: proportional gravity alignment, gyro propagation,
// optional tilt-compensated magnetic heading correction. Gyro bias is calibrated
// externally: accelerometer/magnetometer cannot observe every bias at every pose.
class Ahrs {
  Quaternion q_;
  Vec3 filtered_;
  bool first_ = true, healthy_ = false;
  float accelAge_ = 1, elapsed_ = 0, alignment_ = -1;

public:
  float accelGain = 2.f, magGain = 1.f, gyroCutoffHz = 80.f, declinationRad = 0;
  void reset() {
    q_ = Quaternion{};
    filtered_ = Vec3{};
    first_ = true;
    healthy_ = false;
    accelAge_ = 1;
    elapsed_ = 0;
    alignment_ = -1;
  }
  bool update(Vec3 gyroRad, Vec3 accelG, Vec3 magUT, float dt, bool useMag = false) {
    if (!gyroRad.finite() || !std::isfinite(dt) || dt < 0.0002f || dt > 0.03f ||
        gyroRad.norm() > 35.f) {
      healthy_ = false;
      return false;
    }
    float alpha = clamp((2 * pi * gyroCutoffHz * dt) / (1 + 2 * pi * gyroCutoffHz * dt), 0, 1);
    if (first_) {
      filtered_ = gyroRad;
      first_ = false;
    } else
      filtered_ = filtered_ + (gyroRad - filtered_) * alpha;
    Vec3 omega = filtered_;
    float an = accelG.norm();
    accelAge_ += dt;
    elapsed_ += dt;
    if (accelG.finite() && an >= 0.8f && an <= 1.2f) {
      Vec3 measured = accelG * (1 / an), predicted = q_.inverseRotate({0, 0, 1});
      alignment_ = dot(measured, predicted);
      omega = omega + cross(measured, predicted) * accelGain;
      accelAge_ = 0;
    }
    float mn = magUT.norm();
    if (useMag && magUT.finite() && mn >= 15 && mn <= 80) {
      Vec3 world = q_.rotate(magUT);
      float horizontal = std::sqrt(world.x * world.x + world.y * world.y);
      if (horizontal > mn * 0.2f) {
        float err = wrapPi(declinationRad - std::atan2(world.y, world.x));
        omega = omega + q_.inverseRotate({0, 0, 1}) * (magGain * err);
      }
    }
    Quaternion p = q_;
    float h = dt * 0.5f;
    q_.w += (-p.x * omega.x - p.y * omega.y - p.z * omega.z) * h;
    q_.x += (p.w * omega.x + p.y * omega.z - p.z * omega.y) * h;
    q_.y += (p.w * omega.y - p.x * omega.z + p.z * omega.x) * h;
    q_.z += (p.w * omega.z + p.x * omega.y - p.y * omega.x) * h;
    if (!q_.normalize()) {
      reset();
      return false;
    }
    // An upside-down initial pose has zero cross-product error: elapsed time alone
    // cannot establish convergence. Require gravity innovation below 15 degrees.
    healthy_ = accelAge_ < 0.5f && elapsed_ >= 2.f && alignment_ > 0.9659258f;
    return true;
  }
  Euler attitude() const { return q_.euler(); }
  Vec3 gyroFiltered() const { return filtered_; }
  bool healthy() const { return healthy_; }
  Quaternion quaternion() const { return q_; }
};
} // namespace drone
