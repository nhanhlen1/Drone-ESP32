#include "SensorManager.h"
#include <cmath>
#include <cstring>
#include <algorithm>
namespace hw {
static float norm(const float* v) { return sqrtf(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); }
bool SensorManager::validAxisMap() const {
  int seen=0, inversions=0,sign=1;
  for(int i=0;i<3;++i) {
    const int a=abs(cfg_.axisMap[i]);
    if(a<1 || a>3 || (seen&(1<<a))) return false;
    seen|=1<<a; if(cfg_.axisMap[i]<0) sign=-sign;
    for(int j=0;j<i;++j) if(abs(cfg_.axisMap[j])>a) ++inversions;
  }
  return sign*((inversions%2)?-1:1)==1;
}
void SensorManager::rotate(const float* in,float* out) const {
  for(int i=0;i<3;++i) out[i]=(cfg_.axisMap[i]>0?1.f:-1.f)*in[abs(cfg_.axisMap[i])-1];
}
bool SensorManager::readRegister(uint8_t reg,uint16_t& value) {
  Wire.beginTransmission(cfg_.inaAddress); Wire.write(reg);
  if(Wire.endTransmission(false)!=0 || Wire.requestFrom(cfg_.inaAddress,uint8_t(2))!=2) {++state_.i2cErrors;return false;}
  value=uint16_t(Wire.read())<<8; value|=Wire.read(); return true;
}
bool SensorManager::writeRegister(uint8_t reg,uint16_t value) {
  Wire.beginTransmission(cfg_.inaAddress); Wire.write(reg); Wire.write(uint8_t(value>>8)); Wire.write(uint8_t(value));
  if(Wire.endTransmission()!=0) {++state_.i2cErrors;return false;} return true;
}
bool SensorManager::begin(const SensorConfig& config) {
  cfg_=config;
  if(!validAxisMap() || (cfg_.imuAddress!=0x68 && cfg_.imuAddress!=0x69) ||
     (cfg_.bmpAddress!=0x76 && cfg_.bmpAddress!=0x77) || cfg_.inaAddress<0x40 || cfg_.inaAddress>0x4f) {
    Serial.println("Invalid I2C addresses or IMU proper-rotation mapping");return false;
  }
  Wire.begin(cfg_.sda,cfg_.scl); Wire.setClock(400000); Wire.setTimeOut(2);
  Serial.println("I2C scan (7-bit addresses):");
  bool found[128]={};
  for(int a=1;a<127;++a) {
    Wire.beginTransmission(a); if(Wire.endTransmission()==0) {found[a]=true;Serial.printf("  0x%02X\n",a);}
  }
  state_.imuPresent=found[cfg_.imuAddress];
  if(state_.imuPresent) {
    imu_.begin(Wire,cfg_.imuAddress==0x69);
    state_.imuPresent=imu_.status==ICM_20948_Stat_Ok;
    if(state_.imuPresent) {
      // Continuous accel/gyro; DLPF suppresses vibration. Measured rate is reported by the control task.
      bool settingsOk=imu_.setSampleMode(ICM_20948_Internal_Acc|ICM_20948_Internal_Gyr,ICM_20948_Sample_Mode_Continuous)==ICM_20948_Stat_Ok;
      ICM_20948_fss_t fss{}; fss.a=gpm4; fss.g=dps1000;
      settingsOk=(imu_.setFullScale(ICM_20948_Internal_Acc|ICM_20948_Internal_Gyr,fss)==ICM_20948_Stat_Ok) && settingsOk;
      ICM_20948_dlpcfg_t dlpf{}; dlpf.a=acc_d111bw4_n136bw; dlpf.g=gyr_d119bw5_n154bw3;
      settingsOk=(imu_.setDLPFcfg(ICM_20948_Internal_Acc|ICM_20948_Internal_Gyr,dlpf)==ICM_20948_Stat_Ok) && settingsOk;
      settingsOk=(imu_.enableDLPF(ICM_20948_Internal_Acc|ICM_20948_Internal_Gyr,true)==ICM_20948_Stat_Ok) && settingsOk;
      state_.imuPresent=settingsOk;
    }
  }
  state_.bmpPresent=found[cfg_.bmpAddress] && bmp_.begin(cfg_.bmpAddress);
  if(state_.bmpPresent) bmp_.setSampling(Adafruit_BMP280::MODE_NORMAL,Adafruit_BMP280::SAMPLING_X1,
    Adafruit_BMP280::SAMPLING_X2,Adafruit_BMP280::FILTER_X4,Adafruit_BMP280::STANDBY_MS_1);
  state_.inaPresent=found[cfg_.inaAddress] && writeRegister(0,0x399f);
  if(state_.inaPresent && cfg_.shuntOhms>0 && cfg_.currentLsbA>0) {
    const float calibration=0.04096f/(cfg_.shuntOhms*cfg_.currentLsbA);
    if(std::isfinite(calibration) && calibration>=1 && calibration<=65535) writeRegister(5,uint16_t(calibration));
  }
  CalibrationData loaded;
  if(CalibrationStore::load(loaded)) {
    bool same=true; for(int i=0;i<3;++i) if(loaded.axisMap[i]!=cfg_.axisMap[i]) same=false;
    if(same) cal_=loaded;
  }
  for(int i=0;i<3;++i) cal_.axisMap[i]=cfg_.axisMap[i];
  state_.calibrationFlags=cal_.flags;
  Serial.printf("Sensors IMU=%d BMP=%d INA=%d calibration=%lu\n",state_.imuPresent,state_.bmpPresent,state_.inaPresent,(unsigned long)cal_.flags);
  return state_.imuPresent;
}
bool SensorManager::readImu(ImuSample& out) {
  out.valid=false; out.magValid=false;
  if(!state_.imuPresent || !imu_.dataReady()) return false;
  imu_.getAGMT();
  if(imu_.status!=ICM_20948_Stat_Ok) {++state_.imuErrors;return false;}
  float ar[3]={imu_.accX()*.001f,imu_.accY()*.001f,imu_.accZ()*.001f};
  constexpr float rad=0.0174532925199433f;
  float gr[3]={imu_.gyrX()*rad,imu_.gyrY()*rad,imu_.gyrZ()*rad};
  // SparkFun AGMT presents the AK09916 axes in its native package coordinates.
  // ICM gyro/accel alignment: mag native X -> accel Y, native Y -> accel X, native Z -> -accel Z.
  float mr[3]={imu_.magY(),imu_.magX(),-imu_.magZ()};
  float a[3],g[3],m[3]; rotate(ar,a); rotate(gr,g); rotate(mr,m);
  for(int i=0;i<3;++i) {
    if(!std::isfinite(a[i]) || !std::isfinite(g[i]) || fabs(a[i])>4.1f || fabs(g[i])>18.f) {++state_.imuErrors;return false;}
    out.accelG[i]=(a[i]-cal_.accelBias[i])*cal_.accelScale[i];
    out.gyroRad[i]=g[i]-cal_.gyroBias[i];
    out.magUt[i]=(m[i]-cal_.magBias[i])*cal_.magScale[i];
  }
  // Gate magnetometer heading until explicitly calibrated; AHRS further gates magnetic magnitude.
  const float mn=norm(out.magUt);
  const bool magFresh=(imu_.agmt.magStat1&1) && !(imu_.agmt.magStat2&8);
  out.magValid=magFresh && (cal_.flags&4) && std::isfinite(mn) && mn>=15 && mn<=80;
  out.valid=true; out.microsStamp=micros();
  collect(a,g,m,magFresh && std::isfinite(norm(m)) && norm(m)>5 && norm(m)<200);
  state_.calibrationFlags=cal_.flags;
  return true;
}
void SensorManager::pollSlow(uint32_t now) {
  if(state_.bmpPresent && uint32_t(now-lastBaro_)>=40) {
    lastBaro_=now; const float p=bmp_.readPressure(), t=bmp_.readTemperature();
    state_.baroValid=std::isfinite(p) && p>30000 && p<110000 && std::isfinite(t);
    if(state_.baroValid) {
      state_.pressurePa=p;state_.temperatureC=t;state_.baroMs=now;
      if(referencePa_==0) referencePa_=p;
      const float h=44330.f*(1.f-powf(p/referencePa_,.19029496f));
      state_.relativeAltitudeM+=.12f*(h-state_.relativeAltitudeM);
    } else ++state_.i2cErrors;
  }
  if(state_.inaPresent && uint32_t(now-lastIna_)>=100) {
    lastIna_=now;uint16_t bus=0,shunt=0;
    state_.batteryValid=readRegister(2,bus) && readRegister(1,shunt);
    state_.currentValid=false;
    if(state_.batteryValid) {
      state_.busVoltageV=(bus>>3)*.004f;
      state_.shuntVoltageV=int16_t(shunt)*.00001f;
      state_.sourceVoltageV=state_.busVoltageV+state_.shuntVoltageV;
      state_.batteryValid=(bus&1)==0 && state_.sourceVoltageV>0 && state_.sourceVoltageV<=26.f;
      state_.batteryMs=now;
      if(cfg_.shuntOhms>0 && cfg_.currentLsbA>0 && state_.batteryValid && fabs(state_.shuntVoltageV)<.319f) {
        state_.currentA=state_.shuntVoltageV/cfg_.shuntOhms;
        state_.currentValid=std::isfinite(state_.currentA);
      }
    }
  }
  if(collecting_!=Calibration::None && uint32_t(now-calibrationStartMs_)>65000) {
    collecting_=Calibration::None;calibrationStatus_="failed: calibration timeout";
  }
}
bool SensorManager::calibrate(const char* target) {
  if(!target || collecting_!=Calibration::None) return false;
  if(strcmp(target,"baro")==0) {
    if(!state_.baroValid) return false;
    referencePa_=state_.pressurePa;state_.relativeAltitudeM=0;calibrationStatus_="barometer reference reset";return true;
  }
  if(strcmp(target,"save")==0) return saveCalibration();
  if(!state_.imuPresent) return false;
  if(strcmp(target,"gyro")==0) collecting_=Calibration::Gyro;
  else if(strcmp(target,"mag")==0) collecting_=Calibration::Mag;
  else {
    const char* faces[]={"accel_xp","accel_xn","accel_yp","accel_yn","accel_zp","accel_zn"};
    bool found=false;
    for(int f=0;f<6;++f) if(strcmp(target,faces[f])==0) {face_=f;found=true;}
    if(!found) return false;
    collecting_=Calibration::Accel;
  }
  sampleCount_=0;calibrationStartMs_=millis();calibrationStatus_="collecting";
  for(int i=0;i<3;++i) {sum_[i]=0;sumSq_[i]=0;magMin_[i]=10000;magMax_[i]=-10000;lastMag_[i]=10000;}
  return true;
}
void SensorManager::collect(const float* a,const float* g,const float* m,bool magValid) {
  if(collecting_==Calibration::None) return;
  if(collecting_==Calibration::Mag) {
    if(!magValid) return;
    bool changed=false;for(int i=0;i<3;++i) if(m[i]!=lastMag_[i]) changed=true;
    if(!changed) return;
    for(int i=0;i<3;++i) {magMin_[i]=std::min(magMin_[i],m[i]);magMax_[i]=std::max(magMax_[i],m[i]);lastMag_[i]=m[i];}
    ++sampleCount_;
    if(sampleCount_<1000 || uint32_t(millis()-calibrationStartMs_)<15000) return;
    float radius[3],avg=0;
    for(int i=0;i<3;++i) {radius[i]=(magMax_[i]-magMin_[i])*.5f;avg+=radius[i]/3;}
    collecting_=Calibration::None;
    for(int i=0;i<3;++i) if(radius[i]<10 || radius[i]>100 || avg/radius[i]<.25f || avg/radius[i]>4.f) {calibrationStatus_="failed: rotate through all magnetic axes; remove magnetic interference";return;}
    for(int i=0;i<3;++i) {cal_.magBias[i]=(magMax_[i]+magMin_[i])*.5f;cal_.magScale[i]=avg/radius[i];}
    cal_.flags|=4;calibrationStatus_="mag candidate ready; validate heading then save";return;
  }
  const bool stationary=norm(a)>.8f && norm(a)<1.2f && norm(g)<.12f;
  if(!stationary) {sampleCount_=0;for(int i=0;i<3;++i){sum_[i]=0;sumSq_[i]=0;}calibrationStatus_="hold stationary";return;}
  if(collecting_==Calibration::Accel) {
    const int axis=face_/2;const float sign=face_%2?-1.f:1.f;
    if(a[axis]*sign<.8f) return;
    for(int i=0;i<3;++i) if(i!=axis && fabs(a[i])>.25f) return;
  }
  const float* v=collecting_==Calibration::Gyro?g:a;
  for(int i=0;i<3;++i) {sum_[i]+=v[i];sumSq_[i]+=v[i]*v[i];}
  if(++sampleCount_<400) return;
  for(int i=0;i<3;++i) {
    const double mean=sum_[i]/sampleCount_,variance=sumSq_[i]/sampleCount_-mean*mean;
    if(variance>.0004) {collecting_=Calibration::None;calibrationStatus_="failed: excessive movement or vibration";return;}
  }
  if(collecting_==Calibration::Gyro) {
    for(int i=0;i<3;++i) cal_.gyroBias[i]=sum_[i]/sampleCount_;
    cal_.flags|=1;calibrationStatus_="gyro candidate ready; validate then save";
  } else {
    for(int i=0;i<3;++i) faces_[face_][i]=sum_[i]/sampleCount_;
    faceMask_|=1<<face_;calibrationStatus_="face captured; continue remaining faces";
    if(faceMask_==63) {
      for(int i=0;i<3;++i) {
        float span=faces_[2*i][i]-faces_[2*i+1][i];
        if(span<1.5f || span>2.5f) {collecting_=Calibration::None;calibrationStatus_="failed: six-face span";return;}
      }
      for(int i=0;i<3;++i) {cal_.accelBias[i]=(faces_[2*i][i]+faces_[2*i+1][i])*.5f;cal_.accelScale[i]=2.f/(faces_[2*i][i]-faces_[2*i+1][i]);}
      cal_.flags|=2;calibrationStatus_="accelerometer candidate ready; validate then save";
    }
  }
  collecting_=Calibration::None;
}
bool SensorManager::saveCalibration() {
  if(collecting_!=Calibration::None || !calibrationValid()) return false;
  const bool ok=CalibrationStore::save(cal_);
  calibrationStatus_=ok?"saved candidate calibration; physical acceptance still required":"failed to save calibration";
  return ok;
}
}
