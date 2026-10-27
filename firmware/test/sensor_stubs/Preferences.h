#pragma once
#include "Arduino.h"
extern std::vector<uint8_t> fakeFlash;
struct Preferences {
  bool begin(const char*,bool){return true;}void end(){}
  size_t getBytesLength(const char*){return fakeFlash.size();}
  size_t getBytes(const char*,void* d,size_t n){if(fakeFlash.size()!=n)return 0;memcpy(d,fakeFlash.data(),n);return n;}
  size_t putBytes(const char*,const void* d,size_t n){auto p=static_cast<const uint8_t*>(d);fakeFlash.assign(p,p+n);return n;}
};
