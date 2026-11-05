// Sources for every register and value used here:
//  [TCA]  TI TCA9548A datasheet SCPS207G: address 0x70 with A2..A0 low (table 1),
//         one control byte, bit n = channel n, readable back (8.5.4).
//  [QP]   QST QMC5883P datasheet Rev A: address 0x2C (5.4); chip ID reg 0x00 = 0x80 (9.2.1);
//         data 0x01..0x06 X,Y,Z LSB first (9.2.1); STATUS 0x09 bit0 DRDY, bit1 OVFL set when any
//         axis is outside [-30000, 30000] (9.2.2); CTRL1 0x0A, CTRL2 0x0B fields (tables 17, 18);
//         init 0x29 = 0x06, soft reset 0x0B = 0x80, suspend before changing mode (7, 9.2.3);
//         sensitivity 1000 / 2500 / 3750 / 15000 LSB/G for 30 / 12 / 8 / 2 G (table 2).
//         Copy: github.com/WilliTourt/QMC5883P-Magnetometer-Lib/blob/main/datasheet/QMC5883P.pdf
//         Same values in github.com/adafruit/Adafruit_QMC5883P.
//         Burst read 0x01..0x09 (data, two unused bytes, status) as PX4 does:
//         github.com/PX4/PX4-Autopilot/blob/main/src/drivers/magnetometer/qmc5883p/QMC5883P.cpp
//         Wait after soft reset is not in the datasheet: PX4 waits 100 ms, Adafruit 50 ms; 100 ms here.
//  [QL]   QST QMC5883L datasheet: address 0x0D (5.4); chip ID reg 0x0D = 0xFF (9.2.6);
//         data 0x00..0x05, STATUS 0x06 bit0 DRDY, bit1 OVL, bit2 DOR; CTRL1 0x09 =
//         OSR[7:6] (512/256/128/64) RNG[5:4] (2G/8G) ODR[3:2] (10/50/100/200) MODE[1:0];
//         CTRL2 0x0A bit7 soft reset; 0x0B = 0x01 recommended (table 13, 7, 9.2.5);
//         sensitivity 12000 / 3000 LSB/G for 2 / 8 G (table 2).
//         Copy: github.com/libdriver/qmc5883l/blob/main/datasheet/qmc5883l_datasheet.pdf
//  [HMC]  HMC5883L at 0x1E with ID registers 0x0A..0x0C = 'H','4','3':
//         Linux drivers/iio/magnetometer/hmc5843_core.c. Detected and reported, not used.
#include "sensors.h"
#include <Wire.h>

Mag mags[MAX_MAGS];
uint8_t magCount = 0;
bool muxPresent = false;

static const uint8_t MUX_ADDR = 0x70;        // [TCA]
static const uint8_t QMCP_ADDR = 0x2C;       // [QP]
static const uint8_t QMCL_ADDR = 0x0D;       // [QL]
static const uint8_t HMC_ADDR = 0x1E;        // [HMC]
static uint8_t muxCurrent = 0xFE;            // unknown
// Devices on the main bus stay visible on every channel; they are skipped there.
static bool onMain[128];

const char* chipName(ChipType t) {
  switch (t) {
    case CHIP_QMC5883P: return "QMC5883P";
    case CHIP_QMC5883L: return "QMC5883L";
    case CHIP_HMC5883L: return "HMC5883L";
    default: return "none";
  }
}

// ---------------------------------------------------------------- I2C helpers

static bool probe(uint8_t addr) {
  Wire.beginTransmission(addr);
  return Wire.endTransmission() == 0;
}

static bool writeReg(uint8_t addr, uint8_t reg, uint8_t val) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(val);
  return Wire.endTransmission() == 0;
}

static bool readRegs(uint8_t addr, uint8_t reg, uint8_t* buf, uint8_t n) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(addr, (size_t)n) != n) return false;
  for (uint8_t i = 0; i < n; i++) buf[i] = Wire.read();
  return true;
}

// Selects one multiplexer channel (0..7), or none (NO_MUX). [TCA]
static bool muxSelect(uint8_t ch) {
  if (!muxPresent) return ch == NO_MUX;
  if (ch == muxCurrent) return true;
  Wire.beginTransmission(MUX_ADDR);
  Wire.write(ch == NO_MUX ? 0x00 : (uint8_t)(1u << ch));
  bool ok = Wire.endTransmission() == 0;
  muxCurrent = ok ? ch : 0xFE;
  return ok;
}

// Which magnetometer answers at addr (bus already selected).
static ChipType identify(uint8_t addr, char* note, size_t noteLen) {
  uint8_t b[3] = {0, 0, 0};
  note[0] = 0;
  if (addr == QMCP_ADDR) {
    if (readRegs(addr, 0x00, b, 1) && b[0] == 0x80) return CHIP_QMC5883P;
    snprintf(note, noteLen, "unknown chip at 0x2C, reg0x00=0x%02X", b[0]);
    return CHIP_NONE;
  }
  if (addr == QMCL_ADDR) {
    // reg 0x0D should read 0xFF; weak check, the address ACK is the main sign. [QL]
    if (readRegs(addr, 0x0D, b, 1) && b[0] == 0xFF) return CHIP_QMC5883L;
    snprintf(note, noteLen, "0x0D answers but reg0x0D=0x%02X, treated as QMC5883L", b[0]);
    return CHIP_QMC5883L;
  }
  if (addr == HMC_ADDR) {
    if (readRegs(addr, 0x0A, b, 3) && b[0] == 'H' && b[1] == '4' && b[2] == '3') return CHIP_HMC5883L;
    snprintf(note, noteLen, "unknown chip at 0x1E");
    return CHIP_NONE;
  }
  return CHIP_NONE;
}

// Lists every address that answers on the currently selected bus.
static int listAddresses(char* out, size_t len, uint8_t skip, bool onMainSkip) {
  int found = 0;
  size_t pos = 0;
  out[0] = 0;
  for (uint8_t a = 0x08; a < 0x78; a++) {
    if (a == skip) continue;
    if (onMainSkip && onMain[a]) continue;
    if (probe(a)) {
      if (!onMainSkip) onMain[a] = true;
      found++;
      if (pos + 6 < len) pos += snprintf(out + pos, len - pos, " 0x%02X", a);
    }
  }
  return found;
}

static void addMag(uint8_t ch, uint8_t addr, ChipType t) {
  if (magCount >= MAX_MAGS) return;
  Mag& m = mags[magCount++];
  m.channel = ch;
  m.addr = addr;
  m.type = t;
  m.last[0] = m.last[1] = m.last[2] = 0;
  m.errors = 0;
}

// Looks for a supported magnetometer on the selected bus; emits one line.
static void scanOne(uint8_t ch, void (*emit)(const char*), bool hintIfEmpty) {
  char line[160], addrs[100], note[80];
  char where[8];
  if (ch == NO_MUX) snprintf(where, sizeof where, "main");
  else snprintf(where, sizeof where, "ch%u", ch);

  bool channel = ch != NO_MUX;
  int n = listAddresses(addrs, sizeof addrs, muxPresent ? MUX_ADDR : 0xFF, channel);
  const uint8_t candidates[] = {QMCP_ADDR, QMCL_ADDR, HMC_ADDR};
  bool any = false;
  for (uint8_t a : candidates) {
    if (channel && onMain[a]) continue;
    if (!probe(a)) continue;
    ChipType t = identify(a, note, sizeof note);
    any = true;
    if (t == CHIP_QMC5883P || t == CHIP_QMC5883L) {
      addMag(ch, a, t);
      snprintf(line, sizeof line, "SCAN %s 0x%02X %s mag%u%s%s", where, a, chipName(t), magCount,
               note[0] ? " NOTE " : "", note);
    } else if (t == CHIP_HMC5883L) {
      snprintf(line, sizeof line, "SCAN %s 0x%02X HMC5883L UNSUPPORTED", where, a);
    } else {
      snprintf(line, sizeof line, "SCAN %s 0x%02X UNKNOWN %s", where, a, note);
    }
    emit(line);
  }
  if (!any) {
    if (n > 0) {
      snprintf(line, sizeof line, "SCAN %s OTHER%s", where, addrs);
      emit(line);
    } else if (hintIfEmpty) {
      snprintf(line, sizeof line, "SCAN %s EMPTY HINT_PULLUPS", where);
      emit(line);
    }
  }
}

void sensorsScan(void (*emit)(const char*)) {
  magCount = 0;
  muxCurrent = 0xFE;
  memset(onMain, 0, sizeof onMain);
  muxPresent = probe(MUX_ADDR);
  char line[64];
  snprintf(line, sizeof line, "SCAN begin mux=%s", muxPresent ? "yes" : "no");
  emit(line);

  // Main bus with every channel switched off.
  if (muxPresent) muxSelect(NO_MUX);
  scanOne(NO_MUX, emit, !muxPresent);

  if (muxPresent) {
    for (uint8_t ch = 0; ch < 8; ch++) {
      if (!muxSelect(ch)) {
        snprintf(line, sizeof line, "SCAN ch%u MUX_ERROR", ch);
        emit(line);
        continue;
      }
      scanOne(ch, emit, ch <= 2);  // channels 0, 1 = sensors, 2 = spare
    }
    muxSelect(NO_MUX);
  }
  snprintf(line, sizeof line, "SCAN end mags=%u", magCount);
  emit(line);
}

// ---------------------------------------------------------------- configuration

static uint8_t log2of(uint8_t v) {  // 1->0, 2->1, 4->2, 8->3
  uint8_t r = 0;
  while (v > 1 && r < 3) { v >>= 1; r++; }
  return r;
}

static uint8_t validOsr(uint8_t v) {
  return (v == 1 || v == 2 || v == 4 || v == 8) ? v : 8;
}

static void configureP(Mag& m, const MagConfig& cfg) {
  // [QP] table 18: RNG 00 = 30 G, 01 = 12 G, 10 = 8 G, 11 = 2 G; SET/RESET 00 = on.
  uint8_t rng = cfg.rangeG == 2 ? 3 : cfg.rangeG == 8 ? 2 : cfg.rangeG == 12 ? 1 : 0;
  // [QP] table 17: OSR1 code 00 = 8, 01 = 4, 10 = 2, 11 = 1; OSR2 code 00 = 1 ... 11 = 8.
  uint8_t osr1 = 3 - log2of(cfg.osrA);
  uint8_t osr2 = log2of(cfg.osrB);
  // MODE 01 = normal, ODR 11 = 200 Hz.
  uint8_t ctrl1 = (uint8_t)((osr2 << 6) | (osr1 << 4) | (0x3 << 2) | 0x1);
  writeReg(m.addr, 0x0A, 0x00);           // suspend before changing mode [QP 9.2.3]
  writeReg(m.addr, 0x29, 0x06);           // axis sign definition [QP 7]
  writeReg(m.addr, 0x0B, (uint8_t)(rng << 2));
  writeReg(m.addr, 0x0A, ctrl1);
}

static void configureL(Mag& m, const MagConfig& cfg) {
  // [QL] CTRL1: OSR 00 = 512, 01 = 256, 10 = 128, 11 = 64; RNG 00 = 2 G, 01 = 8 G;
  // ODR 11 = 200 Hz; MODE 01 = continuous.
  uint8_t osr = 3 - log2of(cfg.osrA);  // osrA 8 -> 512 -> code 00
  uint8_t rng = cfg.rangeG == 2 ? 0 : 1;
  writeReg(m.addr, 0x0B, 0x01);
  writeReg(m.addr, 0x09, (uint8_t)((osr << 6) | (rng << 4) | (0x3 << 2) | 0x1));
}

void sensorsConfigure(MagConfig& cfg) {
  bool anyL = false;
  for (uint8_t i = 0; i < magCount; i++) anyL |= mags[i].type == CHIP_QMC5883L;
  if (cfg.rangeG != 30 && cfg.rangeG != 12 && cfg.rangeG != 8 && cfg.rangeG != 2) cfg.rangeG = 30;
  if (anyL && cfg.rangeG != 2) cfg.rangeG = 8;  // QMC5883L has only 2 and 8 G
  cfg.osrA = validOsr(cfg.osrA);
  cfg.osrB = validOsr(cfg.osrB);

  // Soft reset first, then configure. [QP 7], [QL 7]
  for (uint8_t i = 0; i < magCount; i++) {
    Mag& m = mags[i];
    muxSelect(m.channel);
    writeReg(m.addr, m.type == CHIP_QMC5883P ? 0x0B : 0x0A, 0x80);
  }
  delay(100);
  for (uint8_t i = 0; i < magCount; i++) {
    Mag& m = mags[i];
    muxSelect(m.channel);
    if (m.type == CHIP_QMC5883P) configureP(m, cfg);
    else configureL(m, cfg);
  }
}

float lsbPerGauss(const Mag& m, const MagConfig& cfg) {
  if (m.type == CHIP_QMC5883P) {
    switch (cfg.rangeG) {
      case 2: return 15000.0f;
      case 8: return 3750.0f;
      case 12: return 2500.0f;
      default: return 1000.0f;
    }
  }
  return cfg.rangeG == 2 ? 12000.0f : 3000.0f;
}

// ---------------------------------------------------------------- reading

bool readMag(Mag& m, int16_t out[3], uint8_t& sat, bool& ovfl, bool& stale) {
  uint8_t b[9];
  sat = 0;
  ovfl = false;
  stale = false;
  bool ok = muxSelect(m.channel);
  int16_t v[3];
  uint8_t status = 0;
  if (ok && m.type == CHIP_QMC5883P) {
    ok = readRegs(m.addr, 0x01, b, 9);  // X,Y,Z, 2 unused, STATUS [QP], PX4
    status = b[8];
  } else if (ok) {
    ok = readRegs(m.addr, 0x00, b, 7);  // X,Y,Z, STATUS [QL]
    status = b[6];
  }
  if (!ok) {
    m.errors++;
    out[0] = m.last[0]; out[1] = m.last[1]; out[2] = m.last[2];
    return false;
  }
  for (int a = 0; a < 3; a++) v[a] = (int16_t)((uint16_t)b[2 * a] | ((uint16_t)b[2 * a + 1] << 8));
  stale = (status & 0x01) == 0;  // DRDY
  ovfl = (status & 0x02) != 0;   // OVFL / OVL
  int32_t limit = m.type == CHIP_QMC5883P ? 30000 : 32767;
  for (int a = 0; a < 3; a++) {
    int32_t x = v[a];
    if (x >= limit || x <= -limit) sat |= (uint8_t)(1u << a);
    out[a] = m.last[a] = v[a];
  }
  return true;
}
