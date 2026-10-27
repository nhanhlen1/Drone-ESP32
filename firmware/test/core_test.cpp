#include "drone_core.h"
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
using namespace drone;
static unsigned assertions = 0, groups = 0;
static void check(bool ok, const char *expression, int line) {
  assertions++;
  if (!ok)
    throw std::runtime_error(std::string("line ") + std::to_string(line) + ": " + expression);
}
#define CHECK(x) check(bool(x), #x, __LINE__)
static bool near(float a, float b, float tolerance = 1e-4f) { return std::abs(a - b) < tolerance; }
static void test(const char *name, void (*fn)()) {
  fn();
  groups++;
  std::cout << "PASS " << name << '\n';
}
static std::array<MotorGeometry, 4> geometry() {
  return {{{1, -1, 1}, {-1, -1, -1}, {-1, 1, 1}, {1, 1, -1}}};
}
static Checks ready() {
  Checks c;
  c.imu = c.calibrated = c.authenticated = c.hardware = c.battery = c.timing =
      c.explicitArmConfirmation = true;
  return c;
}
static void rotation() {
  AxisRotation r;
  CHECK(!r.configure({{1, 2, -3}}));
  CHECK(!r.configure({{1, 1, 3}}));
  CHECK(!r.configure({{0, 2, 3}}));
  CHECK(r.configure({{-2, 1, 3}}));
  Vec3 v = r.apply({1, 2, 3});
  CHECK(v.x == -2 && v.y == 1 && v.z == 3);
}
static void attitude_static() {
  Ahrs a;
  float tilt = .3f;
  Vec3 gravity{0, std::sin(tilt), std::cos(tilt)};
  for (int i = 0; i < 2500; i++)
    CHECK(a.update({}, gravity, {}, .004f));
  CHECK(a.healthy());
  CHECK(near(a.attitude().roll, tilt, .002f));
  CHECK(near(a.attitude().pitch, 0, .001f));
  Quaternion q = a.quaternion();
  CHECK(near(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z, 1));
  // Linear acceleration is rejected rather than interpreted as tilt.
  float before = a.attitude().roll;
  for (int i = 0; i < 100; i++)
    CHECK(a.update({}, {4, 0, 1}, {}, .004f));
  CHECK(near(before, a.attitude().roll, .001f));
  for (int i = 0; i < 30; i++) {
    CHECK(a.update({}, {4, 0, 1}, {}, .004f));
  }
  CHECK(!a.healthy());
  Ahrs inverted;
  for (int i = 0; i < 1000; i++)
    inverted.update({}, {0, 0, -1}, {}, .004f);
  CHECK(!inverted.healthy());
}
static void attitude_gyro() {
  Ahrs a;
  for (int i = 0; i < 250; i++)
    CHECK(a.update({0, 0, 1}, {0, 0, 1}, {}, .004f));
  CHECK(near(a.attitude().yaw, 1, .002f));
  CHECK(near(a.attitude().roll, 0));
  CHECK(near(a.attitude().pitch, 0));
  CHECK(!a.update({NAN, 0, 0}, {0, 0, 1}, {}, .004f));
  CHECK(!a.healthy());
  CHECK(!a.update({}, {0, 0, 1}, {}, 0));
  CHECK(!a.update({}, {0, 0, 1}, {}, .1f));
}
static void attitude_mag() {
  Ahrs a;
  for (int i = 0; i < 3000; i++)
    CHECK(a.update({0, 0, .05f}, {0, 0, 1}, {30, 0, 0}, .004f, true));
  CHECK(std::abs(a.attitude().yaw) < .06f);
  float before = a.attitude().yaw;
  for (int i = 0; i < 250; i++)
    a.update({0, 0, .05f}, {0, 0, 1}, {-1000, 0, 0}, .004f, true);
  CHECK(near(a.attitude().yaw - before, .05f, .001f));
}
static void pid_windup() {
  Pid p(Gains(2, 1, .01f, .3f, .2f));
  for (int i = 0; i < 5000; i++)
    CHECK(near(p.step(100, 0, .004f), .3f));
  CHECK(near(p.integral(), 0));
  CHECK(near(p.step(0, 0, .004f), 0));
  CHECK(p.step(NAN, 0, .004f) == 0);
  CHECK(p.step(0, 0, -1) == 0);
  CHECK(p.step(0, 0, 1e-30f) == 0);
  CHECK(p.step(std::numeric_limits<float>::max(), -std::numeric_limits<float>::max(), .004f) == 0);
  CHECK(!p.configure(Gains(-1, 1, 1)));
  p.reset();
  CHECK(p.integral() == 0);
}
static void pid_plant() {
  Pid p(Gains(.3f, .2f, .01f, .7f, .5f));
  float rate = 0;
  for (int i = 0; i < 5000; i++) {
    float u = p.step(1, rate, .004f);
    CHECK(std::isfinite(u) && std::abs(u) <= .7f);
    rate += (10 * u - .4f * rate) * .004f;
  }
  CHECK(near(rate, 1, .01f));
  // D is on measurement, so a setpoint jump alone does not create a kick.
  Pid d(Gains(0, 0, 1, 1, 0));
  CHECK(d.step(0, 0, .004f) == 0);
  CHECK(d.step(1, 0, .004f) == 0);
  CHECK(d.step(1, .1f, .004f) < 0);
}
static void cascaded() {
  CascadedController c;
  Command cmd;
  cmd.throttle = .3f;
  cmd.roll = .5f;
  Vec3 output = c.step(cmd, {}, {}, .004f, true);
  CHECK(output.x > 0 && output.y == 0 && output.z == 0);
  c.reset();
  cmd.roll = 0;
  Euler a;
  a.pitch = .2f;
  output = c.step(cmd, a, {}, .004f, true);
  CHECK(output.y < 0);
  c.reset();
  cmd.yaw = -1;
  output = c.step(cmd, {}, {}, .004f, false);
  CHECK(output.z < 0);
  cmd.throttle = NAN;
  output = c.step(cmd, {}, {}, .004f, false);
  CHECK(output.norm() == 0);
}
static void cascaded_attitude_integral() {
  CascadedController c;
  // Disable outer P/D and inner I/D: changing correction must come from the
  // configured outer integral, rather than a hidden inner-loop contribution.
  Gains outer = c.attitude[0].gains();
  outer.kp = 0;
  outer.ki = 1;
  outer.kd = 0;
  CHECK(c.attitude[0].configure(outer));
  CHECK(c.rate[0].configure(Gains(1, 0, 0, 1, 0)));
  Command cmd;
  cmd.throttle = .3f;
  cmd.roll = .5f;
  const float first = c.step(cmd, {}, {}, .004f, true).x;
  CHECK(first > 0);
  float output = first;
  for (int i = 0; i < 2000; ++i) {
    output = c.step(cmd, {}, {}, .004f, true).x;
    CHECK(std::isfinite(output) && output > 0 && output <= .5001f);
  }
  CHECK(output > .49f);
  CHECK(near(c.attitude[0].integral(), .5f));
  c.reset();
  CHECK(c.attitude[0].integral() == 0);
  CHECK(near(c.step(cmd, {}, {}, .004f, true).x, first));
  c.reset();
  cmd.roll = 0;
  Euler tilted;
  tilted.roll = .2f;
  CHECK(c.step(cmd, tilted, {}, .004f, true).x < 0);
}
static void mixer() {
  Mixer m;
  CHECK(!m.valid());
  CHECK(m.mix(.5f, {1, 1, 1})[0] == 0);
  auto g = geometry();
  CHECK(m.configure(g));
  auto out = m.mix(.5f, {});
  for (float v : out)
    CHECK(near(v, .5f));
  for (int axis = 0; axis < 3; axis++) {
    Vec3 torque = axis == 0 ? Vec3{.1f, 0, 0} : (axis == 1 ? Vec3{0, .1f, 0} : Vec3{0, 0, .1f});
    out = m.mix(.5f, torque);
    Vec3 actual;
    for (int i = 0; i < 4; i++) {
      actual.x += out[i] * g[i].y;
      actual.y -= out[i] * g[i].x;
      actual.z += out[i] * g[i].yawSign;
    }
    CHECK(near(actual.x, axis == 0 ? .4f : 0));
    CHECK(near(actual.y, axis == 1 ? .4f : 0));
    CHECK(near(actual.z, axis == 2 ? .4f : 0));
  }
  auto reversed = g;
  std::reverse(reversed.begin(), reversed.end());
  Mixer reordered;
  CHECK(reordered.configure(reversed));
  auto a = m.mix(.5f, {.1f, -.2f, .04f}), b = reordered.mix(.5f, {.1f, -.2f, .04f});
  for (int i = 0; i < 4; i++)
    CHECK(near(a[i], b[3 - i]));
  for (float throttle : {0.f, .1f, .5f, .9f, 1.f}) {
    out = m.mix(throttle, {2, -1, 3});
    for (float v : out)
      CHECK(std::isfinite(v) && v >= 0 && v <= 1);
  }
  auto bad = g;
  bad[0] = bad[1];
  CHECK(!m.configure(bad));
  bad = g;
  bad[0].yawSign = -1;
  CHECK(!m.configure(bad));
  CHECK(m.mix(NAN, {})[0] == 0);
  CHECK(m.mix(.5f, {NAN, 0, 0})[0] == 0);
}
static void state_machine() {
  StateMachine s;
  CHECK(s.state() == FlightState::BOOT);
  CHECK(!s.requestArm(ready(), 0));
  s.beginSelfTest();
  CHECK(s.state() == FlightState::SELF_TEST);
  s.finishSelfTest(true);
  CHECK(s.state() == FlightState::DISARMED);
  CHECK(!s.requestArm(ready(), .5f));
  CHECK(!s.requestArm(ready(), NAN));
  auto noConfirm = ready();
  noConfirm.explicitArmConfirmation = false;
  CHECK(!s.requestArm(noConfirm, 0));
  CHECK(s.requestArm(ready(), 0));
  CHECK(s.state() == FlightState::ARMED_IDLE);
  s.update(ready(), .3f, 100, 100);
  CHECK(s.state() == FlightState::ACTIVE);
  s.update(ready(), .3f, 401, 100);
  CHECK(s.state() == FlightState::FAILSAFE);
  CHECK(!s.armed());
  CHECK(!s.requestArm(ready(), 0));
  s.disarm();
  CHECK(s.requestArm(ready(), 0));
  auto lost = ready();
  lost.imu = false;
  s.update(lost, .3f, 400, 400);
  CHECK(s.state() == FlightState::FAILSAFE);
  s.disarm();
  s.emergencyStop();
  s.disarm();
  CHECK(s.state() == FlightState::EMERGENCY_STOP);
  CHECK(!s.requestArm(ready(), 0));
  // Millisecond rollover is not a spurious timeout.
  StateMachine wrap;
  wrap.beginSelfTest();
  wrap.finishSelfTest(true);
  CHECK(wrap.requestArm(ready(), 0));
  wrap.update(ready(), .2f, 20, 0xfffffff0u);
  CHECK(wrap.armed());
  for (int i = 0; i < 6; i++) {
    Checks c = ready();
    switch (i) {
    case 0:
      c.imu = false;
      break;
    case 1:
      c.calibrated = false;
      break;
    case 2:
      c.authenticated = false;
      break;
    case 3:
      c.hardware = false;
      break;
    case 4:
      c.battery = false;
      break;
    default:
      c.timing = false;
    }
    StateMachine f;
    f.beginSelfTest();
    f.finishSelfTest(true);
    CHECK(!f.requestArm(c, 0));
    CHECK(f.requestArm(ready(), 0));
    f.update(c, .3f, 100, 100);
    CHECK(f.state() == FlightState::FAILSAFE);
  }
}
static void sessions() {
  SessionGate g;
  Command c;
  c.throttle = .2f;
  CHECK(!g.accept(1, 1, 100, 100, c));
  g.open(7, 100);
  CHECK(!g.accept(1, 1, 100, 100, c));
  CHECK(g.accept(7, 1, 100, 100, c));
  CHECK(!g.accept(7, 1, 110, 110, c));
  CHECK(!g.accept(7, 0, 110, 110, c));
  CHECK(!g.accept(7, 2, 105, 105, c));
  CHECK(!g.accept(7, 2, 110, 400, c));
  CHECK(!g.accept(7, 2, 401, 400, c));
  CHECK(g.lastControlMs() == 100);
  CHECK(g.accept(7, 3, 400, 400, c));
  CHECK(g.missed == 1);
  c.roll = NAN;
  CHECK(!g.accept(7, 4, 420, 420, c));
  g.close();
  CHECK(!g.active());
  g.open(9, 0xfffffff0u);
  c = {};
  CHECK(g.accept(9, 0xffffffffu, 0xfffffff0u, 0xfffffff0u, c));
  CHECK(g.accept(9, 0, 4, 4, c));
}
static void timing() {
  LoopStats s;
  s.record(500, 4000, 4000);
  s.record(4500, 5400, 4000);
  CHECK(s.samples == 2);
  CHECK(s.deadlineMisses == 1);
  CHECK(s.worstExecutionUs == 4500);
  CHECK(s.worstJitterUs == 1400);
}
int main() {
  try {
    test("proper sensor rotations", rotation);
    test("AHRS static tilt and acceleration rejection", attitude_static);
    test("AHRS gyro propagation and invalid samples", attitude_gyro);
    test("AHRS magnetic heading and interference gate", attitude_mag);
    test("PID saturation/antiwindup and invalid inputs", pid_windup);
    test("PID closed-loop plant and derivative kick", pid_plant);
    test("cascaded stabilizer restoring signs", cascaded);
    test("outer attitude integral applies, remains bounded and resets", cascaded_attitude_integral);
    test("Quad X torque signs, permutations and saturation", mixer);
    test("arming guards, latched estop, timeout, fault injection", state_machine);
    test("session ownership, replay, freshness, rate limit, rollover", sessions);
    test("deadline timing accounting", timing);
    std::cout << groups << " groups; " << assertions << " assertions passed\n";
    return EXIT_SUCCESS;
  } catch (const std::exception &e) {
    std::cerr << "FAIL " << e.what() << '\n';
    return EXIT_FAILURE;
  }
}
