#pragma once
#include <cstdint>
namespace hw {
struct CalibrationData {
  uint32_t version=3;
  int32_t axisMap[3]={1,2,3};
  float gyroBias[3]={};
  float accelBias[3]={};
  float accelScale[3]={1,1,1};
  float magBias[3]={};
  float magScale[3]={1,1,1};
  uint32_t flags=0; // bit 0 gyro, bit 1 six-face accelerometer, bit 2 magnetometer
  uint32_t checksum=0;
};
class CalibrationStore {
 public:
  static bool valid(const CalibrationData& data);
  static bool load(CalibrationData& data);
  static bool save(CalibrationData data);
};
}
