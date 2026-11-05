// External I2C bus: TCA9548A multiplexer and QMC5883P / QMC5883L magnetometers.
#pragma once
#include <Arduino.h>

enum ChipType : uint8_t { CHIP_NONE = 0, CHIP_QMC5883P, CHIP_QMC5883L, CHIP_HMC5883L };

struct Mag {
  uint8_t channel;    // multiplexer channel 0..7, or NO_MUX if on the main bus
  uint8_t addr;
  ChipType type;
  int16_t last[3];    // last good reading (repeated after an I2C error)
  uint32_t errors;    // failed reads since start
};

struct MagConfig {
  uint8_t rangeG;     // 30 / 12 / 8 / 2
  uint8_t osrA;       // P: OSR1 1/2/4/8.  L: OSR = 64 * osrA
  uint8_t osrB;       // P: OSR2 1/2/4/8.  L: unused
};

static const uint8_t NO_MUX = 0xFF;
static const uint8_t MAX_MAGS = 3;

extern Mag mags[MAX_MAGS];
extern uint8_t magCount;
extern bool muxPresent;

const char* chipName(ChipType t);

// Scans the main bus and every multiplexer channel, fills mags[].
// Each report line goes to emit() (Serial + BLE INFO).
void sensorsScan(void (*emit)(const char*));

// Applies cfg to every magnetometer. Unsupported values are replaced by the
// nearest supported ones and cfg is updated to what was actually set.
void sensorsConfigure(MagConfig& cfg);

// Raw units per gauss for the current range of this chip.
float lsbPerGauss(const Mag& m, const MagConfig& cfg);

// Reads one magnetometer. sat: bits 0..2 = axis x/y/z saturated.
// Returns false on an I2C error (out keeps the last good values).
bool readMag(Mag& m, int16_t out[3], uint8_t& sat, bool& ovfl, bool& stale);
