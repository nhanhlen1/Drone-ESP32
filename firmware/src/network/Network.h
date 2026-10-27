#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
#include <WebSocketsServer.h>
#include <WebServer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

namespace drone {
enum class CommandType : uint8_t { Control, Heartbeat, Arm, Disarm, Estop, Pid, Calibrate, Telemetry };
struct CommandMessage {
  CommandType type{CommandType::Heartbeat};
  float throttle{0}, roll{0}, pitch{0}, yaw{0};
  bool angleMode{false};
  float gains[4][3]{};  // roll, pitch, yaw rate PID, then shared attitude PID
  char target[16]{};
  uint8_t clientId{0};
  uint32_t receivedMs{0};
  uint32_t sessionGeneration{0};
};
// All methods belong to the low-priority network task. Pass snapshots to control.
class Network {
 public:
  bool begin();
  void poll(bool armed);
  bool receive(CommandMessage& command);
  void sendTelemetry(const char* json);
  bool authenticated() const;
  bool available() const { return available_; }
  bool emergencyAccepted() const { return emergencyAccepted_; }
  uint32_t packetLoss() const { return packetLoss_; }
  uint32_t latencyMs() const { return latencyMs_; }
  uint32_t rejected() const { return rejected_; }
  uint32_t sessionGeneration() const { return generation_; }
 private:
  static constexpr size_t kClients = 8;
  WebSocketsServer socket_{81};
  WebServer http_{80};
  QueueHandle_t queue_{nullptr};
  StaticJsonDocument<1536> document_;
  bool available_{false}, armed_{false}, hasSequence_{false}, emergencyAccepted_{false};
  int owner_{-1};
  char session_[33]{};
  uint32_t generation_{0};
  uint32_t lastSequence_{0}, lastLeaseMs_{0}, packetLoss_{0}, latencyMs_{0}, rejected_{0};
  uint32_t windows_[kClients]{}, counts_[kClients]{};
  void event(uint8_t client, WStype_t event, uint8_t* payload, size_t length);
  void message(uint8_t client, uint8_t* payload, size_t length);
  void revoke();
  void reply(uint8_t client, const char* type, const char* reason);
  bool enqueue(const CommandMessage& command, bool urgent=false);
};
}
