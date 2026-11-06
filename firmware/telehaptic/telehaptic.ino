// Telehaptic capsule firmware: Seeed XIAO nRF52840 Sense, core "Seeed nRF52 Boards" (non-mbed).
// Reads 1..3 magnetometers behind a TCA9548A and the on-board IMU on one clock tick,
// streams the samples over BLE, pulses the vibration motor on command, reports the battery.
// Formats: protocol.h and docs/protocol.md.
#include <Adafruit_TinyUSB.h>  // Seeed wiki: needed for Serial on the non-mbed core
#include <Wire.h>
#include "protocol.h"
#include "sensors.h"
#include "imu.h"
#include "link.h"

// Pins (variant.h of Seeed_XIAO_nRF52840_Sense; XIAO nRF52840 Sense schematic v1.1).
static const uint8_t MOTOR_PIN = D2;  // P0.28, Grove Vibration Motor SIG, HIGH = on
// VBAT_ENABLE = 14 (P0.14): LOW enables the divider. Never driven HIGH by this sketch:
// Seeed wiki warns P0.31 may exceed 3.6 V otherwise. (The core's initVariant() sets it HIGH
// at boot, before setup(); the first line of setup() sets it LOW and it stays LOW.)
// PIN_VBAT = 32 (P0.31, AIN7). Divider on the schematic: VBAT - 1 M - P0.31 - 510 k - P0.14.
static const float VBAT_DIVIDER = (1000.0f + 510.0f) / 510.0f;
// PIN_CHARGING_CURRENT (P0.13) is not touched.

// ---------------------------------------------------------------- clock
// RTC2 counts the 32.768 kHz crystal (USE_LFXO in variant.h). FreeRTOS uses RTC1,
// the core and Bluefruit do not use RTC2. The 24-bit counter is extended in software;
// clockNow() is called only from the loop task, far more often than every 512 s.
static const uint32_t TICKS_PER_S = 32768;
static uint32_t clockLast24 = 0, clockExt = 0;

static void clockBegin() {
  NRF_RTC2->TASKS_STOP = 1;
  NRF_RTC2->PRESCALER = 0;  // 32768 Hz, writable only while stopped
  NRF_RTC2->TASKS_CLEAR = 1;
  NRF_RTC2->TASKS_START = 1;
}

// No watchdog on purpose: whether a pin reset (double tap into the UF2 bootloader) also
// resets the nRF52840 WDT was not checked, and the Adafruit bootloader does not feed it.
// A running WDT could make flashing impossible.

static const char* resetReason() {
  uint32_t r = NRF_POWER->RESETREAS;
  NRF_POWER->RESETREAS = r;  // bits are cleared by writing 1
  if (r & POWER_RESETREAS_DOG_Msk) return "watchdog";
  if (r & POWER_RESETREAS_RESETPIN_Msk) return "reset_button";
  if (r & POWER_RESETREAS_SREQ_Msk) return "software";
  if (r & POWER_RESETREAS_LOCKUP_Msk) return "lockup";
  if (r == 0) return "power_on";
  return "other";
}

static uint32_t clockNow() {
  uint32_t c = NRF_RTC2->COUNTER & 0xFFFFFF;
  clockExt += (c - clockLast24) & 0xFFFFFF;
  clockLast24 = c;
  return clockExt;
}

// ---------------------------------------------------------------- state
static MagConfig cfg = {30, 8, 8};  // +-30 G, OSR1 = 8, OSR2 = 8
static uint16_t rateHz = 200;  // 200 Hz: more accelerometer points at the jolt of a touch; 50 / 100 by command
static bool imuOk = false;
static bool configChanged = true;

static volatile uint32_t sampleIndex = 0;
static uint32_t schedStart = 0, schedN = 0, nextTick = 0;
static uint32_t overruns = 0, imuErrors = 0;

// The motor is switched off by a FreeRTOS one-shot timer, so a slow command (rescan, range)
// handled right after MOTOR cannot stretch the pulse. The timer callback only writes the pin
// and flags; the loop reports "MOTOR off" (clockNow() is used from the loop task only).
static volatile bool motorOn = false;
static volatile bool motorOffPending = false;
static volatile uint32_t motorOffIdx = 0;
static TimerHandle_t motorTimer = NULL;

static uint16_t batteryMv = 0;
static const char* bootReason = "";
static uint32_t nextBattery = 0, nextLive = 0;

// Current packet.
static uint8_t pkt[PKT_MAX_PAYLOAD];
static uint8_t pktCount = 0, pktMags = 0;
static uint16_t pktSeq = 0;
static uint32_t pktFirstT = 0;
static const uint32_t FLUSH_TICKS = 983;  // 30 ms

static uint16_t sampleSize(uint8_t n) { return 6 + 6 * n + 12; }
static uint8_t maxPerPacket(uint8_t n);

// Scan report, kept to resend when the phone (re)subscribes.
static const uint8_t SCAN_LINES_MAX = 32;  // worst case: begin, main, 8 channels x 3 candidates, end
static char scanLines[SCAN_LINES_MAX][96];
static uint8_t scanLineCount = 0;

static void put16(uint8_t* p, uint16_t v) { p[0] = v; p[1] = v >> 8; }
static void put32(uint8_t* p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

// ---------------------------------------------------------------- reports

static void emitScanLine(const char* line) {
  if (scanLineCount < SCAN_LINES_MAX) {
    strncpy(scanLines[scanLineCount], line, sizeof scanLines[0] - 1);
    scanLines[scanLineCount][sizeof scanLines[0] - 1] = 0;
    scanLineCount++;
  }
  linkInfo(line);
}

static void emitConfig() {
  char line[200];
  int pos = snprintf(line, sizeof line, "CFG rate=%u range=%u osr=%u/%u n=%u imu=%u", rateHz,
                     cfg.rangeG, cfg.osrA, cfg.osrB, magCount, imuOk ? 1 : 0);
  for (uint8_t i = 0; i < magCount && pos < (int)sizeof line; i++) {
    const Mag& m = mags[i];
    char where[6];
    if (m.channel == NO_MUX) strcpy(where, "main");
    else snprintf(where, sizeof where, "ch%u", m.channel);
    pos += snprintf(line + pos, sizeof line - pos, " mag%u=%s@%s,%u", i + 1, chipName(m.type), where,
                    (unsigned)lsbPerGauss(m, cfg));
  }
  linkInfo(line);
  linkInfo("IMU acc_mg_per_lsb=0.244 gyr_mdps_per_lsb=17.5 odr=208");
}

static void emitBattery() {
  char line[40];
  snprintf(line, sizeof line, "BAT mv=%u", batteryMv);
  linkInfo(line);
}

static void emitStatus() {
  char line[200];
  snprintf(line, sizeof line, "FW %s", FW_VERSION);
  linkInfo(line);
  snprintf(line, sizeof line, "BOOT reason=%s", bootReason);
  linkInfo(line);
  for (uint8_t i = 0; i < scanLineCount; i++) linkInfo(scanLines[i]);
  emitConfig();
  emitBattery();
  int pos = snprintf(line, sizeof line, "STATS uptime_s=%lu samples=%lu overruns=%lu dropped=%lu imu_err=%lu i2c_err=",
                     (unsigned long)(clockNow() / TICKS_PER_S), (unsigned long)sampleIndex,
                     (unsigned long)overruns, (unsigned long)(linkDroppedPackets + linkQueueFull), (unsigned long)imuErrors);
  for (uint8_t i = 0; i < magCount && pos < (int)sizeof line; i++)
    pos += snprintf(line + pos, sizeof line - pos, i ? ",%lu" : "%lu", (unsigned long)mags[i].errors);
  if (pos < (int)sizeof line) pos += snprintf(line + pos, sizeof line - pos, " info_dropped=%lu recover=", (unsigned long)linkDroppedInfo);
  for (uint8_t i = 0; i < magCount && pos < (int)sizeof line; i++)
    pos += snprintf(line + pos, sizeof line - pos, i ? ",%lu" : "%lu", (unsigned long)mags[i].recoveries);
  linkInfo(line);
}

// ---------------------------------------------------------------- battery

static void readBattery() {
  // Internal 0.6 V reference, gain 1/3: 0..1.8 V at 12 bit (wiring_analog_nRF52.c).
  // At 4.2 V on the battery P0.31 sees 4.2 * 510 / 1510 = 1.42 V.
  // Source impedance 1 M || 510 k = 338 k: the longest acquisition time (40 us) is used.
  analogReference(AR_INTERNAL_1_8);
  analogReadResolution(12);
  analogSampleTime(40);
  uint32_t sum = 0;
  for (int i = 0; i < 8; i++) sum += analogRead(PIN_VBAT);
  float vPin = (sum / 8.0f) * 1.8f / 4096.0f;
  batteryMv = (uint16_t)(vPin * VBAT_DIVIDER * 1000.0f + 0.5f);
}

// ---------------------------------------------------------------- packets

static void flushPacket() {
  if (pktCount == 0) return;
  pkt[0] = PKT_VERSION;
  pkt[1] = (uint8_t)((pktMags & 0x3) | (imuOk ? 0x4 : 0));
  put16(pkt + 2, pktSeq++);
  put32(pkt + 4, sampleIndex - pktCount);
  if (linkConnected()) linkSendData(pkt, PKT_HEADER + pktCount * sampleSize(pktMags));
  pktCount = 0;
}

static void takeSample(uint32_t t) {
  if (pktCount == 0) {
    pktMags = magCount;
    pktFirstT = t;
  }
  uint8_t* s = pkt + PKT_HEADER + pktCount * sampleSize(pktMags);
  uint16_t flags = 0;
  if (configChanged) { flags |= FLAG_CONFIG; configChanged = false; }
  if (motorOn) flags |= FLAG_MOTOR;

  put32(s, t);
  uint8_t* p = s + 6;
  for (uint8_t i = 0; i < pktMags; i++) {
    int16_t v[3];
    uint8_t sat;
    bool ovfl, stale;
    if (!readMag(mags[i], v, sat, ovfl, stale)) flags |= FLAG_I2C_ERR;
    flags |= (uint16_t)sat << (3 * i);
    if (ovfl) flags |= FLAG_OVFL;
    if (stale) flags |= FLAG_STALE;
    for (int a = 0; a < 3; a++, p += 2) put16(p, (uint16_t)v[a]);
  }
  // On a read error the last good IMU values repeat, as for the magnetometers (protocol.md).
  static int16_t g[3] = {0, 0, 0}, a[3] = {0, 0, 0};
  int16_t g1[3], a1[3];
  if (imuOk) {
    if (imuRead(g1, a1)) { memcpy(g, g1, sizeof g); memcpy(a, a1, sizeof a); }
    else { imuErrors++; flags |= FLAG_I2C_ERR | FLAG_IMU_ERR; }
  }
  for (int k = 0; k < 3; k++, p += 2) put16(p, (uint16_t)g[k]);
  for (int k = 0; k < 3; k++, p += 2) put16(p, (uint16_t)a[k]);
  put16(s + 4, flags);

  sampleIndex++;
  pktCount++;
  if (pktCount >= maxPerPacket(pktMags)) flushPacket();
}

// Samples that fit one notification at the negotiated MTU (Bluefruit splits longer ones into
// several notifications, which the app could not parse). At least one.
static uint8_t maxPerPacket(uint8_t n) {
  uint16_t room = linkPayloadMax();
  int k = room > PKT_HEADER ? (room - PKT_HEADER) / sampleSize(n) : 0;
  return k < 1 ? 1 : (uint8_t)k;
}

// Plain-text values on USB Serial twice a second, for checks without the app.
static void printLive() {
  char line[160];
  int pos = snprintf(line, sizeof line, "LIVE");
  for (uint8_t i = 0; i < magCount; i++) {
    float k = 100.0f / lsbPerGauss(mags[i], cfg);  // uT per unit
    long x = lroundf(mags[i].last[0] * k), y = lroundf(mags[i].last[1] * k), z = lroundf(mags[i].last[2] * k);
    pos += snprintf(line + pos, sizeof line - pos, " mag%u_uT=%ld,%ld,%ld", i + 1, x, y, z);
  }
  snprintf(line + pos, sizeof line - pos, " bat_mv=%u", batteryMv);
  serialLine(line);
}

// ---------------------------------------------------------------- schedule

static uint32_t tickAt(uint32_t n) {
  return schedStart + (uint32_t)(((uint64_t)n * TICKS_PER_S + rateHz / 2) / rateHz);
}

static void restartSchedule(uint32_t now) {
  schedStart = now;
  schedN = 0;
  nextTick = now;
}

static void rescan() {
  flushPacket();
  scanLineCount = 0;
  sensorsScan(emitScanLine);
  sensorsConfigure(cfg);
  configChanged = true;
  emitConfig();
}

// ---------------------------------------------------------------- commands

static void handleCommand(const Command& c, uint32_t now) {
  char line[64];
  if (c.len == 0) return;
  uint8_t op = c.data[0];
  uint16_t u16 = c.len >= 3 ? (uint16_t)(c.data[1] | (c.data[2] << 8)) : 0;
  switch (op) {
    case CMD_RATE:
      if (u16 == 50 || u16 == 100 || u16 == 200) {
        flushPacket();
        rateHz = u16;
        restartSchedule(now);
        configChanged = true;
        emitConfig();
      } else {
        linkInfo("ERR rate must be 50, 100 or 200");
      }
      break;
    case CMD_RANGE:
      if (c.len >= 2) {
        flushPacket();
        cfg.rangeG = c.data[1];
        sensorsConfigure(cfg);
        configChanged = true;
        emitConfig();
      }
      break;
    case CMD_OSR:
      if (c.len >= 3) {
        flushPacket();
        cfg.osrA = c.data[1];
        cfg.osrB = c.data[2];
        sensorsConfigure(cfg);
        configChanged = true;
        emitConfig();
      }
      break;
    case CMD_MOTOR: {
      if (c.len < 3) { linkInfo("ERR motor needs u16 ms"); break; }
      uint16_t ms = u16 < 1 ? 1 : (u16 > 2000 ? 2000 : u16);
      TickType_t ticks = pdMS_TO_TICKS(ms);
      if (ticks < 1) ticks = 1;
      digitalWrite(MOTOR_PIN, HIGH);
      motorOn = true;
      motorOffPending = false;
      // Changing the period also starts the timer; a running pulse is restarted with the new length.
      if (motorTimer == NULL || xTimerChangePeriod(motorTimer, ticks, 0) != pdPASS) {
        digitalWrite(MOTOR_PIN, LOW);
        motorOn = false;
        linkInfo("ERR motor timer");
        break;
      }
      snprintf(line, sizeof line, "MOTOR on idx=%lu t=%lu ms=%u", (unsigned long)sampleIndex,
               (unsigned long)now, ms);
      linkInfo(line);
      break;
    }
    case CMD_RESCAN:
      rescan();
      break;
    case CMD_STATUS:
      emitStatus();
      break;
    case CMD_PING:
      if (c.len >= 5) {
        uint32_t token = (uint32_t)c.data[1] | ((uint32_t)c.data[2] << 8) | ((uint32_t)c.data[3] << 16) |
                         ((uint32_t)c.data[4] << 24);
        snprintf(line, sizeof line, "PONG %lu %lu", (unsigned long)token, (unsigned long)now);
        linkInfo(line);
      }
      break;
    default:
      snprintf(line, sizeof line, "ERR unknown command 0x%02X", op);
      linkInfo(line);
  }
}

// Stall monitor: a FreeRTOS task (not the WDT peripheral, so flashing is unaffected). If the sample
// index has not moved for 2 s, the loop is stuck, most likely in an I2C transfer that never ends
// (the core's TWIM driver waits without a timeout). Restart; the next scan checks the bus lines
// first and skips I2C if a line is held low, so a broken wire cannot cause a restart loop.
static volatile bool monitorArmed = false;
static volatile bool monitorSetup = true;   // during setup(): the scan may hang, not the sampling

static void motorOffCb(TimerHandle_t) {
  digitalWrite(MOTOR_PIN, LOW);
  motorOn = false;
  motorOffIdx = sampleIndex;
  motorOffPending = true;
}

static void monitorTask(void*) {
  uint32_t last = sampleIndex, stuckMs = 0, setupMs = 0;
  for (;;) {
    vTaskDelay(pdMS_TO_TICKS(500));
    // A hang inside setup() (an I2C transfer that never ends during the first scan) would
    // otherwise need a power cycle. The scan takes well under a second; allow 10 s.
    if (monitorSetup) { setupMs += 500; if (setupMs >= 10000) NVIC_SystemReset(); continue; }
    if (!monitorArmed) { last = sampleIndex; stuckMs = 0; continue; }
    if (sampleIndex == last) stuckMs += 500; else stuckMs = 0;
    last = sampleIndex;
    if (stuckMs >= 2000) NVIC_SystemReset();   // reported after the restart as BOOT reason=software
  }
}

// ---------------------------------------------------------------- setup / loop

void setup() {
  pinMode(VBAT_ENABLE, OUTPUT);
  digitalWrite(VBAT_ENABLE, LOW);  // first: keep the divider enabled, P0.14 never HIGH
  pinMode(MOTOR_PIN, OUTPUT);
  digitalWrite(MOTOR_PIN, LOW);

  Serial.begin(115200);
  Wire.begin();
  Wire.setClock(I2C_HZ);  // see sensors.h

  const char* reason = resetReason();
  linkBegin();   // starts the SoftDevice and with it the 32 kHz clock
  clockBegin();
  delay(20);     // sensors' power-on time is 250 us (P) / 350 us (L)

  linkInfo("FW " FW_VERSION);
  char line[48];
  snprintf(line, sizeof line, "BOOT reason=%s", reason);
  linkInfo(line);
  bootReason = reason;
  delay(2);
  motorTimer = xTimerCreate("motor", 1, pdFALSE, NULL, motorOffCb);
  xTaskCreate(monitorTask, "monitor", 256, NULL, TASK_PRIO_NORMAL, NULL);   // watches setup() too
  bool clockOk = clockNow() != 0;
  if (!clockOk) linkInfo("CLOCK FAIL rtc2 not counting");
  rescan();
  imuOk = imuBegin();
  linkInfo(imuOk ? "IMU ok LSM6DS3TR-C" : "IMU FAIL");
  configChanged = true;
  emitConfig();

  readBattery();
  emitBattery();
  uint32_t now = clockNow();
  nextBattery = now + 5 * TICKS_PER_S;
  nextLive = now + TICKS_PER_S / 2;
  restartSchedule(now);
  monitorArmed = clockOk;   // without a clock there are no samples; restarting would not help
  monitorSetup = false;
}

void loop() {
  uint32_t now = clockNow();

  Command c;
  bool handled = false;
  // One command per pass, so a burst of slow commands (rescans) never starves sampling long
  // enough to trip the stall monitor.
  if (linkTakeCommand(c)) { handleCommand(c, clockNow()); handled = true; }
  if (linkTakeHello()) { emitStatus(); handled = true; }
  if (handled) now = clockNow();  // rescan / range / averaging block for 100-250 ms

  if (motorOffPending) {
    motorOffPending = false;
    char line[64];
    // idx = first sample without the motor flag; t = when the loop noticed (within ~1 ms).
    snprintf(line, sizeof line, "MOTOR off idx=%lu t=%lu", (unsigned long)motorOffIdx, (unsigned long)now);
    linkInfo(line);
  }

  if ((int32_t)(now - nextTick) >= 0) {
    uint32_t period = TICKS_PER_S / rateHz;
    if ((int32_t)(now - nextTick) > (int32_t)period) {  // fell behind by a whole period
      overruns++;
      restartSchedule(now);
    }
    takeSample(now);
    nextTick = tickAt(++schedN);
  }
  if (pktCount > 0 && (now - pktFirstT) >= FLUSH_TICKS) flushPacket();

  if ((int32_t)(now - nextBattery) >= 0) {
    readBattery();
    emitBattery();
    nextBattery = now + 5 * TICKS_PER_S;
  }
  if ((int32_t)(now - nextLive) >= 0) {
    if (Serial) printLive();
    nextLive = now + TICKS_PER_S / 2;
  }

  // Sleep a scheduler tick (~1 ms) when the next sample is far enough away.
  int32_t wait = (int32_t)(nextTick - clockNow());
  if (wait > 66) delay(1);
  else yield();
}
