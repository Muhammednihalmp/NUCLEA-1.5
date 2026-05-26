## 📡 NUCLEA – Universal RF/IR/BLE Attack & Debug Tool

**NUCLEA** is an open‑source hardware/software platform that turns an ESP32 + nRF24L01+ into a swiss‑army knife for wireless experimentation, security testing, and debugging.  
It supports:

- **433 MHz RF** – capture, replay, and brute‑force jam  
- **Infrared (38 kHz)** – capture and replay any IR remote signal  
- **BLE HID (Ducky Script)** – emulate a Bluetooth keyboard with programmable payloads  
- **nRF24L01+ BLE jammer** – flood BLE advertising channels (2, 26, 80)  

Control everything from a **dedicated Android app** (WiFi AP mode) or any browser via REST API.

---

## 🎯 Features

| Module        | Capabilities                                                                 |
|---------------|------------------------------------------------------------------------------|
| **RF 433 MHz**| Capture raw signals (value, bits, protocol, pulse length) – 4 slots. Replay with adjustable repeats. Jamming mode (full 60 s burst). |
| **Infrared**  | Capture any IR protocol (NEC, Sony, RC5, RC6, etc.) – 4 slots. Replay exactly as received. |
| **BLE HID**   | Ducky Script compatible payloads (key presses, modifiers, delays). Quick‑type text. 4 payload slots. |
| **BLE Jammer**| Spam 2.4 GHz on BLE advertising channels (2402, 2426, 2480 MHz) using nRF24L01+. Automatic 60 s stop. |
| **Web API**   | Full JSON REST interface – integrate with your own scripts.                   |
| **Android App**| Material Design, real‑time status, slot renaming, payload editor, syntax help. |

---

## 🧰 Hardware Requirements

- **ESP32** (any board with sufficient pins, e.g. DevKit v1)
- **nRF24L01+** module (with external antenna recommended for BLE jamming)
- **433 MHz receiver** (e.g. FS1000A / XY‑MK‑5V)
- **433 MHz transmitter** (e.g. FS1000A / MX‑05V)
- **IR receiver** (e.g. TSOP38238, VS1838B)
- **IR LED** (e.g. 5mm 940nm)
- **Breadboard & jumper wires**

> **Power note:** nRF24L01+ can draw >100 mA spikes; use a decoupling capacitor (10 µF–100 µF) close to the module, or an external 3.3 V regulator.

---

## 🔌 Wiring Diagram

| nRF24L01+ | ESP32      | RF RX     | ESP32 | RF TX     | ESP32 | IR RX    | ESP32 | IR TX    | ESP32 |
|-----------|------------|-----------|-------|-----------|-------|----------|-------|----------|-------|
| VCC       | 3.3 V      | DATA      | GPIO4 | DATA      | GPIO5 | DATA     | GPIO15| LED      | GPIO2 |
| GND       | GND        | VCC       | 5V*   | VCC       | 5V*   | VCC      | 3.3 V |          |       |
| CE        | GPIO22     | GND       | GND   | GND       | GND   | GND      | GND   |          |       |
| CSN       | GPIO21     |           |       |           |       |          |       |          |       |
| SCK       | GPIO18     |           |       |           |       |          |       |          |       |
| MOSI      | GPIO23     |           |       |           |       |          |       |          |       |
| MISO      | GPIO19     |           |       |           |       |          |       |          |       |

> *433 MHz modules often accept 5 V. Check your module’s datasheet.

---

## 📦 Required Libraries (Arduino IDE)

Install via **Library Manager**:

| Library          | Version | Author       |
|------------------|---------|--------------|
| RF24             | ≥1.4.8  | TMRh20       |
| RCSwitch         | latest  | sui77        |
| IRremote         | ≥2.8.0  | shirriff, z3t0|
| BleKeyboard      | latest  | T‑vK         |

Also install **ESP32 board package** (2.0.17 or newer) via Boards Manager.

---

## 🚀 Getting Started

### 1. Upload the ESP32 Firmware
- Open `nuclea.ino` in Arduino IDE.
- Select **ESP32 Dev Module** (or your specific board).
- Adjust pin definitions if you changed wiring.
- Upload and open **Serial Monitor** (115200 baud).  
  You’ll see:
  ```
  NUCLEA AP IP: 192.168.4.1
  NUCLEA ready!
  ```

### 2. Android App – Build & Install
- Clone the repository or copy `MainActivity.java` and layout files into a new Android Studio project (`com.example.nuclea`).
- Build the APK and install on your phone.

### 3. Connect to the ESP32
- On your phone, go to **WiFi Settings** → connect to `NUCLEA_AP` (password `nuclea123`).
- Open the **NUCLEA** app.
- Tap **PING** (default IP `192.168.4.1`).  
  The app will display the connected status and load all saved slots.

### 4. Using the Features

#### RF 433 MHz
- Tap **REC** on any slot to capture a signal for 10 seconds.
- Press **▶** to replay it (15 repeats, full power).
- Long‑press the slot name to rename it.
- Use the **JAMMER** section to start/stop a full‑band 433 MHz jam (60 s auto‑stop).

#### Infrared
- Point any IR remote at the receiver.
- Tap **REC** → capture for 6 seconds.
- Press **▶** to replay the exact same signal.
- Rename slots by long‑pressing the name.

#### BLE HID (Ducky)
- First tap **BLE ON** to enable the Bluetooth keyboard.  
  Pair with your target device (name: `NUCLEA HID`).
- **Quick‑type** any text and press **TYPE**.
- **Payload slots**:
  - Tap **✎** to edit a Ducky Script payload.
  - Syntax help is available inside the editor.
  - Press **▶** to execute the payload.
- Long‑press a slot name to rename it.

#### BLE Jammer (nRF24L01+)
- The app will show `● NRF READY` if the nRF24L01+ is detected.
- Tap **START BLE JAM** – the ESP32 will begin spamming BLE advertising channels (2, 26, 80) at maximum power.
- The jammer auto‑stops after 60 seconds.
- Tap **STOP BLE JAM** to cancel early.

> ⚠️ **Legal warning:** Jamming 2.4 GHz is illegal in most countries. Use only in a shielded lab or on your own devices with explicit permission.

---

## 🌐 Web API (REST)

You can control NUCLEA from any browser or script.  
Base URL: `http://192.168.4.1`

| Endpoint                | Method | Description                             |
|-------------------------|--------|-----------------------------------------|
| `/status`               | GET    | Full JSON state (slots, jamming flags)  |
| `/rf/read?slot=N`       | GET    | Capture RF for 10 s into slot N         |
| `/rf/emulate?slot=N`    | GET    | Replay RF slot N                        |
| `/rf/clear?slot=N`      | GET    | Clear RF slot N                         |
| `/rf/label?slot=N&name=X`| GET   | Rename RF slot                          |
| `/rf/jam/start`         | GET    | Start 433 MHz jamming                   |
| `/rf/jam/stop`          | GET    | Stop 433 MHz jamming                    |
| `/ir/read?slot=N`       | GET    | Capture IR for 6 s into slot N          |
| `/ir/emulate?slot=N`    | GET    | Replay IR slot N                        |
| `/ir/clear?slot=N`      | GET    | Clear IR slot                           |
| `/ir/label?slot=N&name=X`| GET   | Rename IR slot                          |
| `/bt/on`                | GET    | Enable BLE HID                          |
| `/bt/off`               | GET    | Disable BLE HID                         |
| `/bt/type?text=hello`   | GET    | Type plain text                         |
| `/bt/key?k=CTRL+C`      | GET    | Send a key combo                        |
| `/bt/set?slot=N&payload=...`| GET| Save Ducky payload                      |
| `/bt/exec?slot=N`       | GET    | Execute saved Ducky script              |
| `/bt/clear?slot=N`      | GET    | Clear BT slot                           |
| `/bt/label?slot=N&name=X`| GET   | Rename BT slot                          |
| `/ble/jam/start`        | GET    | Start BLE jamming                       |
| `/ble/jam/stop`         | GET    | Stop BLE jamming                        |
| `/ble/jam/status`       | GET    | JSON with `active`, `elapsed`, `nrfReady`, `packets` |

All endpoints support CORS.

---

## 📱 Android App Screenshots

*(Placeholder – add your own images)*

| RF Slots | IR Slots | BLE Ducky Editor | BLE Jammer |
|----------|----------|------------------|------------|
| ![RF](docs/rf.png) | ![IR](docs/ir.png) | ![BT](docs/bt.png) | ![Jam](docs/jam.png) |

---

## 🛠 Troubleshooting

| Problem                          | Solution                                                                 |
|----------------------------------|--------------------------------------------------------------------------|
| nRF24L01+ not detected           | Check wiring, power (use capacitor). Try `radio.begin()` in setup.       |
| IR signals not captured          | Verify IR receiver pin (GPIO15). Use a known working remote (NEC protocol first). |
| BLE HID not connecting           | Turn BLE on in app, then pair from target device. Some devices require "pairing code" – click OK. |
| Android app can’t connect        | Ensure phone is connected to `NUCLEA_AP`. Reboot ESP32 if AP not visible. |
| BLE jammer doesn’t block         | Reduce distance between nRF24 and target. Use external antenna. Increase `BLE_JAM_INTERVAL` to 1000 µs. |

---

## 📄 License

This project is released under the **MIT License**.  
**Use at your own risk.** The authors are not responsible for any misuse or damage caused by this tool.

---

## 🤝 Contributing

Pull requests, bug reports, and feature suggestions are welcome.  
Please follow the existing code style and document new features.

---

## ⭐ Acknowledgements

- RF24 library by TMRh20
- RCSwitch by sui77
- IRremote by shirriff/z3t0
- BleKeyboard by T‑vK
- ESP32 Arduino core

---

**Happy hacking – but stay legal!**
