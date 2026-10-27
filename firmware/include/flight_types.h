#pragma once
#include <cstdint>
namespace flight {
struct Timing {
  uint32_t executionUs = 0, periodUs = 0, jitterUs = 0, maxJitterUs = 0,
           misses = 0;
};
// Phase 2 navigation interface is deliberately unimplemented and cannot select
// flight modes.
struct NavigationRequirements {
  bool altitudeEstimator, positionEstimator, validatedFailsafe,
      flightAcceptance;
};
} // namespace flight
