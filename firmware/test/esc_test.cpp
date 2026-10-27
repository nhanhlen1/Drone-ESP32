#include "motors/EscDriver.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <limits>
int main(){
 drone::EscDriver esc;esc.begin();assert(!esc.realEnabled());assert(pinCalls==4);
 for(float v:esc.output())assert(v==0);
 esc.write({1,1,1,1},.004f,true);
 for(float v:esc.output())assert(v>0&&v<=config::THROTTLE_SLEW_PER_SEC*.0041f);
 for(int i=0;i<1000;++i)esc.write({1,1,1,1},.004f,true);
 for(float v:esc.output())assert(v<=config::THROTTLE_LIMIT);
 esc.write({1,1,1,1},.004f,false);for(float v:esc.output())assert(v==0);
 esc.write({std::numeric_limits<float>::quiet_NaN(),1,1,1},.004f,true);for(float v:esc.output())assert(v==0);
 esc.write({1,1,1,1},1,true);for(float v:esc.output())assert(v==0);
 assert(pinCalls==4); // Mock write/cutoff cannot attach or drive any PWM channel.
 puts("ESC mock behavior PASS: boot, slew, cap, cutoff, invalid data, no GPIO output");
}
