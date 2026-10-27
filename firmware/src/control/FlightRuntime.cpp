#include "../Runtime.h"
#include <esp_task_wdt.h>
namespace runtime {
void controlTask(void *) {
  Ahrs ahrs;
  CascadedController controller;
  Mixer mixer;
  EscDriver esc;
  StateMachine machine;
  mixer.configure(hardware::MOTORS);
  esc.begin();
  machine.beginSelfTest();
  esp_task_wdt_add(nullptr);
  Status out;
  SensorFrame frame;
  LinkState link;
  Command input;
  bool angleMode = false, calibrationPending = false,
       haveControlSinceDisarm = false;
  uint32_t lastLinkGeneration = 0;
  uint32_t requestCounter = 0, lastCompleted = 0, lastImu = 0, lastControl = 0,
           lastWakeUs = micros(), stable = 0;
  TickType_t wake = xTaskGetTickCount();
  for (;;) {
    uint32_t started = micros(), now = millis();
    float dt = uint32_t(started - lastWakeUs) * 1e-6f;
    out.periodUs = started - lastWakeUs;
    lastWakeUs = started;
    xQueueReceive(sensorQueue, &frame, 0);
    xQueueReceive(linkQueue, &link, 0);
    bool sessionChanged = link.generation != lastLinkGeneration;
    if (sessionChanged) {
      lastLinkGeneration = link.generation;
      input = {};
      lastControl = 0;
      haveControlSinceDisarm = false;
    }
    if (machine.state() == FlightState::SELF_TEST && frame.initialized)
      machine.finishSelfTest(frame.sensors.imuPresent);
    if (frame.imu.valid && frame.imu.microsStamp != lastImu) {
      float sensorDt =
          lastImu ? uint32_t(frame.imu.microsStamp - lastImu) * 1e-6f : .004f;
      lastImu = frame.imu.microsStamp;
      auto v = [](const float *a) { return Vec3{a[0], a[1], a[2]}; };
      ahrs.update(v(frame.imu.gyroRad), v(frame.imu.accelG), v(frame.imu.magUt),
                  sensorDt, frame.imu.magValid);
    }
    bool fresh =
        frame.imu.valid && uint32_t(started - frame.imu.microsStamp) < 20000;
    if (dt > .002f && dt < .006f && fresh)
      ++stable;
    else
      stable = 0;
    if (frame.calibrationRequestId &&
        frame.calibrationRequestId != lastCompleted && !frame.busy) {
      lastCompleted = frame.calibrationRequestId;
      calibrationPending = false;
      ahrs.reset();
      stable = 0;
    }
    if (emergencyLatched.load())
      machine.emergencyStop();
    Checks checks;
    checks.imu = fresh && ahrs.healthy();
    checks.calibrated = frame.calibrated && !frame.busy && !calibrationPending;
    checks.authenticated = !sessionChanged && link.authenticated &&
                           uint32_t(now - link.stamp) < 100;
    // Configuration/mapping are required even in mock: do not hide missing
    // acceptance.
    checks.hardware = mixer.valid() && config::MOTOR_MAPPING_VERIFIED &&
                      config::IMU_MOUNT_VERIFIED;
    float volts = frame.sensors.sourceVoltageV;
    checks.battery =
        config::BATTERY_CONFIG_VERIFIED && config::BATTERY_CELLS > 0 &&
        frame.sensors.batteryValid &&
        uint32_t(now - frame.sensors.batteryMs) < 500 &&
        volts >= config::BATTERY_CELLS * config::MIN_CELL_VOLTAGE &&
        volts <= config::BATTERY_CELLS * config::MAX_CELL_VOLTAGE;
    checks.timing = stable >= 500;
    CommandMessage msg;
    int handled = 0;
    while (handled++ < 8 && xQueueReceive(commandQueue, &msg, 0) == pdTRUE) {
      bool local = msg.clientId == 255 && (msg.type == CommandType::Calibrate ||
                                           msg.type == CommandType::Disarm);
      if (!local && msg.sessionGeneration != link.generation)
        continue;
      switch (msg.type) {
      case CommandType::Estop:
        machine.emergencyStop();
        input = {};
        haveControlSinceDisarm = false;
        break;
      case CommandType::Disarm:
        machine.disarm();
        input = {};
        haveControlSinceDisarm = false;
        controller.reset();
        break;
      case CommandType::Control:
        if (checks.authenticated &&
            uint32_t(now - msg.receivedMs) <= config::COMMAND_TIMEOUT_MS) {
          input = {msg.throttle, msg.roll, msg.pitch, msg.yaw};
          if (angleMode != msg.angleMode && machine.armed()) {
            machine.disarm();
            strlcpy(out.commandResult, "mode change requires disarm",
                    sizeof(out.commandResult));
          }
          angleMode = msg.angleMode;
          lastControl = msg.receivedMs;
          haveControlSinceDisarm = true;
        }
        break;
      case CommandType::Arm:
        checks.explicitArmConfirmation = true;
        checks.calibrated =
            checks.calibrated && !calibrationPending && !frame.busy;
        if (!haveControlSinceDisarm ||
            uint32_t(now - lastControl) > config::COMMAND_TIMEOUT_MS)
          strlcpy(out.commandResult,
                  "ARM rejected: fresh low-throttle control required",
                  sizeof(out.commandResult));
        else {
          machine.requestArm(checks, input.throttle);
          strlcpy(out.commandResult, machine.reason(),
                  sizeof(out.commandResult));
        }
        break;
      case CommandType::Pid:
        if (machine.state() == FlightState::DISARMED) {
          bool ok = true;
          for (int i = 0; i < 4; ++i) {
            Gains g(msg.gains[i][0], msg.gains[i][1], msg.gains[i][2],
                    i == 3 ? 3.f : .35f, i == 3 ? .5f : .15f);
            ok = ok && g.valid();
          }
          if (ok) {
            for (int i = 0; i < 3; ++i)
              controller.rate[i].configure(Gains(msg.gains[i][0],
                                                 msg.gains[i][1],
                                                 msg.gains[i][2], .35f, .15f));
            for (auto &p : controller.attitude)
              p.configure(Gains(msg.gains[3][0], msg.gains[3][1],
                                msg.gains[3][2], 3, .5f));
          }
          strlcpy(out.commandResult,
                  ok ? "PID applied in RAM; reboot resets defaults"
                     : "PID rejected",
                  sizeof(out.commandResult));
        } else
          strlcpy(out.commandResult, "PID requires DISARMED",
                  sizeof(out.commandResult));
        break;
      case CommandType::Calibrate:
        if (machine.state() == FlightState::DISARMED && !frame.busy &&
            !calibrationPending) {
          CalibrationRequest request;
          request.requestId = ++requestCounter;
          strlcpy(request.target, msg.target, sizeof(request.target));
          if (xQueueSend(calibrationQueue, &request, 0) == pdTRUE) {
            calibrationPending = true;
            checks.calibrated = false;
            haveControlSinceDisarm = false;
            input = {};
            strlcpy(out.commandResult, "calibration queued",
                    sizeof(out.commandResult));
          }
        } else
          strlcpy(out.commandResult, "calibration requires idle DISARMED",
                  sizeof(out.commandResult));
        break;
      default:
        break;
      }
    }
    if (emergencyLatched.load())
      machine.emergencyStop();
    machine.update(checks, input.throttle, now, lastControl);
    if (!machine.armed() || input.throttle < .01f)
      controller.reset();
    Vec3 torque = machine.armed() && input.throttle >= .01f
                      ? controller.step(input, ahrs.attitude(),
                                        ahrs.gyroFiltered(), dt, angleMode)
                      : Vec3{};
    // No torque-induced output at idle. BENCH-only failsafe cuts mock outputs.
    auto demand = machine.armed() && input.throttle >= .01f
                      ? mixer.mix(input.throttle, torque)
                      : std::array<float, 4>{};
    esc.write(demand, dt, machine.armed());
    out.prearm = checks;
    out.sensors = frame;
    out.angles = ahrs.attitude();
    out.rates = ahrs.gyroFiltered();
    out.motors = esc.output();
    out.angleMode = angleMode;
    out.state = machine.state();
    strlcpy(out.reason, machine.reason(), sizeof(out.reason));
    out.executionUs = micros() - started;
    out.timing.record(out.executionUs, out.periodUs, 4000);
    xQueueOverwrite(statusQueue, &out);
    esp_task_wdt_reset();
    vTaskDelayUntil(&wake, pdMS_TO_TICKS(4));
  }
}
} // namespace runtime
