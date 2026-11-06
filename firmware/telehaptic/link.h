// BLE service (Bluefruit, non-mbed Seeed nRF52 core) and USB Serial text output.
#pragma once
#include <Arduino.h>
#include "protocol.h"

struct Command {
  uint8_t len;
  uint8_t data[15];
};

void linkBegin();
bool linkConnected();      // phone connected and subscribed to DATA
bool linkTakeCommand(Command& c);   // next command written to CTRL, if any
bool linkTakeHello();      // true once after the phone subscribes to INFO

// Queues a DATA packet for sending; false if the queue is full (packet dropped).
bool linkSendData(const uint8_t* buf, uint16_t len);
// Text line to USB Serial and, when subscribed, to INFO.
void linkInfo(const char* line);

// Notification payload that fits the negotiated MTU (at most PKT_MAX_PAYLOAD).
uint16_t linkPayloadMax();

extern volatile uint32_t linkDroppedPackets;  // notify failed (tx task)
extern volatile uint32_t linkQueueFull;       // packet dropped, send queue full (loop task)
extern volatile uint32_t linkDroppedInfo;     // INFO line dropped, queue full

// USB Serial line that never blocks.
void serialLine(const char* line);
