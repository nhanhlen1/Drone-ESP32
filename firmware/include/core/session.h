#pragma once
// Pure sequence/freshness helper; the transport provides actual authentication.
#include "command.h"
#include <cstdint>

namespace drone {
// Authentication itself lives in the transport. Once it proves a session token,
// open() binds a single owner. sentMs echoes ESP32 monotonic telemetry time, so no
// wall clock sync is required. Heartbeats intentionally cannot refresh controls.
class SessionGate {
  uint32_t session_ = 0, lastSeq_ = 0, lastAccepted_ = 0, lastControl_ = 0;
  bool open_ = false, haveSeq_ = false;

public:
  uint32_t rejected = 0, missed = 0;
  void open(uint32_t session, uint32_t now) {
    session_ = session;
    lastAccepted_ = now;
    lastControl_ = now;
    open_ = true;
    haveSeq_ = false;
    rejected = 0;
    missed = 0;
  }
  void close() {
    open_ = false;
    haveSeq_ = false;
  }
  bool active() const { return open_; }
  bool accept(uint32_t session, uint32_t seq, uint32_t sentMs, uint32_t now, Command c) {
    uint32_t delta = seq - lastSeq_;
    if (!open_ || session != session_ || !c.valid() || uint32_t(now - sentMs) > 250 ||
        (haveSeq_ && (delta == 0 || delta >= 0x80000000u)) ||
        (haveSeq_ && uint32_t(now - lastAccepted_) < 10)) {
      rejected++;
      return false;
    }
    if (haveSeq_ && delta > 1)
      missed += delta - 1;
    lastSeq_ = seq;
    haveSeq_ = true;
    lastAccepted_ = now;
    lastControl_ = now;
    return true;
  }
  uint32_t lastControlMs() const { return lastControl_; }
};
} // namespace drone
