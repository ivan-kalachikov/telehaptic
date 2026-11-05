// Sources: Bluefruit52Lib in github.com/Seeed-Studio/Adafruit_nRF52_Arduino
//  - configPrphBandwidth(BANDWIDTH_MAX) = MTU 247 (bluefruit.cpp), config before begin();
//  - PHY / data length / MTU requests on connect as in examples/Peripheral/throughput;
//  - notify() waits up to BLE_GENERIC_TIMEOUT (100 ms) for a free buffer
//    (BLECharacteristic.cpp, BLEConnection::getHvnPacket), so sending runs in its own task
//    and the sampling loop never waits for the radio.
#include "link.h"
#include <bluefruit.h>

static BLEService service(UUID_SERVICE);
static BLECharacteristic chrData(UUID_DATA);
static BLECharacteristic chrCtrl(UUID_CTRL);
static BLECharacteristic chrInfo(UUID_INFO);

struct Packet {
  uint8_t kind;  // 0 = data, 1 = info text
  uint16_t len;
  uint8_t buf[PKT_MAX_PAYLOAD];
};

static QueueHandle_t txQueue;     // DATA packets
static QueueHandle_t infoQueue;   // INFO lines: their own queue, so a full DATA queue cannot drop them
static QueueHandle_t cmdQueue;
static volatile bool hello = false;
volatile uint32_t linkDroppedPackets = 0;

static void onConnect(uint16_t conn) {
  BLEConnection* c = Bluefruit.Connection(conn);
  c->requestPHY();
  c->requestDataLengthUpdate();
  c->requestMtuExchange(247);
}

static void onDisconnect(uint16_t conn, uint8_t reason) {
  (void)conn;
  char line[48];
  snprintf(line, sizeof line, "LINK disconnected reason=0x%02X", reason);
  serialLine(line);
}

static void onCtrlWrite(uint16_t conn, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  (void)conn; (void)chr;
  Command c;
  c.len = len > sizeof c.data ? sizeof c.data : len;
  memcpy(c.data, data, c.len);
  xQueueSend(cmdQueue, &c, 0);
}

static void onInfoCccd(uint16_t conn, BLECharacteristic* chr, uint16_t value) {
  (void)conn; (void)chr;
  if (value & BLE_GATT_HVX_NOTIFICATION) hello = true;
}

static void txTask(void*) {
  static Packet p;
  for (;;) {
    // INFO first (rare, small), then DATA; wait at most 5 ms so INFO is never stuck behind an empty DATA queue.
    if (xQueueReceive(infoQueue, &p, 0) != pdTRUE && xQueueReceive(txQueue, &p, pdMS_TO_TICKS(5)) != pdTRUE) continue;
    BLECharacteristic& chr = p.kind == 0 ? chrData : chrInfo;
    if (!Bluefruit.connected() || !chr.notifyEnabled()) continue;
    if (!chr.notify(p.buf, p.len) && p.kind == 0) linkDroppedPackets++;
  }
}

void linkBegin() {
  txQueue = xQueueCreate(16, sizeof(Packet));
  infoQueue = xQueueCreate(24, sizeof(Packet));
  cmdQueue = xQueueCreate(8, sizeof(Command));

  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
  Bluefruit.begin();
  Bluefruit.setName(DEVICE_NAME);
  Bluefruit.setTxPower(4);
  Bluefruit.Periph.setConnectCallback(onConnect);
  Bluefruit.Periph.setDisconnectCallback(onDisconnect);
  Bluefruit.Periph.setConnInterval(6, 12);  // 7.5 .. 15 ms, units of 1.25 ms

  service.begin();

  chrData.setProperties(CHR_PROPS_NOTIFY);
  chrData.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chrData.setMaxLen(PKT_MAX_PAYLOAD);
  chrData.begin();

  chrCtrl.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  chrCtrl.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  chrCtrl.setMaxLen(sizeof(((Command*)0)->data));
  chrCtrl.setWriteCallback(onCtrlWrite);
  chrCtrl.begin();

  chrInfo.setProperties(CHR_PROPS_NOTIFY | CHR_PROPS_READ);
  chrInfo.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chrInfo.setMaxLen(PKT_MAX_PAYLOAD);
  chrInfo.setCccdWriteCallback(onInfoCccd);
  chrInfo.begin();
  chrInfo.write("FW " FW_VERSION);

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addService(service);  // 128-bit UUID: the app scans for it
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);  // 20 ms fast, 152.5 ms slow
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);

  xTaskCreate(txTask, "tx", 512, NULL, TASK_PRIO_LOW, NULL);
}

bool linkConnected() {
  return Bluefruit.connected() && chrData.notifyEnabled();
}

bool linkTakeCommand(Command& c) {
  return xQueueReceive(cmdQueue, &c, 0) == pdTRUE;
}

bool linkTakeHello() {
  if (!hello) return false;
  hello = false;
  return true;
}

bool linkSendData(const uint8_t* buf, uint16_t len) {
  static Packet p;
  p.kind = 0;
  p.len = len;
  memcpy(p.buf, buf, len);
  if (xQueueSend(txQueue, &p, 0) != pdTRUE) {
    linkDroppedPackets++;
    return false;
  }
  return true;
}

void linkInfo(const char* line) {
  serialLine(line);
  if (!Bluefruit.connected() || !chrInfo.notifyEnabled()) return;
  static Packet p;
  p.kind = 1;
  p.len = strnlen(line, PKT_MAX_PAYLOAD);
  memcpy(p.buf, line, p.len);
  xQueueSend(infoQueue, &p, pdMS_TO_TICKS(20));
}

// A terminal that keeps the port open but does not read would make Serial.write wait for ever
// (Adafruit_USBD_CDC::write loops while connected). Write only when the whole line fits.
void serialLine(const char* line) {
  size_t n = strlen(line);
  if (Serial && Serial.availableForWrite() >= (int)(n + 2)) Serial.println(line);
}
