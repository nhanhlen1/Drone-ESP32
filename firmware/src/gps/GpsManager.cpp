#include "GpsManager.h"
#include <cmath>
#include <cstdlib>
namespace hw {
void GpsManager::begin(uint32_t baud) {
  serial_.setRxBufferSize(2048);serial_.begin(baud,SERIAL_8N1,16,17);
}
void GpsManager::poll(uint32_t now) {
  unsigned budget=512;
  while(budget-- && serial_.available()) {parser_.encode(char(serial_.read()));lastByteMs_=now;}
  state_.characters=parser_.charsProcessed();state_.checksumFailures=parser_.failedChecksum();
  state_.uartActive=state_.characters>0 && uint32_t(now-lastByteMs_)<2000;
  if(gnGsa_.isUpdated()) {state_.fixType=atoi(gnGsa_.value());lastGsaMs_=now;}
  if(gpGsa_.isUpdated()) {state_.fixType=atoi(gpGsa_.value());lastGsaMs_=now;}
  if(uint32_t(now-lastGsaMs_)>3000 || state_.fixType>3) state_.fixType=0;
  if(gpQuality_.isUpdated()) quality_=atoi(gpQuality_.value());
  if(gnQuality_.isUpdated()) quality_=atoi(gnQuality_.value());
  if(gpStatus_.isUpdated()) rmcReject_=gpStatus_.value()[0]!='A';
  if(gnStatus_.isUpdated()) rmcReject_=gnStatus_.value()[0]!='A';
  const bool newLocation=parser_.location.isUpdated();
  const double lat=parser_.location.lat(),lon=parser_.location.lng();
  state_.altitudeValid=parser_.altitude.isValid() && parser_.altitude.age()<2000;
  state_.speedValid=parser_.speed.isValid() && parser_.speed.age()<2000;
  state_.valid=state_.fixType!=1 && quality_>=1 && quality_<=5 && !rmcReject_ &&
    std::isfinite(lat) && std::isfinite(lon) && fabs(lat)<=90 && fabs(lon)<=180 && parser_.location.isValid() && parser_.location.age()<1500 &&
    parser_.satellites.isValid() && parser_.satellites.age()<2000 && parser_.satellites.value()>=4 &&
    parser_.hdop.isValid() && parser_.hdop.age()<2000 && parser_.hdop.hdop()<=5;
  if(state_.valid) {
    state_.latitude=lat;state_.longitude=lon;
    state_.lastFixMs=now-parser_.location.age();
    state_.satellites=parser_.satellites.value();state_.hdop=parser_.hdop.hdop();
    if(state_.altitudeValid) state_.altitudeM=parser_.altitude.meters();
    if(state_.speedValid) state_.speedMps=parser_.speed.mps();
  } else stableFixes_=0;
  const bool homeCandidate=state_.valid && state_.fixType==3 && state_.satellites>=6 && state_.hdop<=2 &&
    parser_.altitude.isValid() && parser_.altitude.age()<1500 && std::isfinite(state_.latitude) && std::isfinite(state_.longitude);
  if(newLocation && homeCandidate && !state_.homeValid) {
    if(++stableFixes_>=10) {
      state_.homeLatitude=state_.latitude;state_.homeLongitude=state_.longitude;state_.homeValid=true;
    }
  } else if(!homeCandidate) stableFixes_=0;
}
}
