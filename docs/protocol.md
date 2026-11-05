# Capsule protocol

The capsule is a XIAO nRF52840 Sense with magnetometers. This file describes how it talks to the phone. The same format is written in code:
- firmware: `firmware/telehaptic/protocol.h`;
- app: `android/.../core/Protocol.kt`;
- analysis: `analysis/analyze.py`.

When the format changes, change all of them together and raise `PKT_VERSION`.

## BLE service

| What | UUID | Properties |
|---|---|---|
| Service | `7e1a0001-5c2d-4b8e-9f3a-1d6c2b8e4a10` | advertised; the app scans for it |
| DATA | `7e1a0002-5c2d-4b8e-9f3a-1d6c2b8e4a10` | notify, binary sample packets |
| CTRL | `7e1a0003-5c2d-4b8e-9f3a-1d6c2b8e4a10` | write / write without response, commands |
| INFO | `7e1a0004-5c2d-4b8e-9f3a-1d6c2b8e4a10` | notify + read, UTF-8 text lines |

- Device name: `Telehaptic`.
- External I2C runs at 100 kHz for the first bring-up (multiplexer and wires across the hand); two magnetometers take about 3 ms per tick.
- INFO lines have their own send queue, so a full DATA queue cannot drop them. The app asks for the status again if a packet's sensor count does not match the last CFG line.
- USB Serial lines are written only when they fit in the buffer, so a terminal that does not read cannot block the firmware.
- MTU up to 247, so up to 244 payload bytes per notification.
- The capsule asks for a 7.5–15 ms connection interval.
- When the phone subscribes to INFO, the capsule sends its full status (below).

## DATA packet

Little-endian.

| Offset | Field | Type | Meaning |
|---|---|---|---|
| 0 | version | u8 | 1 |
| 1 | layout | u8 | bits 0–1: number of magnetometers n (0–3); bit 2: IMU present |
| 2 | packet_seq | u16 | +1 per packet |
| 4 | first_index | u32 | index of the first sample in the packet |
| 8 | samples | | k samples of S = 18 + 6n bytes |

**Sample indices are consecutive since power-on.** A gap in the indices is a loss, so the app counts losses to the sample; the packet counter is not needed for that.

Sample:

| Offset | Field | Type | Meaning |
|---|---|---|---|
| 0 | t | u32 | XIAO clock, ticks of 1/32768 s (RTC2 on the 32.768 kHz crystal); wraps after ~36 h |
| 4 | flags | u16 | see below |
| 6 | mag[i].x, y, z | i16 × 3n | raw chip units; µT = value × 100 / LSB_per_gauss (from the CFG line) |
| 6+6n | gyr x, y, z | i16 × 3 | raw; mdps = value × 17.5 |
| 12+6n | acc x, y, z | i16 × 3 | raw; mg = value × 0.244 |

Without an IMU its values are zero.

Samples per packet: (244 − 8) / S, so 7 with n = 2 and 6 with n = 3.

A packet is sent when it is full, but no later than 30 ms after its first sample.

`flags`:

| Bit | Meaning |
|---|---|
| 3i + a | magnetometer i (0–2), axis a (0 = x, 1 = y, 2 = z) saturated: QMC5883P \|value\| ≥ 30000, QMC5883L ≥ 32767 |
| 9 | motor on |
| 10 | overflow bit OVFL/OVL set on some magnetometer |
| 11 | some magnetometer had no new data (DRDY = 0); the value repeats |
| 12 | new settings apply from this sample (rate, range, averaging, rescan) |
| 13 | I2C read error: that sensor's values repeat the last good ones |

Magnetometer order: a sensor on the main bus first, if any, then by multiplexer channel. Which sensor is on which channel is in the `SCAN` and `CFG` lines.

## CTRL commands

The first byte is the command code, then the arguments, little-endian.

| Code | Command | Arguments | Answer on INFO |
|---|---|---|---|
| 0x01 | sampling rate | u16 Hz: 50 / 100 / 200 | `CFG …` or `ERR …` |
| 0x02 | range | u8 gauss: 30 / 12 / 8 / 2 (QMC5883L: 8 / 2 only) | `CFG …` |
| 0x03 | averaging | u8 a, u8 b. P: OSR1 = a, OSR2 = b (1/2/4/8). L: OSR = 64·a | `CFG …` |
| 0x10 | motor pulse | u16 ms (1–2000) | `MOTOR on …`, then `MOTOR off …` |
| 0x20 | rescan the bus | — | `SCAN …` lines, `CFG …` |
| 0x21 | full status | — | see "Status" |
| 0x30 | ping | u32 phone token | `PONG <token> <t>` |

## INFO lines

The lines go to USB Serial (115200) and, when the phone is subscribed, to INFO. Format: `WORD key=value …`.

```
FW 0.1.17
BOOT reason=power_on
SCAN begin mux=yes
SCAN ch0 0x2C QMC5883P mag1
SCAN ch1 0x2C QMC5883P mag2
SCAN ch2 EMPTY HINT_PULLUPS
SCAN end mags=2
IMU ok LSM6DS3TR-C
CFG rate=200 range=30 osr=8/8 n=2 imu=1 mag1=QMC5883P@ch0,1000 mag2=QMC5883P@ch1,1000
IMU acc_mg_per_lsb=0.244 gyr_mdps_per_lsb=17.5 odr=208
BAT mv=3987
STATS uptime_s=120 samples=24000 overruns=0 dropped=0 imu_err=0 i2c_err=0,0
MOTOR on idx=5012 t=1642291 ms=40
MOTOR off idx=5020 t=1643602
PONG 123456 1650000
```

- `BOOT reason=`: cause of the last start: `power_on`, `reset_button`, `software`, `lockup`, `watchdog`, `other`. `software` means a software reset: either the stall monitor (the sample index had not moved for 2 s, for example an I2C transfer that never ended) or the reboot after flashing; RESETREAS does not tell them apart. The monitor is a FreeRTOS task, not the WDT peripheral, so flashing is unaffected.
- `SCAN <where> …`:
  - `<where>` is `main` (the main bus) or `chN` (a multiplexer channel);
  - variants: address and chip, `UNSUPPORTED`, `UNKNOWN`, `OTHER <addresses>`, `EMPTY HINT_PULLUPS`, `MUX_ERROR`;
  - `HINT_PULLUPS` is written for empty channels 0–2; the app shows "Channel N: sensor does not answer. Check the SDN/SCN wires and the 4.7 kΩ pull-ups to 3.3 V". Channel 2 is the spare.
- `SCAN BUS_STUCK sda=0 scl=1`: before the scan SDA or SCL is held low. Nothing is sent on the bus then: the core's I2C driver waits without a timeout and would hang. Cause: wiring, a short to GND or missing pull-ups.
- `CLOCK FAIL`: RTC2 is not counting; there will be no samples.
- In `CFG` each magnetometer has its chip, place and LSB per gauss.
- `MOTOR … idx`: sample index; `t`: XIAO clock at the command or when the motor stopped.
- `PONG <token> <t>`: t is taken when the ping is handled.
- `BAT mv` comes every 5 s; how it is measured is in the comments of `telehaptic.ino`.
- `STATS`:
  - `overruns`: how often the sampling fell a whole period behind;
  - `dropped`: packets that did not go out over the radio;
  - `i2c_err`: read errors per magnetometer.
- Status = `FW`, `BOOT`, all `SCAN` lines of the last scan, `CFG`, `IMU`, `BAT`, `STATS`.

## Defaults and why

- QMC5883P: Normal mode, ODR 200 Hz, OSR1 = 8, OSR2 = 8, the init example from the datasheet (§7).
- Sampling 200 Hz by default: the magnetometers (200 Hz) and the IMU (208 Hz) give a fresh value on every tick, and the accelerometer has a chance to catch the jolt of a touch. 100 and 50 Hz by command.
- Range ±30 G, 0.1 µT per unit. Estimate, not a measurement: an 8×2 mm N45 disc 20–30 mm from a sensor can exceed 800 µT (±8 G).
- QMC5883L: Continuous mode, ODR 200 Hz, OSR 512, ±8 G (its widest range).
- IMU: ±8 g, ±500 °/s, ODR 208 Hz.
