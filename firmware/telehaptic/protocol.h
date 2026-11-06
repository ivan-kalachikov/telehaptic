// Formats shared with the Android app and the analysis.
// Human-readable description: docs/protocol.md. Keep both in sync.
#pragma once
#include <stdint.h>

#ifndef FW_BUILD
#define FW_BUILD 0  // CI passes the run number: -DFW_BUILD=<n>
#endif
#define FW_STR2(x) #x
#define FW_STR(x) FW_STR2(x)
#define FW_VERSION "0.1." FW_STR(FW_BUILD)

// GATT service and characteristics (random 128-bit base).
#define UUID_SERVICE "7e1a0001-5c2d-4b8e-9f3a-1d6c2b8e4a10"
#define UUID_DATA    "7e1a0002-5c2d-4b8e-9f3a-1d6c2b8e4a10"  // notify, binary samples
#define UUID_CTRL    "7e1a0003-5c2d-4b8e-9f3a-1d6c2b8e4a10"  // write, commands
#define UUID_INFO    "7e1a0004-5c2d-4b8e-9f3a-1d6c2b8e4a10"  // notify + read, text lines

#define DEVICE_NAME "Telehaptic"

// ---- DATA packet ------------------------------------------------------------
// Header (8 bytes), then samples. Little-endian.
//   u8  version     = PKT_VERSION
//   u8  layout      bits 0-1: number of magnetometers n (0..3); bit 2: IMU present
//   u16 packet_seq  +1 per packet
//   u32 first_index index of the first sample; indices are consecutive
// Sample (6 + 6n + 12 bytes, IMU part always present, zeros if no IMU):
//   u32 t           XIAO clock, ticks of 1/32768 s (RTC2, extended to 32 bit)
//   u16 flags       see FLAG_*
//   i16 mag[n][3]   raw chip units, x y z
//   i16 gyr[3]      raw IMU units
//   i16 acc[3]      raw IMU units
#define PKT_VERSION 1
#define PKT_HEADER 8
#define PKT_MAX_PAYLOAD 244  // ATT MTU 247 - 3; fewer samples per packet at a smaller MTU

// flags: bit 3*i + a = magnetometer i, axis a saturated (i = 0..2, a = 0..2)
#define FLAG_MOTOR    (1u << 9)   // vibration motor is on during this sample
#define FLAG_OVFL     (1u << 10)  // chip overflow bit set on any magnetometer
#define FLAG_STALE    (1u << 11)  // some magnetometer had no new data (DRDY = 0)
#define FLAG_CONFIG   (1u << 12)  // settings changed, first sample with new ones
#define FLAG_I2C_ERR  (1u << 13)  // a read failed; values of that sensor repeat the last good ones
#define FLAG_IMU_ERR  (1u << 14)  // the failed read was the IMU's (bit 13 is set too)

// ---- CTRL commands: 1 byte opcode + arguments ------------------------------
#define CMD_RATE    0x01  // u16 Hz: 50 / 100 / 200
#define CMD_RANGE   0x02  // u8 gauss: 30 / 12 / 8 / 2 (QMC5883L: 8 / 2)
#define CMD_OSR     0x03  // u8 a, u8 b. QMC5883P: OSR1 = a (1/2/4/8), OSR2 = b (1/2/4/8).
                          //               QMC5883L: OSR = 64 * a (a = 1/2/4/8), b ignored.
#define CMD_MOTOR   0x10  // u16 ms (1..2000)
#define CMD_RESCAN  0x20
#define CMD_STATUS  0x21
#define CMD_PING    0x30  // u32 token -> INFO "PONG <token> <t>"
