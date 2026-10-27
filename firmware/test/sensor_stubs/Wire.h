#pragma once
#include "Arduino.h"
struct TwoWire {
  std::map<uint8_t,uint16_t> registers;
  uint8_t address=0,reg=0,writeCount=0,readIndex=0;bool fail=false;
  void begin(uint8_t,uint8_t){}void setClock(int){}void setTimeOut(int){}
  void beginTransmission(uint8_t a){address=a;writeCount=0;}
  void write(uint8_t b){if(writeCount==0)reg=b; ++writeCount;}
  int endTransmission(bool=true){return fail?2:0;}
  uint8_t requestFrom(uint8_t,uint8_t n){readIndex=0;return fail?0:n;}
  uint8_t read(){return readIndex++==0?registers[reg]>>8:registers[reg]&255;}
};
extern TwoWire Wire;
