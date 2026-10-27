#include "../Runtime.h"
namespace runtime {
// Calibration transactions are owned by sensorTask; controller locks ARM until
// completion.
void sensorTask(void *) {
  hw::SensorManager manager;
  hw::SensorConfig cfg;
  cfg.imuAddress = hardware::IMU_ADDRESS;
  cfg.bmpAddress = hardware::BMP_ADDRESS;
  cfg.inaAddress = hardware::INA_ADDRESS;
  cfg.shuntOhms = hardware::SHUNT_OHMS;
  cfg.currentLsbA = hardware::CURRENT_LSB_A;
  cfg.batteryCells = config::BATTERY_CELLS;
  for (int i = 0; i < 3; ++i)
    cfg.axisMap[i] = hardware::IMU_AXES[i];
  manager.begin(cfg);
  SensorFrame frame;
  frame.initialized = true;
  TickType_t wake = xTaskGetTickCount();
  for (;;) {
    CalibrationRequest r;
    if (xQueueReceive(calibrationQueue, &r, 0) == pdTRUE) {
      if (!manager.calibrate(r.target))
        Serial.printf("Calibration rejected: %s\n", r.target);
      frame.calibrationRequestId = r.requestId;
    }
    hw::ImuSample sample;
    if (manager.readImu(sample))
      frame.imu = sample;
    manager.pollSlow(millis());
    frame.sensors = manager.snapshot();
    frame.calibrated = manager.calibrationValid();
    strlcpy(frame.calibration, manager.calibrationStatus(),
            sizeof(frame.calibration));
    frame.busy = manager.calibrating();
    xQueueOverwrite(sensorQueue, &frame);
    vTaskDelayUntil(&wake, pdMS_TO_TICKS(4));
  }
}
} // namespace runtime
