#pragma once
#include "config.h"
#include "pins.h"
#include <Arduino.h>
#include <array>
namespace drone {
// Cutoff removes pulses. Its behavior on an actual ESC MUST be measured.
class EscDriver {
public:
  void begin();
  void write(const std::array<float, 4> &demand, float dt, bool enable);
  void cutoff();
  bool realEnabled() const { return DRONE_ENABLE_REAL_ESC; }
  const std::array<float, 4> &output() const { return output_; }

private:
  std::array<float, 4> output_{};
  bool attached_ = false;
};
} // namespace drone
