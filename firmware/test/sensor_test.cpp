// Hardware transport stubs exercise the actual sensor/calibration/storage implementation;
// they do not establish physical sensor or I2C correctness.
#include <cassert>
#include <cstdio>
#include <limits>
#include "../src/sensors/SensorManager.h"
uint32_t fakeMs=0;FakeSerial Serial;TwoWire Wire;FakeImuValues fakeImu;std::vector<uint8_t> fakeFlash;
static int checks=0;
#define CHECK(expr) do {++checks;if(!(expr)){std::fprintf(stderr,"FAIL %s:%d %s\n",__FILE__,__LINE__,#expr);std::abort();}}while(false)
static void samples(hw::SensorManager& sensor,unsigned count,uint32_t step=4) {
  hw::ImuSample s;
  for(unsigned i=0;i<count;++i){fakeMs+=step;CHECK(sensor.readImu(s));}
}
int main(){
  hw::SensorConfig cfg;hw::SensorManager sensor;
  CHECK(sensor.begin(cfg));CHECK(!sensor.calibrationValid());CHECK(!sensor.saveCalibration());
  hw::ImuSample sample;
  fakeImu.magOverflow=true;CHECK(sensor.readImu(sample));CHECK(!sample.magValid);fakeImu.magOverflow=false;
  fakeImu.ready=false;CHECK(!sensor.readImu(sample));CHECK(!sample.valid);fakeImu.ready=true;
  fakeImu.fault=true;CHECK(!sensor.readImu(sample));CHECK(sensor.snapshot().imuErrors==1);fakeImu.fault=false;
  fakeImu.g[0]=1; // one degree/s static gyro bias
  CHECK(sensor.calibrate("gyro"));CHECK(sensor.calibrating());CHECK(!sensor.calibrate("mag"));
  samples(sensor,400);CHECK(!sensor.calibrating());CHECK(sensor.snapshot().calibrationFlags&1);
  CHECK(sensor.readImu(sample));CHECK(std::fabs(sample.gyroRad[0])<1e-5f);
  const char* faces[]={"accel_xp","accel_xn","accel_yp","accel_yn","accel_zp","accel_zn"};
  for(int f=0;f<6;++f){
    for(float& a:fakeImu.a)a=0;
    fakeImu.a[f/2]=(f%2?-980.f:1020.f); // known +0.02g bias, unit sensitivity
    CHECK(sensor.calibrate(faces[f]));CHECK(!sensor.calibrationValid());samples(sensor,400);
  }
  CHECK(sensor.calibrationValid());CHECK(sensor.saveCalibration());
  CHECK(sensor.readImu(sample));CHECK(std::fabs(sample.accelG[2]+1.f)<1e-4f);
  hw::CalibrationData stored;CHECK(hw::CalibrationStore::load(stored));CHECK((stored.flags&3)==3);
  CHECK(std::fabs(stored.gyroBias[0]-.01745329252f)<1e-5f);
  CHECK(std::fabs(stored.accelBias[0]-.02f)<1e-4f);
  fakeFlash[10]^=1;hw::CalibrationData damaged;CHECK(!hw::CalibrationStore::load(damaged));
  CHECK(sensor.calibrate("mag"));
  for(unsigned i=0;i<1200;++i){
    fakeImu.m[0]=40.f*std::sin(i*.051f)+5;fakeImu.m[1]=35.f*std::sin(i*.037f)-7;fakeImu.m[2]=45.f*std::sin(i*.023f)+3;
    fakeMs+=16;CHECK(sensor.readImu(sample));
  }
  CHECK(!sensor.calibrating());CHECK(sensor.snapshot().calibrationFlags&4);CHECK(sensor.saveCalibration());
  fakeImu.m[0]=0;fakeImu.m[1]=30;fakeImu.m[2]=30;
  fakeImu.magReady=false;CHECK(sensor.readImu(sample));CHECK(!sample.magValid);
  fakeImu.magReady=true;fakeImu.magOverflow=true;CHECK(sensor.readImu(sample));CHECK(!sample.magValid);fakeImu.magOverflow=false;
  CHECK(sensor.calibrate("gyro"));CHECK(!sensor.calibrationValid());fakeMs+=66000;sensor.pollSlow(fakeMs);CHECK(!sensor.calibrating());
  // Unverified shunt must never produce a trustworthy current, even with a valid bus.
  Wire.registers[2]=uint16_t(3000<<3);Wire.registers[1]=100;fakeMs+=100;sensor.pollSlow(fakeMs);
  CHECK(sensor.snapshot().batteryValid);CHECK(!sensor.snapshot().currentValid);CHECK(std::fabs(sensor.snapshot().busVoltageV-12.f)<1e-5f);
  cfg.shuntOhms=.01f;cfg.currentLsbA=.001f;hw::SensorManager power;CHECK(power.begin(cfg));fakeMs+=100;power.pollSlow(fakeMs);
  CHECK(power.snapshot().currentValid);CHECK(std::fabs(power.snapshot().currentA-.1f)<1e-5f);CHECK(std::fabs(power.snapshot().sourceVoltageV-12.001f)<1e-5f);
  Wire.registers[2]|=1;fakeMs+=100;power.pollSlow(fakeMs);CHECK(!power.snapshot().batteryValid);CHECK(!power.snapshot().currentValid);
  Wire.fail=true;fakeMs+=100;power.pollSlow(fakeMs);CHECK(!power.snapshot().batteryValid);CHECK(power.snapshot().i2cErrors>0);Wire.fail=false;
  cfg.axisMap[0]=-1;hw::SensorManager badMap;CHECK(!badMap.begin(cfg)); // reflection is not a body rotation
  cfg.axisMap[0]=2;cfg.axisMap[1]=-1;cfg.axisMap[2]=3;hw::SensorManager rotation;CHECK(rotation.begin(cfg));
  CHECK(!rotation.calibrationValid()); // saved calibration belongs to a different mounting
  cfg.imuAddress=0x42;hw::SensorManager badAddress;CHECK(!badAddress.begin(cfg));
  std::printf("Sensor/storage regression checks passed: %d (hardware transport mocked)\n",checks);
}
