#pragma once
#include "drone_core.h"
// Fill in measured values; no motor number implies a physical corner.
namespace hardware {
constexpr uint8_t IMU_ADDRESS = 0x69, BMP_ADDRESS = 0x76, INA_ADDRESS = 0x40;
constexpr int8_t IMU_AXES[3] = {
    1, 2, 3}; // signed body FLU permutation, verify all axes
constexpr float SHUNT_OHMS = 0, CURRENT_LSB_A = 0; // deliberately unconfigured
constexpr std::array<drone::MotorGeometry, 4> MOTORS = {
    {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}}};
} // namespace hardware
