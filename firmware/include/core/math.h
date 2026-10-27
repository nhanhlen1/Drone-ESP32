#pragma once
// FLU vectors, rotations and coordinate transforms.
#include <array>
#include <cmath>

namespace drone {
constexpr float pi = 3.14159265358979323846f;
inline float clamp(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }
inline float wrapPi(float x) {
  if (!std::isfinite(x))
    return 0;
  return std::remainder(x, 2 * pi);
}
struct Vec3 {
  float x = 0, y = 0, z = 0;
  Vec3() = default;
  Vec3(float a, float b, float c) : x(a), y(b), z(c) {}
  Vec3 operator+(Vec3 b) const { return {x + b.x, y + b.y, z + b.z}; }
  Vec3 operator-(Vec3 b) const { return {x - b.x, y - b.y, z - b.z}; }
  Vec3 operator*(float s) const { return {x * s, y * s, z * s}; }
  bool finite() const { return std::isfinite(x) && std::isfinite(y) && std::isfinite(z); }
  float norm() const { return std::sqrt(x * x + y * y + z * z); }
};
inline Vec3 cross(Vec3 a, Vec3 b) {
  return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
inline float dot(Vec3 a, Vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
struct Euler {
  float roll = 0, pitch = 0, yaw = 0;
};
struct Quaternion {
  float w = 1, x = 0, y = 0, z = 0;
  Vec3 rotate(Vec3 v) const {
    Vec3 q{x, y, z};
    return v + cross(q, v) * (2 * w) + cross(q, cross(q, v)) * 2;
  }
  Vec3 inverseRotate(Vec3 v) const {
    Quaternion q = *this;
    q.x = -x;
    q.y = -y;
    q.z = -z;
    return q.rotate(v);
  }
  bool normalize() {
    float n = std::sqrt(w * w + x * x + y * y + z * z);
    if (!std::isfinite(n) || n < 1e-8f)
      return false;
    w /= n;
    x /= n;
    y /= n;
    z /= n;
    return true;
  }
  Euler euler() const {
    Euler e;
    e.roll = std::atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y));
    e.pitch = std::asin(clamp(2 * (w * y - z * x), -1, 1));
    e.yaw = std::atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z));
    return e;
  }
};
// Signed axis permutation. Only proper rotations (determinant +1) are accepted.
class AxisRotation {
  std::array<int, 3> axes_{{1, 2, 3}};

public:
  bool configure(std::array<int, 3> a) {
    bool used[3] = {false, false, false};
    float m[3][3] = {{0}};
    for (int i = 0; i < 3; i++) {
      if (a[i] == 0 || a[i] < -3 || a[i] > 3)
        return false;
      int k = std::abs(a[i]) - 1;
      if (k < 0 || k > 2 || used[k])
        return false;
      used[k] = true;
      m[i][k] = a[i] > 0 ? 1.f : -1.f;
    }
    float d = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
              m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
              m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    if (d != 1)
      return false;
    axes_ = a;
    return true;
  }
  Vec3 apply(Vec3 v) const {
    float s[3] = {v.x, v.y, v.z}, r[3];
    for (int i = 0; i < 3; i++)
      r[i] = s[std::abs(axes_[i]) - 1] * (axes_[i] > 0 ? 1.f : -1.f);
    return {r[0], r[1], r[2]};
  }
};
} // namespace drone
