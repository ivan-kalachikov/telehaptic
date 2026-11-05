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
  uint8_t id = 0;
  imu.readRegister(&id, 0x0F);  // WHO_AM_I; LSM6DS3TR-C = 0x6A (library header)
  return id == 0x6A;
}

bool imuRead(int16_t gyr[3], int16_t acc[3]) {
  // OUTX_L_G (0x22) .. OUTZ_H_XL (0x2D): gyro x y z, then accel x y z, LSB first.
  // One burst; the library itself relies on register auto-increment for 2-byte reads.
  uint8_t b[12];
  if (imu.readRegisterRegion(b, 0x22, 12) != 0) return false;
  for (int i = 0; i < 3; i++) {
    gyr[i] = (int16_t)((uint16_t)b[2 * i] | ((uint16_t)b[2 * i + 1] << 8));
    acc[i] = (int16_t)((uint16_t)b[6 + 2 * i] | ((uint16_t)b[6 + 2 * i + 1] << 8));
  }
  return true;
}
