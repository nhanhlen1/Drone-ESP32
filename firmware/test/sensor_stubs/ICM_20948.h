#pragma once
#include "Wire.h"
enum {ICM_20948_Stat_Ok=0,ICM_20948_Internal_Acc=1,ICM_20948_Internal_Gyr=2,ICM_20948_Sample_Mode_Continuous=0,gpm4=0,dps1000=0,acc_d111bw4_n136bw=0,gyr_d119bw5_n154bw3=0};
struct ICM_20948_fss_t{int a=0,g=0;};struct ICM_20948_dlpcfg_t{int a=0,g=0;};
struct FakeImuValues {float a[3]={0,0,1000},g[3]={},m[3]={30,0,30};bool ready=true,fault=false,magReady=true,magOverflow=false;};
extern FakeImuValues fakeImu;
struct ICM_20948_I2C {
  int status=0;struct {uint8_t magStat1=1,magStat2=0;}agmt;
  int begin(TwoWire&,bool){return status=fakeImu.fault?1:0;}
  int setSampleMode(int,int){return status=fakeImu.fault?1:0;}
  int setFullScale(int,ICM_20948_fss_t){return status=fakeImu.fault?1:0;}
  int setDLPFcfg(int,ICM_20948_dlpcfg_t){return status=fakeImu.fault?1:0;}
  int enableDLPF(int,bool){return status=fakeImu.fault?1:0;}
  bool dataReady(){return fakeImu.ready;}
  void getAGMT(){status=fakeImu.fault?1:0;agmt.magStat1=fakeImu.magReady?1:0;agmt.magStat2=fakeImu.magOverflow?8:0;}
  float accX(){return fakeImu.a[0];}float accY(){return fakeImu.a[1];}float accZ(){return fakeImu.a[2];}
  float gyrX(){return fakeImu.g[0];}float gyrY(){return fakeImu.g[1];}float gyrZ(){return fakeImu.g[2];}
  float magX(){return fakeImu.m[0];}float magY(){return fakeImu.m[1];}float magZ(){return fakeImu.m[2];}
};
