// On-board LSM6DS3TR-C on Wire1 (P0.07 / P0.27), address 0x6A.
// Library: github.com/Seeed-Studio/Seeed_Arduino_LSM6DS3 (2.0.7). It powers the IMU
// through PIN_LSM6DS3TR_C_POWER (P1.08) and switches its I2C to Wire1 on this board.
#pragma once
#include <Arduino.h>

bool imuBegin();
// gyr[3], acc[3] in raw units; false on an I2C error.
bool imuRead(int16_t gyr[3], int16_t acc[3]);

// Scales of the settings used in imuBegin (from the library's calcAccel / calcGyro):
// accel 0.061 mg * (range / 2), gyro 4.375 mdps * (range / 125).
static const int IMU_ACC_RANGE_G = 8;
static const int IMU_GYR_RANGE_DPS = 500;
static const float IMU_ACC_MG_PER_LSB = 0.061f * (IMU_ACC_RANGE_G / 2);
static const float IMU_GYR_MDPS_PER_LSB = 4.375f * (IMU_GYR_RANGE_DPS / 125);
