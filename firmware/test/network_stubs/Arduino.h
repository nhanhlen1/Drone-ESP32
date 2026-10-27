#pragma once
#include <cstdint>
#include <cstdio>
#include <cstring>
extern uint32_t testNowMs;
inline uint32_t millis() { return testNowMs; }
struct SerialMock { void println(const char*) {} };
inline SerialMock Serial;
inline size_t strlcpy(char* dst, const char* src, size_t capacity) {
 size_t n=strlen(src); if (capacity) { size_t count=n<capacity-1?n:capacity-1; memcpy(dst,src,count);dst[count]=0; } return n;
}
