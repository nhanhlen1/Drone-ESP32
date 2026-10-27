#include "network/Network.h"
#include <cassert>
#include <iostream>
#include <string>
uint32_t testNowMs=1000;
namespace {
int checks=0;
void check(bool condition) { ++checks; assert(condition); }
std::string session;
void send(const std::string& body,uint8_t client=0) { WebSocketsServer::instance->emit(client,WStype_TEXT,body); }
std::string packet(const std::string& type,uint32_t seq,uint32_t stamp=1000,const std::string& fields="") {
 return "{\"v\":1,\"type\":\""+type+"\",\"session\":\""+session+"\",\"seq\":"+std::to_string(seq)+",\"sent_ms\":"+std::to_string(stamp)+fields+"}";
}
}
int main() {
 drone::Network net;
 check(net.begin());check(!net.emergencyAccepted());net.poll(false);
 WebSocketsServer::instance->emit(0,WStype_CONNECTED,"/ws");
 send("{\"v\":1,\"type\":\"auth\",\"token\":\"bad\"}"); check(!net.authenticated());
 send("{\"v\":1,\"type\":\"auth\",\"token\":\"01234567890123456789012345678901\"}");
 check(net.authenticated());
 StaticJsonDocument<256> doc; check(!deserializeJson(doc,WebSocketsServer::instance->sent.back()));
 session=doc["session"].as<std::string>();check(session.size()==32);
 drone::CommandMessage out;
 send(packet("control",1,1000,",\"throttle\":0.1,\"roll\":0,\"pitch\":0,\"yaw\":0,\"mode\":\"rate\""));
 check(net.receive(out));check(out.type==drone::CommandType::Control && out.throttle==0.1f);
 check(out.sessionGeneration==net.sessionGeneration());
 send(packet("heartbeat",1));check(!net.receive(out)); // replay
 send(packet("control",2,1000,",\"throttle\":1.01,\"roll\":0,\"pitch\":0,\"yaw\":0,\"mode\":\"rate\""));
 check(!net.receive(out));
 send(packet("control",2,1000,",\"throttle\":true,\"roll\":0,\"pitch\":0,\"yaw\":0,\"mode\":\"rate\""));
 check(!net.receive(out));
 send(packet("heartbeat",2,1001));check(!net.receive(out)); // future
 send(packet("heartbeat",2,749));check(!net.receive(out)); // 251ms old
 send(packet("arm",2));check(!net.receive(out));
 send(packet("arm",2,1000,",\"confirm\":true"));check(net.receive(out));check(out.type==drone::CommandType::Arm);
 send(packet("heartbeat",5));check(net.receive(out));check(net.packetLoss()==2);
 send(packet("heartbeat",6),1);check(!net.receive(out)); // authenticated session bound client
 net.poll(true);
 send("{\"v\":1,\"type\":\"auth\",\"token\":\"01234567890123456789012345678901\"}");
 check(net.authenticated()); // reauth fails but preserves current owner
 send(packet("calibrate",6,1000,",\"target\":\"gyro\""));check(!net.receive(out));
 net.poll(false);
 send(packet("calibrate",6,1000,",\"target\":\"gyro\""));check(net.receive(out));check(std::string(out.target)=="gyro");
 for(uint32_t seq=7;seq<23;++seq)send(packet("heartbeat",seq)); // fill queue
 send(packet("estop",23)); check(net.receive(out));check(out.type==drone::CommandType::Estop);check(net.emergencyAccepted());
 check(!net.receive(out)); // urgent event reset full queue, no stale controls after stop
 const uint32_t originalGeneration=net.sessionGeneration();
 testNowMs=1501;net.poll(false);check(!net.authenticated());check(net.sessionGeneration()!=originalGeneration);check(net.emergencyAccepted());
 send(packet("heartbeat",24,1501));check(!net.receive(out));
 check(net.rejected()>=11);
 testNowMs=2000; net.poll(false);
 send("{\"v\":1,\"type\":\"auth\",\"token\":\"01234567890123456789012345678901\"}");
 check(net.authenticated());
 check(!deserializeJson(doc,WebSocketsServer::instance->sent.back()));session=doc["session"].as<std::string>();
 int accepted=0;
 for(uint32_t seq=1;seq<=120;++seq){send(packet("heartbeat",seq,2000));if(net.receive(out))++accepted;}
 check(net.emergencyAccepted());
 check(accepted==99); // auth consumes one of 100 messages/window
 WebSocketsServer::instance->disconnect(0);check(!net.authenticated());check(!net.receive(out));check(net.emergencyAccepted());
 // Regression: accepted stop erased by re-auth queue reset before control dequeues it.
 testNowMs=3000;
 drone::Network second;
 check(second.begin());second.poll(false);
 WebSocketsServer::instance->emit(0,WStype_CONNECTED,"/ws");
 send("{\"v\":1,\"type\":\"auth\",\"token\":\"01234567890123456789012345678901\"}");
 check(!deserializeJson(doc,WebSocketsServer::instance->sent.back()));session=doc["session"].as<std::string>();
 send(packet("estop",1,3000));check(second.emergencyAccepted());
 send("{\"v\":1,\"type\":\"auth\",\"token\":\"01234567890123456789012345678901\"}");
 check(!second.receive(out));check(second.emergencyAccepted());
 std::cout<<checks<<" network assertions passed (real JSON/authorization logic; fake transport HAL)\n";
}
