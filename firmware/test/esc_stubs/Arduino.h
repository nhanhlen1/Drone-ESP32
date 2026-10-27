#pragma once
#include <cstdint>
constexpr int INPUT=0;
inline unsigned pinCalls=0;
inline void pinMode(int,int){++pinCalls;}
