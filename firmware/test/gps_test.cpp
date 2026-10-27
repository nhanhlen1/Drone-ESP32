// Actual pinned TinyGPSPlus parser + production GpsManager, mocked UART transport/time only.
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <string>
#include "../src/gps/GpsManager.h"
uint32_t fakeMs=100;FakeSerial Serial;FakeSerialConfig fakeSerialConfig;std::string fakeSerialInput;
static int checks=0;
#define CHECK(expr) do {++checks;if(!(expr)){std::fprintf(stderr,"FAIL %s:%d %s\n",__FILE__,__LINE__,#expr);std::abort();}}while(false)
static std::string nmea(const std::string& payload) {
  unsigned checksum=0;for(unsigned char c:payload)checksum^=c;
  char suffix[8];std::snprintf(suffix,sizeof(suffix),"*%02X\r\n",checksum);
  return "$"+payload+suffix;
}
static const std::string goodGga="GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,";
static const std::string goodRmc="GNRMC,123519,A,4807.038,N,01131.000,E,10.0,084.4,230394,003.1,W";
static std::string gsa(int fix){return "GNGSA,A,"+std::to_string(fix)+",04,05,09,12,24,25,29,31,,,,,1.8,1.0,1.5";}
static void feed(hw::GpsManager& gps,const std::string& payload,uint32_t advance=100) {
  fakeMs+=advance;fakeSerialInput+=nmea(payload);gps.poll(fakeMs);
}
static void good(hw::GpsManager& gps,int fix=3){
  fakeMs+=100;fakeSerialInput+=nmea(gsa(fix))+nmea(goodGga)+nmea(goodRmc);gps.poll(fakeMs);
}
int main(){
  hw::GpsManager gps;gps.begin(38400);
  CHECK(fakeSerialConfig.baud==38400);CHECK(fakeSerialConfig.rx==16);CHECK(fakeSerialConfig.tx==17);CHECK(fakeSerialConfig.rxBuffer>=512);
  gps.poll(fakeMs);CHECK(!gps.snapshot().valid);CHECK(!gps.snapshot().homeValid);CHECK(!gps.snapshot().uartActive);
  good(gps);const auto first=gps.snapshot();CHECK(first.valid);CHECK(first.fixType==3);CHECK(first.satellites==8);
  CHECK(std::fabs(first.latitude-48.1173)<1e-6);CHECK(std::fabs(first.longitude-11.5166667)<1e-6);
  CHECK(std::fabs(first.altitudeM-545.4f)<.01f);CHECK(std::fabs(first.speedMps-5.1444444f)<.001f);CHECK(std::fabs(first.hdop-.9f)<.001f);
  CHECK(first.uartActive);CHECK(!first.homeValid);
  for(int i=0;i<8;++i){good(gps);}
  CHECK(!gps.snapshot().homeValid);
  good(gps);CHECK(gps.snapshot().homeValid);CHECK(std::fabs(gps.snapshot().homeLatitude-first.latitude)<1e-8);
  gps.clearHome();CHECK(!gps.snapshot().homeValid);
  for(int i=0;i<12;++i){good(gps,2);}
  CHECK(!gps.snapshot().homeValid);CHECK(gps.snapshot().fixType==2);CHECK(gps.snapshot().valid);
  for(int i=0;i<12;++i){good(gps,1);}
  CHECK(!gps.snapshot().homeValid);CHECK(!gps.snapshot().valid);
  good(gps);CHECK(gps.snapshot().valid);fakeMs+=1600;gps.poll(fakeMs);CHECK(!gps.snapshot().valid);CHECK(gps.snapshot().uartActive);
  fakeMs+=600;gps.poll(fakeMs);CHECK(!gps.snapshot().uartActive);CHECK(!gps.snapshot().altitudeValid);CHECK(!gps.snapshot().speedValid);
  // Fresh RMC must not revive old satellites/HDOP or stale altitude needed for Home.
  feed(gps,goodRmc,100);CHECK(!gps.snapshot().valid);CHECK(!gps.snapshot().homeValid);
  for(int i=0;i<12;++i){feed(gps,goodRmc);}
  CHECK(!gps.snapshot().homeValid);
  good(gps);CHECK(gps.snapshot().valid);
  // Parser rejects corrupt checksum; repeated invalid data cannot freshen position.
  const uint32_t chars=gps.snapshot().characters,failed=gps.snapshot().checksumFailures;
  fakeMs+=1600;fakeSerialInput+="$"+goodGga+"*00\r\n";gps.poll(fakeMs);
  CHECK(!gps.snapshot().valid);CHECK(gps.snapshot().characters>chars);CHECK(gps.snapshot().checksumFailures>failed);
  // No GSA -> unknown 3D status, never invent a Home from otherwise good GGA/RMC.
  hw::GpsManager noGsa;noGsa.begin();
  for(int i=0;i<12;++i){fakeMs+=100;fakeSerialInput+=nmea(goodGga)+nmea(goodRmc);noGsa.poll(fakeMs);}
  CHECK(noGsa.snapshot().valid);CHECK(noGsa.snapshot().fixType==0);CHECK(!noGsa.snapshot().homeValid);
  // Low-quality fix may be displayed but not qualify as Home.
  hw::GpsManager weak;weak.begin();
  for(int i=0;i<12;++i){fakeMs+=100;fakeSerialInput+=nmea(gsa(3))+nmea("GNGGA,123519,4807.038,N,01131.000,E,1,04,4.0,545.4,M,46.9,M,,")+nmea(goodRmc);weak.poll(fakeMs);}
  CHECK(weak.snapshot().valid);CHECK(!weak.snapshot().homeValid);
  // GSA expires independently, even while GGA continues.
  good(weak);fakeMs+=3100;
  for(int i=0;i<12;++i){fakeMs+=100;fakeSerialInput+=nmea(goodGga)+nmea(goodRmc);weak.poll(fakeMs);}
  CHECK(weak.snapshot().valid);CHECK(weak.snapshot().fixType==0);CHECK(!weak.snapshot().homeValid);
  // Explicit receiver-invalid/estimated fixes must invalidate immediately, not await position age.
  good(gps);CHECK(gps.snapshot().valid);
  feed(gps,"GNRMC,123520,V,4807.038,N,01131.000,E,10.0,084.4,230394,003.1,W");CHECK(!gps.snapshot().valid);
  good(gps);CHECK(gps.snapshot().valid);
  feed(gps,"GNGGA,123519,4807.038,N,01131.000,E,0,08,0.9,545.4,M,46.9,M,,");CHECK(!gps.snapshot().valid);
  feed(gps,"GNGGA,123519,4807.038,N,01131.000,E,6,08,0.9,545.4,M,46.9,M,,");CHECK(!gps.snapshot().valid);
  std::printf("GPS regression scenarios passed: %d assertions (real TinyGPSPlus; UART mocked)\n",checks);
}
