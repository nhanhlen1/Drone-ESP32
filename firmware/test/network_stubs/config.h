#pragma once
// Public test-only configuration. Production local_secrets.h is never read here.
namespace config { constexpr char SSID[]="HOST_TEST"; constexpr char VERSION[]="test"; }
#define DRONE_AP_PASSWORD "test-only-password"
#define DRONE_SESSION_TOKEN "01234567890123456789012345678901"
