#pragma once
#include <functional>
constexpr int HTTP_GET=0;
class WebServer {
 public: explicit WebServer(int) {} void on(const char*,int,std::function<void()>) {}
 void begin() {} void handleClient() {} void send(int,const char*,const char*) {}
};
