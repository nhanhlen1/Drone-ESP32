#include "Runtime.h"
#include <esp_task_wdt.h>
namespace runtime {
std::atomic<bool> emergencyLatched{false};
QueueHandle_t sensorQueue, statusQueue, commandQueue, linkQueue,
    calibrationQueue;
Network network;
hw::GpsManager gps;
bool runtimeReady = false;
} // namespace runtime
using namespace runtime;
void setup() {
  Serial.begin(115200);
  sensorQueue = xQueueCreate(1, sizeof(SensorFrame));
  statusQueue = xQueueCreate(1, sizeof(Status));
  commandQueue = xQueueCreate(16, sizeof(drone::CommandMessage));
  linkQueue = xQueueCreate(1, sizeof(LinkState));
  calibrationQueue = xQueueCreate(1, sizeof(CalibrationRequest));
  if (!sensorQueue || !statusQueue || !commandQueue || !linkQueue ||
      !calibrationQueue) {
    Serial.println("FATAL queues: outputs remain uninitialized");
    return;
  }
  esp_task_wdt_init(1, true);
  bool ok1 = xTaskCreatePinnedToCore(sensorTask, "sensors", 8192, nullptr, 4,
                                     nullptr, 1) == pdPASS;
  bool ok2 = ok1 && xTaskCreatePinnedToCore(controlTask, "control", 8192,
                                            nullptr, 5, nullptr, 1) == pdPASS;
  if (!ok2) {
    Serial.println("FATAL task creation: no motor outputs");
    return;
  }
  gps.begin(config::GPS_BAUD);
  network.begin();
  runtimeReady = true;
  Serial.println("BENCH MOCK: real motor output locked. Serial calibration: "
                 "cal gyro / cal accel_xp etc / cal save");
}
void loop() {
  if (!runtimeReady) {
    vTaskDelay(pdMS_TO_TICKS(100));
    return;
  }
  static Status status;
  xQueueReceive(statusQueue, &status, 0);
  bool armed = status.state == drone::FlightState::ARMED_IDLE ||
               status.state == drone::FlightState::ACTIVE;
  network.poll(armed);
  if (network.emergencyAccepted())
    emergencyLatched.store(true);
  gps.poll(millis());
  LinkState link{network.authenticated(), millis(),
                 network.sessionGeneration()};
  xQueueOverwrite(linkQueue, &link);
  drone::CommandMessage msg;
  while (network.receive(msg)) {
    if (msg.type == drone::CommandType::Estop)
      emergencyLatched.store(true);
    bool urgent = msg.type == drone::CommandType::Estop ||
                  msg.type == drone::CommandType::Disarm;
    if (urgent) {
      xQueueReset(commandQueue);
      xQueueSendToFront(commandQueue, &msg, 0);
    } else
      xQueueSend(commandQueue, &msg, 0);
  }
  // Serial is a physical, local-only diagnostics channel; never permits ARM or
  // motor test.
  static char line[40];
  static size_t used = 0;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      line[used] = 0;
      if (!strncmp(line, "cal ", 4)) {
        drone::CommandMessage m{};
        m.type = drone::CommandType::Calibrate;
        m.clientId = 255;
        strlcpy(m.target, line + 4, sizeof(m.target));
        xQueueSend(commandQueue, &m, 0);
      }
      if (!strcmp(line, "disarm")) {
        drone::CommandMessage m{};
        m.type = drone::CommandType::Disarm;
        m.clientId = 255;
        xQueueReset(commandQueue);
        xQueueSendToFront(commandQueue, &m, 0);
      }
      used = 0;
    } else if (c != '\r' && used < sizeof(line) - 1)
      line[used++] = c;
  }
  static uint32_t at = 0;
  if (uint32_t(millis() - at) >= 50) {
    at = millis();
    telemetry(status);
  }
  vTaskDelay(pdMS_TO_TICKS(1));
}
