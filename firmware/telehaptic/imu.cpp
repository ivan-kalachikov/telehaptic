#include "imu.h"
#include <LSM6DS3.h>
#include <Wire.h>

static LSM6DS3 imu(I2C_MODE, 0x6A);  // SDO/SA0 tied to GND on the XIAO Sense schematic v1.1

bool imuBegin() {
  imu.settings.gyroRange = IMU_GYR_RANGE_DPS;
  imu.settings.gyroSampleRate = 208;   // Hz, >= the fastest tick (200 Hz)
  imu.settings.gyroBandWidth = 100;
  imu.settings.gyroFifoEnabled = 0;
  imu.settings.accelRange = IMU_ACC_RANGE_G;
  imu.settings.accelSampleRate = 208;
  imu.settings.accelBandWidth = 100;
  imu.settings.accelFifoEnabled = 0;
  imu.settings.fifoModeWord = 0;
  if (imu.begin() != 0) return false;
  Wire1.setClock(400000);
  // CTRL3_C (0x12) bit 6 BDU: output registers keep one sample until both bytes are read, so a
  // burst read never mixes the low byte of one sample with the high byte of the next
  // (LSM6DS3TR-C datasheet, CTRL3_C). The library does not set it; IF_INC (bit 2) stays as it is.
  uint8_t c3 = 0;
  if (imu.readRegister(&c3, 0x12) == 0) imu.writeRegister(0x12, (uint8_t)(c3 | 0x40));
  uint8_t id = 0;
  imu.readRegister(&id, 0x0F);  // WHO_AM_I; LSM6DS3TR-C = 0x6A (library header)
  return id == 0x6A;
}

bool imuRead(int16_t gyr[3], int16_t acc[3]) {
  // OUTX_L_G (0x22) .. OUTZ_H_XL (0x2D): gyro x y z, then accel x y z, LSB first. One burst on
  // Wire1 (the library's readRegisterRegion reports success even after a short read, so the
  // count is checked here). Register auto-increment: IF_INC, on by default in CTRL3_C.
  uint8_t b[12];
  Wire1.beginTransmission(0x6A);
  Wire1.write(0x22);
  if (Wire1.endTransmission(false) != 0) return false;
  if (Wire1.requestFrom((uint8_t)0x6A, (size_t)12) != 12) return false;
  for (int i = 0; i < 12; i++) b[i] = Wire1.read();
  for (int i = 0; i < 3; i++) {
    gyr[i] = (int16_t)((uint16_t)b[2 * i] | ((uint16_t)b[2 * i + 1] << 8));
    acc[i] = (int16_t)((uint16_t)b[6 + 2 * i] | ((uint16_t)b[6 + 2 * i + 1] << 8));
  }
  return true;
}
