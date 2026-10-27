#include <WiFiClientSecure.h>
#include "Network.h"
#include "config.h"
#include <WiFi.h>
#include <esp_system.h>
#include <cmath>
#include <cstring>

namespace drone {
namespace {
bool secureEqual(const char* a, const char* b) {
  if (!a || !b) return false;
  const size_t length = strlen(b);
  if (strlen(a) != length) return false;
  uint8_t difference = 0;
  for (size_t i=0; i<length; ++i) difference |= static_cast<uint8_t>(a[i]^b[i]);
  return difference == 0;
}
bool number(JsonVariantConst value, float minimum, float maximum, float& output) {
  if (value.is<bool>() || !value.is<float>()) return false;
  output = value.as<float>();
  return std::isfinite(output) && output >= minimum && output <= maximum;
}
}

bool Network::begin() {
  // No shared/default password, open AP, or compiled example token.
  if (strlen(DRONE_AP_PASSWORD)<8 || strlen(DRONE_AP_PASSWORD)>63 ||
      strlen(DRONE_SESSION_TOKEN)<32) {
    Serial.println("[NET] disabled: provision local AP password (8..63), token (>=32)");
    return false;
  }
  queue_ = xQueueCreate(16, sizeof(CommandMessage));
  if (!queue_) return false;
  WiFi.mode(WIFI_AP);
  WiFi.setSleep(false);
  if (!WiFi.softAP(config::SSID, DRONE_AP_PASSWORD, 1, false, 2)) {
    vQueueDelete(queue_); queue_=nullptr; return false;
  }
  socket_.begin();
  socket_.onEvent([this](uint8_t client, WStype_t type, uint8_t* payload, size_t length) {
    event(client,type,payload,length);
  });
  // No command surface on HTTP. Used for a minimal connectivity check.
  http_.on("/", HTTP_GET, [this] {
    char status[192];
    snprintf(status,sizeof(status),"{\"firmware\":\"%s\",\"websocket\":\"ws://192.168.4.1:81/ws\"}",config::VERSION);
    http_.send(200,"application/json",status);
  });
  http_.begin();
  available_=true;
  Serial.println("[NET] AP ready; WebSocket :81/ws; plain LAN transport, bench only");
  return true;
}
void Network::poll(bool armed) {
  armed_=armed;
  if (!available_) return;
  if (owner_>=0 && static_cast<uint32_t>(millis()-lastLeaseMs_)>500) revoke();
  socket_.loop();
  http_.handleClient();
}
bool Network::authenticated() const {
  return available_ && owner_>=0 && static_cast<uint32_t>(millis()-lastLeaseMs_)<=500;
}
void Network::revoke() {
  const int old=owner_;
  owner_=-1; session_[0]='\0'; hasSequence_=false; ++generation_;
  if (queue_) xQueueReset(queue_);
  if (old>=0) reply(static_cast<uint8_t>(old),"error","session_expired");
}
void Network::reply(uint8_t client, const char* type, const char* reason) {
  StaticJsonDocument<160> response;
  response["v"]=1; response["type"]=type; response["reason"]=reason;
  response["now_ms"]=millis();
  char output[192]; const size_t length=serializeJson(response,output,sizeof(output));
  socket_.sendTXT(client,output,length);
}
void Network::event(uint8_t client, WStype_t type, uint8_t* payload, size_t length) {
  if (client>=kClients) { socket_.disconnect(client); return; }
  if (type==WStype_CONNECTED) {
    // WStype_CONNECTED contains the HTTP upgrade URL. Never accept arbitrary paths.
    if (length!=3 || memcmp(payload,"/ws",3)!=0) { socket_.disconnect(client); return; }
    windows_[client]=millis(); counts_[client]=0;
    reply(client,"hello","authentication_required");
  } else if (type==WStype_DISCONNECTED) {
    if (owner_==client) revoke();
  } else if (type==WStype_TEXT) {
    message(client,payload,length);
  } else if (type==WStype_BIN || type==WStype_FRAGMENT_TEXT_START ||
             type==WStype_FRAGMENT_BIN_START || type==WStype_FRAGMENT) {
    ++rejected_; reply(client,"error","fragments_and_binary_unsupported");
  }
}
bool Network::enqueue(const CommandMessage& command, bool urgent) {
  if (!queue_) return false;
  if (urgent) {
    // Stop messages must not wait behind queued stick inputs.
    if (uxQueueSpacesAvailable(queue_)==0) xQueueReset(queue_);
    return xQueueSendToFront(queue_,&command,0)==pdTRUE;
  }
  return xQueueSend(queue_,&command,0)==pdTRUE;
}
bool Network::receive(CommandMessage& command) {
  return queue_ && xQueueReceive(queue_,&command,0)==pdTRUE;
}
void Network::sendTelemetry(const char* json) {
  if (authenticated() && json) socket_.sendTXT(static_cast<uint8_t>(owner_),json);
}
void Network::message(uint8_t client, uint8_t* payload, size_t length) {
  const uint32_t now=millis();
  if (static_cast<uint32_t>(now-windows_[client])>=1000) {
    windows_[client]=now; counts_[client]=0;
  }
  if (++counts_[client]>100) { ++rejected_; return; }
  auto reject=[&](const char* reason) { ++rejected_; reply(client,"error",reason); };
  if (length==0 || length>1400) { reject("message_size"); return; }
  document_.clear();
  auto error=deserializeJson(document_,payload,length,DeserializationOption::NestingLimit(6));
  if (error || !document_.is<JsonObject>() || !document_["v"].is<uint32_t>() ||
      document_["v"].as<uint32_t>()!=1 || !document_["type"].is<const char*>()) {
    reject("invalid_envelope"); return;
  }
  const char* type=document_["type"];
  if (strcmp(type,"auth")==0) {
    if (armed_) { reject("auth_locked_while_armed"); return; }
    if (!document_["token"].is<const char*>() ||
        !secureEqual(document_["token"].as<const char*>(),DRONE_SESSION_TOKEN)) {
      reject("authentication_failed"); return;
    }
    if (owner_>=0 && owner_!=client) socket_.disconnect(static_cast<uint8_t>(owner_));
    revoke();
    owner_=client; lastLeaseMs_=now; hasSequence_=false;
    snprintf(session_,sizeof(session_),"%08lx%08lx%08lx%08lx",
       static_cast<unsigned long>(esp_random()),static_cast<unsigned long>(esp_random()),
       static_cast<unsigned long>(esp_random()),static_cast<unsigned long>(esp_random()));
    StaticJsonDocument<192> response;
    response["v"]=1; response["type"]="auth"; response["session"]=session_; response["now_ms"]=now;
    char output[224]; size_t size=serializeJson(response,output,sizeof(output));
    socket_.sendTXT(client,output,size);
    return;
  }
  if (!authenticated() || owner_!=client || !document_["session"].is<const char*>() ||
      !secureEqual(document_["session"].as<const char*>(),session_)) {
    reject("session_invalid"); return;
  }
  if (!document_["seq"].is<uint32_t>() || !document_["sent_ms"].is<uint32_t>()) {
    reject("sequence_or_timestamp"); return;
  }
  const uint32_t sequence=document_["seq"].as<uint32_t>();
  const int32_t age=static_cast<int32_t>(now-document_["sent_ms"].as<uint32_t>());
  if ((hasSequence_ && sequence<=lastSequence_) || age<0 || age>250) {
    reject("stale_or_out_of_order"); return;
  }
  CommandMessage message{};
  message.clientId=client; message.receivedMs=now; message.sessionGeneration=generation_;
  bool renewLease=false, urgent=false;
  if (strcmp(type,"control")==0) {
    message.type=CommandType::Control;
    const char* mode=document_["mode"].is<const char*>()?document_["mode"].as<const char*>():"";
    if (!number(document_["throttle"],0,1,message.throttle) ||
        !number(document_["roll"],-1,1,message.roll) ||
        !number(document_["pitch"],-1,1,message.pitch) ||
        !number(document_["yaw"],-1,1,message.yaw) ||
        (strcmp(mode,"rate")!=0 && strcmp(mode,"angle")!=0)) {
      reject("control_range_or_type"); return;
    }
    message.angleMode=strcmp(mode,"angle")==0; renewLease=true;
  } else if (strcmp(type,"heartbeat")==0) {
    message.type=CommandType::Heartbeat; renewLease=true;
  } else if (strcmp(type,"arm")==0) {
    if (!document_["confirm"].is<bool>() || !document_["confirm"].as<bool>()) {
      reject("arm_confirmation_required"); return;
    }
    message.type=CommandType::Arm;
  } else if (strcmp(type,"disarm")==0) {
    message.type=CommandType::Disarm; urgent=true;
  } else if (strcmp(type,"estop")==0) {
    message.type=CommandType::Estop; urgent=true;
    // An accepted stop survives queue resets, session replacement and lease expiry.
    // Only a device reboot clears this latch; control must consume it each snapshot.
    emergencyAccepted_=true;
  } else if (strcmp(type,"telemetry")==0) {
    message.type=CommandType::Telemetry;
  } else if (strcmp(type,"pid")==0) {
    if (armed_) { reject("configuration_requires_disarmed"); return; }
    message.type=CommandType::Pid;
    constexpr const char* axes[]={"roll","pitch","yaw","attitude"};
    constexpr const char* terms[]={"p","i","d"};
    for (int a=0;a<4;++a) for (int t=0;t<3;++t) {
      if (!number(document_["gains"][axes[a]][terms[t]],0,t==2?10:20,message.gains[a][t])) {
        reject("pid_range_or_type"); return;
      }
    }
  } else if (strcmp(type,"calibrate")==0) {
    if (armed_) { reject("calibration_requires_disarmed"); return; }
    message.type=CommandType::Calibrate;
    const char* target=document_["target"].is<const char*>()?document_["target"].as<const char*>():"";
    constexpr const char* targets[]={"gyro","accel_xp","accel_xn","accel_yp","accel_yn",
                                    "accel_zp","accel_zn","mag","baro","save"};
    bool valid=false;
    for (const char* candidate:targets) if (strcmp(candidate,target)==0) valid=true;
    if (!valid) { reject("calibration_target"); return; }
    strlcpy(message.target,target,sizeof(message.target));
  } else { reject("unsupported_type"); return; }
  if (!enqueue(message,urgent)) { reject("queue_full"); return; }
  if (hasSequence_ && sequence>lastSequence_+1) packetLoss_+=sequence-lastSequence_-1;
  hasSequence_=true; lastSequence_=sequence; latencyMs_=static_cast<uint32_t>(age);
  if (renewLease) lastLeaseMs_=now;
  reply(client,"ack",type);
}
}
