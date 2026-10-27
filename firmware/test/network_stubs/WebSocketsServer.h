#pragma once
#include <cstdint>
#include <string>
#include <functional>
#include <vector>
enum WStype_t { WStype_ERROR, WStype_DISCONNECTED, WStype_CONNECTED, WStype_TEXT, WStype_BIN,
 WStype_FRAGMENT_TEXT_START,WStype_FRAGMENT_BIN_START,WStype_FRAGMENT,WStype_FRAGMENT_FIN,WStype_PING,WStype_PONG };
class WebSocketsServer {
 public:
  inline static WebSocketsServer* instance=nullptr;
  std::vector<std::string> sent;
  explicit WebSocketsServer(int) {instance=this;}
  void begin() {}
  void loop() {}
  void onEvent(std::function<void(uint8_t,WStype_t,uint8_t*,size_t)> f) { callback=f; }
  bool sendTXT(uint8_t,const char* data,size_t length=0) {sent.emplace_back(data,length?length:strlen(data));return true;}
  void disconnect(uint8_t client) {if(callback)callback(client,WStype_DISCONNECTED,nullptr,0);}
  void emit(uint8_t client,WStype_t type,const std::string& payload) {
    std::vector<uint8_t> buffer(payload.begin(),payload.end());buffer.push_back(0);
    callback(client,type,buffer.data(),payload.size());
  }
 private: std::function<void(uint8_t,WStype_t,uint8_t*,size_t)> callback;
};
