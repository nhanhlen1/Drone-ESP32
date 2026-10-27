#pragma once
// Measured loop execution, interval jitter and missed-deadline counters.
#include <cstdint>

namespace drone {
struct LoopStats {
  uint32_t samples = 0, deadlineMisses = 0, worstExecutionUs = 0, worstJitterUs = 0;
  void record(uint32_t executionUs, uint32_t intervalUs, uint32_t periodUs) {
    samples++;
    if (executionUs > periodUs || intervalUs > periodUs + periodUs / 4)
      deadlineMisses++;
    if (executionUs > worstExecutionUs)
      worstExecutionUs = executionUs;
    uint32_t jitter = intervalUs > periodUs ? intervalUs - periodUs : periodUs - intervalUs;
    if (jitter > worstJitterUs)
      worstJitterUs = jitter;
  }
};
} // namespace drone
