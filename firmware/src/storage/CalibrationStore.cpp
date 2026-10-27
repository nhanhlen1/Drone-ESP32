#include "CalibrationStore.h"
#include <Preferences.h>
#include <cmath>
#include <cstddef>
namespace hw {
static uint32_t hash(const CalibrationData& d) {
  uint32_t h=2166136261u;
  const uint8_t* p=reinterpret_cast<const uint8_t*>(&d);
  for(size_t i=0;i<offsetof(CalibrationData,checksum);++i) h=(h^p[i])*16777619u;
  return h;
}
bool CalibrationStore::valid(const CalibrationData& d) {
  if(d.version!=3 || d.flags>7) return false;
  for(int i=0;i<3;++i) {
    if(!std::isfinite(d.gyroBias[i]) || fabs(d.gyroBias[i])>.2f ||
       !std::isfinite(d.accelBias[i]) || fabs(d.accelBias[i])>.4f ||
       !std::isfinite(d.accelScale[i]) || d.accelScale[i]<.5f || d.accelScale[i]>2.f ||
       !std::isfinite(d.magBias[i]) || fabs(d.magBias[i])>200.f ||
       !std::isfinite(d.magScale[i]) || d.magScale[i]<.25f || d.magScale[i]>4.f) return false;
  }
  return true;
}
bool CalibrationStore::load(CalibrationData& d) {
  Preferences p;
  if(!p.begin("drone-cal",true)) return false;
  CalibrationData temp;
  const bool ok=p.getBytesLength("v3")==sizeof(temp) && p.getBytes("v3",&temp,sizeof(temp))==sizeof(temp);
  p.end();
  if(!ok || !valid(temp) || hash(temp)!=temp.checksum) return false;
  d=temp; return true;
}
bool CalibrationStore::save(CalibrationData d) {
  if(!valid(d)) return false;
  d.checksum=hash(d);
  Preferences p;
  if(!p.begin("drone-cal",false)) return false;
  const bool ok=p.putBytes("v3",&d,sizeof(d))==sizeof(d);
  p.end(); return ok;
}
}
