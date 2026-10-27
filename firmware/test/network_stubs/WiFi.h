#pragma once
constexpr int WIFI_AP=0;
struct WiFiMock { void mode(int) {} void setSleep(bool) {} bool softAP(const char*,const char*,int,bool,int) {return true;} };
inline WiFiMock WiFi;
