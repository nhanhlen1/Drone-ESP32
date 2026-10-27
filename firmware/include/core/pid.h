#pragma once
// Filtered PID with conditional anti-windup and bounded output.
#include "math.h"

namespace drone {
struct Gains {
  float kp = 0, ki = 0, kd = 0, limit = 1, integralLimit = 0.25f, derivativeHz = 30;
  Gains() = default;
  Gains(float p, float i, float d, float l = 1, float il = .25f, float hz = 30)
      : kp(p), ki(i), kd(d), limit(l), integralLimit(il), derivativeHz(hz) {}
  bool valid() const {
    return std::isfinite(kp) && std::isfinite(ki) && std::isfinite(kd) && std::isfinite(limit) &&
           std::isfinite(integralLimit) && std::isfinite(derivativeHz) && kp >= 0 && ki >= 0 &&
           kd >= 0 && kp <= 100 && ki <= 100 && kd <= 10 && limit > 0 && limit <= 20 &&
           integralLimit >= 0 && integralLimit <= limit && derivativeHz > 0 && derivativeHz <= 250;
  }
};
class Pid {
  Gains g_;
  float integral_ = 0, lastMeasurement_ = 0, derivative_ = 0;
  bool first_ = true;

public:
  Pid() = default;
  explicit Pid(Gains g) { configure(g); }
  bool configure(Gains g) {
    if (!g.valid())
      return false;
    g_ = g;
    reset();
    return true;
  }
  Gains gains() const { return g_; }
  void reset() {
    integral_ = 0;
    lastMeasurement_ = 0;
    derivative_ = 0;
    first_ = true;
  }
  float integral() const { return integral_; }
  float step(float target, float measured, float dt) {
    if (!std::isfinite(target) || !std::isfinite(measured) || !std::isfinite(dt) || dt < 1e-5f ||
        dt > .1f) {
      reset();
      return 0;
    }
    float error = target - measured;
    if (!std::isfinite(error)) {
      reset();
      return 0;
    }
    float raw = first_ ? 0 : -(measured - lastMeasurement_) / dt;
    if (!std::isfinite(raw)) {
      reset();
      return 0;
    }
    first_ = false;
    lastMeasurement_ = measured;
    float alpha = 2 * pi * g_.derivativeHz * dt / (1 + 2 * pi * g_.derivativeHz * dt);
    derivative_ += alpha * (raw - derivative_);
    float candidate = clamp(integral_ + g_.ki * error * dt, -g_.integralLimit, g_.integralLimit);
    float output = g_.kp * error + candidate + g_.kd * derivative_;
    // Conditional integration: retain only integration which does not deepen saturation.
    if ((output <= g_.limit && output >= -g_.limit) || (output > g_.limit && error < 0) ||
        (output < -g_.limit && error > 0))
      integral_ = candidate;
    return clamp(g_.kp * error + integral_ + g_.kd * derivative_, -g_.limit, g_.limit);
  }
};
} // namespace drone
