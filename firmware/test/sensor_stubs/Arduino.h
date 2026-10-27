#pragma once
#include <cstdint>
#include <cstddef>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <map>
#include <string>
#include <vector>
using byte=uint8_t;
extern uint32_t fakeMs;
inline uint32_t millis(){return fakeMs;}
inline uint32_t micros(){return fakeMs*1000;}
struct FakeSerial { template<typename... T>void printf(const char*,T...){} template<typename T>void println(const T&){} };
extern FakeSerial Serial;
#ifndef TWO_PI
#define TWO_PI 6.28318530717958647692
#endif
inline double radians(double x){return x*0.01745329251994329577;}
inline double degrees(double x){return x*57.2957795130823208768;}
template<class T>inline T sq(T x){return x*x;}
constexpr int SERIAL_8N1=0;
struct FakeSerialConfig{uint32_t baud=0;int rx=-1,tx=-1;size_t rxBuffer=0;};
extern FakeSerialConfig fakeSerialConfig;
extern std::string fakeSerialInput;
struct HardwareSerial {
  explicit HardwareSerial(int){}
  void setRxBufferSize(size_t n){fakeSerialConfig.rxBuffer=n;}
  void begin(uint32_t baud,int,int rx,int tx){fakeSerialConfig.baud=baud;fakeSerialConfig.rx=rx;fakeSerialConfig.tx=tx;}
  int available(){return int(fakeSerialInput.size());}
  int read(){const int b=static_cast<unsigned char>(fakeSerialInput.front());fakeSerialInput.erase(0,1);return b;}
};
