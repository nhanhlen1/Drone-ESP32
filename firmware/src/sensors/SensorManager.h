#pragma once
#include <Arduino.h>
#include <Wire.h>
#include <ICM_20948.h>
#include <Adafruit_BMP280.h>
#include "../storage/CalibrationStore.h"
namespace hw {
struct SensorConfig {
  uint8_t imuAddress=0x69, bmpAddress=0x76, inaAddress=0x40;
  uint8_t sda=21,scl=22;
  float shuntOhms=0, currentLsbA=0; // zero = unverified: current is unavailable
  uint8_t batteryCells=0;
  int8_t axisMap[3]={1,2,3}; // body FLU from IMU axes, signed proper rotation only
};
struct ImuSample {
  float accelG[3]={},gyroRad[3]={},magUt[3]={};
  bool valid=false,magValid=false;
  uint32_t microsStamp=0;
};
struct SensorSnapshot {
  bool imuPresent=false, bmpPresent=false, inaPresent=false;
  bool baroValid=false, batteryValid=false,currentValid=false;
  float temperatureC=0, pressurePa=0, relativeAltitudeM=0;
  float busVoltageV=0, shuntVoltageV=0, sourceVoltageV=0, currentA=0;
  uint32_t baroMs=0,batteryMs=0,imuErrors=0,i2cErrors=0;
  uint8_t calibrationFlags=0;
};
class SensorManager {
 public:
  bool begin(const SensorConfig& config);
  bool readImu(ImuSample& output);
  void pollSlow(uint32_t nowMs);
  const SensorSnapshot& snapshot() const { return state_; }
  bool calibrate(const char* target);
  bool calibrating() const { return collecting_!=Calibration::None; }
  bool calibrationValid() const { return !calibrating() && (cal_.flags&3)==3; }
  const char* calibrationStatus() const { return calibrationStatus_; }
  bool saveCalibration();
 private:
  enum class Calibration { None,Gyro,Accel,Mag };
  SensorConfig cfg_;
  ICM_20948_I2C imu_;
  Adafruit_BMP280 bmp_;
  SensorSnapshot state_;
  CalibrationData cal_;
  Calibration collecting_=Calibration::None;
  int face_=0;
  uint32_t sampleCount_=0, calibrationStartMs_=0,lastBaro_=0,lastIna_=0;
  double sum_[3]={},sumSq_[3]={};
  float magMin_[3]={},magMax_[3]={},lastMag_[3]={};
  float faces_[6][3]={};
  uint8_t faceMask_=0;
  float referencePa_=0;
  const char* calibrationStatus_="idle";
  bool validAxisMap() const;
  void rotate(const float* in,float* out) const;
  void collect(const float* a,const float* g,const float* m,bool magValid);
  bool readRegister(uint8_t reg,uint16_t& value);
  bool writeRegister(uint8_t reg,uint16_t value);
};
}
