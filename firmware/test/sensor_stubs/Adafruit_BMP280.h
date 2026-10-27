#pragma once
struct Adafruit_BMP280 {
  enum {MODE_NORMAL=0,SAMPLING_X1=1,SAMPLING_X2=2,FILTER_X4=4,STANDBY_MS_1=1};
  bool begin(unsigned char){return true;}
  void setSampling(int,int,int,int,int){}
  float readPressure(){return 101325.f;}float readTemperature(){return 20.f;}
};
