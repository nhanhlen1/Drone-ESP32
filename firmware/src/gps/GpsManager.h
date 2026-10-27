#pragma once
#include <Arduino.h>
#include <TinyGPSPlus.h>
namespace hw {
struct GpsSnapshot {
  double latitude=0,longitude=0,homeLatitude=0,homeLongitude=0;
  float altitudeM=0,hdop=99,speedMps=0;
  uint32_t satellites=0,lastFixMs=0,characters=0,checksumFailures=0;
  uint8_t fixType=0; // NMEA GSA: 1 none, 2 2D, 3 3D; 0 unknown
  bool valid=false,homeValid=false,uartActive=false,altitudeValid=false,speedValid=false;
};
class GpsManager {
 public:
  void begin(uint32_t baud=9600);
  void poll(uint32_t nowMs);
  const GpsSnapshot& snapshot() const { return state_; }
  void clearHome() {state_.homeValid=false;stableFixes_=0;}
 private:
  HardwareSerial serial_{2};
  TinyGPSPlus parser_;
  TinyGPSCustom gpGsa_{parser_,"GPGSA",2},gnGsa_{parser_,"GNGSA",2};
  TinyGPSCustom gpQuality_{parser_,"GPGGA",6},gnQuality_{parser_,"GNGGA",6};
  TinyGPSCustom gpStatus_{parser_,"GPRMC",2},gnStatus_{parser_,"GNRMC",2};
  GpsSnapshot state_;
  uint32_t lastByteMs_=0,lastGsaMs_=0;
  uint8_t stableFixes_=0,quality_=0;
  bool rmcReject_=false;
};
}
