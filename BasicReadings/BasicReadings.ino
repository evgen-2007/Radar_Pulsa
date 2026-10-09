#include <Arduino.h>
#include <HardwareSerial.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include <NimBLEDevice.h>
#include <Adafruit_NeoPixel.h>
#include <Seeed_Arduino_mmWave.h>
#include <LittleFS.h>
#include <esp_system.h>

// ============================================================
// XIAO ESP32-C6 + MR60BHA2
// Wi-Fi + configuration AP + HTTP API + BLE JSON
// External WS2812 RGB LED connected to D1
// Recording state stored in Preferences
// CSV recording in LittleFS
// ============================================================

#define DEVICE_NAME       "MR60BHA2"
#define BLE_DEVICE_NAME   "XIAO-C6"

#define BLE_SERVICE_UUID \
  "7a1e0001-8b4f-4d2a-9c21-1234567890ab"

#define BLE_CHARACTERISTIC_UUID \
  "7a1e0002-8b4f-4d2a-9c21-1234567890ab"

// External WS2812 / NeoPixel RGB LED
static const int RGB_LED_PIN = D1;
static const int RGB_LED_COUNT = 1;

Adafruit_NeoPixel rgbLed(
  RGB_LED_COUNT,
  RGB_LED_PIN,
  NEO_GRB + NEO_KHZ800
);

// -------------------- LED modes --------------------

enum LedMode {
  LED_WHITE,
  LED_RAINBOW,
  LED_GREEN,
  LED_RED,
  LED_YELLOW,
  LED_PURPLE
};

LedMode ledMode = LED_WHITE;

unsigned long rainbowStartedAt = 0;
unsigned long lastRainbowFrame = 0;
uint16_t rainbowHue = 0;

unsigned long bootStartedAt = 0;
unsigned long lastLEDUpdate = 0;

bool rainbowPending = false;
bool redBlinkState = false;

// -------------------- Hardware / services --------------------

HardwareSerial radarSerial(0);
SEEED_MR60BHA2 mmWave;

WebServer server(80);
Preferences preferences;

// -------------------- Wi-Fi state --------------------

String savedSSID;
String savedPassword;
String apName;

bool provisioningMode = false;
bool wifiConnected = false;

// -------------------- BLE state --------------------

bool bleStarted = false;
bool bleClientConnected = false;

NimBLEServer *bleServer = nullptr;
NimBLEService *bleService = nullptr;
NimBLECharacteristic *bleCharacteristic = nullptr;

// -------------------- Recording state --------------------

bool recordingActive = false;
bool filesystemReady = false;

unsigned long lastRecordWrite = 0;
const unsigned long RECORD_INTERVAL_MS = 1000;

const char *RECORD_FILE = "/records.csv";

// -------------------- Timers --------------------

unsigned long wifiConnectStarted = 0;
unsigned long lastWiFiAttempt = 0;
unsigned long lastBLENotify = 0;
unsigned long lastWiFiStatusPrint = 0;

const unsigned long WIFI_CONNECT_TIMEOUT = 15000;
const unsigned long WIFI_RETRY_INTERVAL = 10000;
const unsigned long BLE_NOTIFY_INTERVAL = 1000;

// -------------------- Radar values --------------------

float breathRate = 0.0f;
float heartRate = 0.0f;
float distance = 0.0f;

bool targetDetected = false;
bool breathValid = false;
bool heartValid = false;
bool distanceValid = false;

unsigned long lastSensorFrame = 0;
unsigned long lastBreath = 0;
unsigned long lastHeart = 0;
unsigned long lastDistance = 0;

// ============================================================
// RGB LED
// ============================================================

void setRGB(uint8_t r, uint8_t g, uint8_t b) {
  rgbLed.setPixelColor(0, rgbLed.Color(r, g, b));
  rgbLed.show();
}

void showRainbowFrame() {
  uint32_t color =
    rgbLed.gamma32(rgbLed.ColorHSV(rainbowHue));

  rgbLed.setPixelColor(0, color);
  rgbLed.show();

  rainbowHue += 1800;
}

void updateStatusLED() {
  const unsigned long now = millis();

  // Rainbow effect for the first 3 seconds after every boot.
  if (ledMode == LED_RAINBOW && now - rainbowStartedAt < 3000) {
    if (now - lastRainbowFrame >= 25) {
      lastRainbowFrame = now;
      showRainbowFrame();
    }
    return;
  }

  if (ledMode == LED_RAINBOW && now - rainbowStartedAt >= 3000) {
    ledMode = LED_WHITE;
  }

  // Recording indication takes priority after the startup rainbow.
  if (recordingActive) {
    setRGB(170, 0, 255);
    return;
  }

  // Treat missing radar frames after the startup grace period as an error.
  const bool radarFault =
    now - bootStartedAt > 20000 &&
    (lastSensorFrame == 0 || now - lastSensorFrame > 10000);

  // Red blinking indicates a sensor/BLE startup fault.
  if (radarFault || !bleStarted) {
    if (now - lastLEDUpdate >= 400) {
      lastLEDUpdate = now;
      redBlinkState = !redBlinkState;
      setRGB(redBlinkState ? 255 : 0, 0, 0);
    }
    return;
  }

  // BLE client connected: blue when not recording.
  if (bleClientConnected) {
    setRGB(0, 100, 255);
    return;
  }

  // Connected to Wi-Fi: green.
  if (WiFi.status() == WL_CONNECTED) {
    setRGB(0, 255, 0);
    return;
  }

  // Not connected to a client/network: yellow.
  setRGB(255, 180, 0);
}

// ============================================================
// Reset reason and recording state
// ============================================================

const char *resetReasonName(esp_reset_reason_t reason) {
  switch (reason) {
    case ESP_RST_POWERON:
      return "POWERON";

    case ESP_RST_EXT:
      return "EXTERNAL_RESET_PIN";

    case ESP_RST_SW:
      return "SOFTWARE_RESET";

    case ESP_RST_PANIC:
      return "PANIC";

    case ESP_RST_INT_WDT:
      return "INTERRUPT_WDT";

    case ESP_RST_TASK_WDT:
      return "TASK_WDT";

    case ESP_RST_WDT:
      return "OTHER_WDT";

    case ESP_RST_DEEPSLEEP:
      return "DEEPSLEEP";

    case ESP_RST_BROWNOUT:
      return "BROWNOUT";

    case ESP_RST_SDIO:
      return "SDIO";

    default:
      return "OTHER";
  }
}

void loadRecordingStateAndHandleReset() {
  esp_reset_reason_t reason = esp_reset_reason();

  Serial.println();
  Serial.println("---------- RESET DIAGNOSTICS ----------");

  Serial.print("Reset reason: ");
  Serial.println(resetReasonName(reason));

  Serial.print("Reset reason code: ");
  Serial.println((int)reason);

  preferences.begin("session", false);

  // Restore the last saved session state.
  recordingActive = preferences.getBool("active", false);

  // Only toggle the session for a reset-pin reset.
  // Power-on and software resets do not toggle recording.
  if (reason == ESP_RST_EXT) {
    recordingActive = !recordingActive;

    preferences.putBool("active", recordingActive);

    ledMode = LED_RAINBOW;
    rainbowStartedAt = millis();
    rainbowPending = true;

    Serial.println(
      recordingActive
        ? "SESSION STARTED by RST"
        : "SESSION STOPPED by RST"
    );
  } else {
    ledMode = LED_WHITE;
    Serial.println("Session state restored without toggling.");
  }

  preferences.end();

  Serial.print("Recording active: ");
  Serial.println(recordingActive ? "YES" : "NO");

  Serial.println("---------------------------------------");
}

// ============================================================
// CSV recording storage
// ============================================================

void startRecordStorage() {
  filesystemReady = LittleFS.begin(true);

  if (!filesystemReady) {
    Serial.println(
      "ERROR: LittleFS mount failed; CSV recording unavailable"
    );
    return;
  }

  if (!LittleFS.exists(RECORD_FILE)) {
    File f = LittleFS.open(RECORD_FILE, FILE_WRITE);

    if (f) {
      f.println(
        "elapsed_ms,heart_rate_bpm,breath_rate_bpm,"
        "distance,target,heart_valid,breath_valid,distance_valid"
      );

      f.close();
    }
  }

  Serial.println("Flash record storage ready: /records.csv");
}

void recordSampleIfNeeded() {
  unsigned long now = millis();

  if (!recordingActive ||
      !filesystemReady ||
      now - lastRecordWrite < RECORD_INTERVAL_MS) {
    return;
  }

  lastRecordWrite = now;

  File f = LittleFS.open(RECORD_FILE, FILE_APPEND);

  if (!f) {
    Serial.println("ERROR: cannot append session record");
    return;
  }

  bool heartOk =
    heartValid &&
    now - lastHeart < 5000 &&
    heartRate > 0;

  bool breathOk =
    breathValid &&
    now - lastBreath < 5000 &&
    breathRate > 0;

  bool distOk =
    distanceValid &&
    now - lastDistance < 3000;

  f.printf(
    "%lu,%.2f,%.2f,%.2f,%d,%d,%d,%d\n",
    now,
    heartRate,
    breathRate,
    distance,
    targetDetected ? 1 : 0,
    heartOk ? 1 : 0,
    breathOk ? 1 : 0,
    distOk ? 1 : 0
  );

  f.close();
}

// ============================================================
// Radar processing through the official Seeed library
// ============================================================

void updateRadar() {
  if (!mmWave.update(10)) {
    return;
  }

  const unsigned long now = millis();
  float value = 0.0f;
  bool receivedAny = false;

  if (mmWave.getBreathRate(value)) {
    if (isfinite(value) && value > 0.0f) {
      breathRate = value;
      breathValid = true;
      lastBreath = now;
      receivedAny = true;
    }
  }

  if (mmWave.getHeartRate(value)) {
    if (isfinite(value) && value > 0.0f) {
      heartRate = value;
      heartValid = true;
      lastHeart = now;
      receivedAny = true;
    }
  }

  float rawDistance = 0.0f;
  if (mmWave.getDistance(rawDistance)) {
    if (isfinite(rawDistance) && rawDistance > 0.0f) {
      // The previous x100 conversion produced values such as 4000 cm.
      // Treat the library value as centimetres and reject out-of-range data.
      // Verify this unit against measured distances on the actual sensor.
      if (rawDistance >= 1.0f && rawDistance <= 600.0f) {
        distance = rawDistance;
        distanceValid = true;
        targetDetected = true;
        lastDistance = now;
        receivedAny = true;

        Serial.print("Radar raw distance: ");
        Serial.print(rawDistance, 3);
        Serial.print(" | displayed distance: ");
        Serial.print(distance, 1);
        Serial.println(" cm");
      } else {
        Serial.print("Ignoring implausible raw distance: ");
        Serial.println(rawDistance, 3);
      }
    }
  }

  if (receivedAny) {
    lastSensorFrame = now;
  }

  // Expire stale readings rather than presenting old values as current.
  if (heartValid && now - lastHeart > 10000) {
    heartValid = false;
  }
  if (breathValid && now - lastBreath > 10000) {
    breathValid = false;
  }
  if (distanceValid && now - lastDistance > 5000) {
    distanceValid = false;
    targetDetected = false;
  }
}

// ============================================================
// Wi-Fi configuration
// ============================================================

String getAPName() {
  uint64_t chipID = ESP.getEfuseMac();

  char suffix[5];

  snprintf(
    suffix,
    sizeof(suffix),
    "%04X",
    (uint16_t)(chipID & 0xFFFF)
  );

  return String("MR60BHA2-") + suffix;
}

void loadWiFiCredentials() {
  preferences.begin("wifi", true);

  savedSSID = preferences.getString("ssid", "");
  savedPassword = preferences.getString("password", "");

  preferences.end();

  Serial.print("Saved SSID: ");
  Serial.println(savedSSID.length() ? savedSSID : "NONE");
}

void saveWiFiCredentials(
  const String &ssid,
  const String &password
) {
  preferences.begin("wifi", false);

  preferences.putString("ssid", ssid);
  preferences.putString("password", password);

  preferences.end();

  savedSSID = ssid;
  savedPassword = password;

  Serial.println("Wi-Fi credentials saved");
}

void clearWiFiCredentials() {
  preferences.begin("wifi", false);
  preferences.clear();
  preferences.end();

  savedSSID = "";
  savedPassword = "";

  Serial.println("Wi-Fi credentials erased");
}

void startProvisioningAP() {
  Serial.println("Starting configuration access point...");

  apName = getAPName();

  WiFi.mode(WIFI_AP_STA);

  bool ok = WiFi.softAP(
    apName.c_str(),
    "MR60setup"
  );

  provisioningMode = ok;

  if (ok) {
    Serial.print("AP SSID: ");
    Serial.println(apName);

    Serial.println("AP password: MR60setup");

    Serial.print("AP IP: ");
    Serial.println(WiFi.softAPIP());

    Serial.println(
      "Open http://192.168.4.1 to configure Wi-Fi"
    );
  } else {
    Serial.println("ERROR: could not start configuration AP");
  }
}

bool connectToSavedWiFi(bool waitForResult = true) {
  if (!savedSSID.length()) {
    return false;
  }

  Serial.println("Connecting to saved Wi-Fi...");

  Serial.print("SSID: ");
  Serial.println(savedSSID);

  WiFi.mode(
    provisioningMode ? WIFI_AP_STA : WIFI_STA
  );

  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);

  WiFi.begin(
    savedSSID.c_str(),
    savedPassword.c_str()
  );

  wifiConnectStarted = millis();
  lastWiFiAttempt = millis();

  if (waitForResult) {
    while (
      WiFi.status() != WL_CONNECTED &&
      millis() - wifiConnectStarted < WIFI_CONNECT_TIMEOUT
    ) {
      delay(250);
      Serial.print('.');
    }

    Serial.println();
  }

  if (WiFi.status() == WL_CONNECTED) {
    wifiConnected = true;

    Serial.println("Wi-Fi CONNECTED!");

    Serial.print("IP address: ");
    Serial.println(WiFi.localIP());

    Serial.print("RSSI: ");
    Serial.println(WiFi.RSSI());

    return true;
  }

  wifiConnected = false;

  Serial.println(
    "Wi-Fi not connected yet; retry will continue in background."
  );

  return false;
}

// ============================================================
// Sensor JSON
// ============================================================

String getSensorJSON() {
  unsigned long now = millis();

  bool target =
    targetDetected &&
    now - lastDistance < 3000;

  bool breath =
    breathValid &&
    now - lastBreath < 5000 &&
    breathRate > 0;

  bool heart =
    heartValid &&
    now - lastHeart < 5000 &&
    heartRate > 0;

  bool dist =
    distanceValid &&
    now - lastDistance < 3000;

  String json = "{";

  json += "\"target\":";
  json += target ? "true" : "false";

  json += ",\"heart\":";
  json += String(heartRate, 2);

  json += ",\"heart_valid\":";
  json += heart ? "true" : "false";

  json += ",\"breath\":";
  json += String(breathRate, 2);

  json += ",\"breath_valid\":";
  json += breath ? "true" : "false";

  json += ",\"distance\":";
  json += String(distance, 2);
  json += ",\"distance_unit\":\"cm\"";

  json += ",\"distance_valid\":";
  json += dist ? "true" : "false";

  json += ",\"recording\":";
  json += recordingActive ? "true" : "false";

  json += ",\"wifi_connected\":";
  json += (WiFi.status() == WL_CONNECTED) ? "true" : "false";

  json += ",\"ble_connected\":";
  json += bleClientConnected ? "true" : "false";

  json += "}";

  return json;
}

// ============================================================
// BLE server
// ============================================================

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(
    NimBLEServer *server,
    NimBLEConnInfo &info
  ) override {
    bleClientConnected = true;
    Serial.println("BLE client connected");
  }

  void onDisconnect(
    NimBLEServer *server,
    NimBLEConnInfo &info,
    int reason
  ) override {
    bleClientConnected = false;

    Serial.println(
      "BLE client disconnected; restarting advertising"
    );

    NimBLEDevice::startAdvertising();
  }
};

void startBLE() {
  Serial.println();
  Serial.println("--------------------------------");
  Serial.println("STARTING BLUETOOTH BLE");
  Serial.println("--------------------------------");

  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

  bleServer = NimBLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  bleService = bleServer->createService(BLE_SERVICE_UUID);

  bleCharacteristic = bleService->createCharacteristic(
    BLE_CHARACTERISTIC_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
  );

  bleCharacteristic->setValue("{\"status\":\"starting\"}");

  bleService->start();

  NimBLEAdvertising *advertising =
    NimBLEDevice::getAdvertising();

  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setName(BLE_DEVICE_NAME);
  advertising->enableScanResponse(true);

  bool advOK = advertising->start();
  bleStarted = advOK;

  Serial.println(
    advOK
      ? "BLE ADVERTISING STARTED"
      : "ERROR: BLE advertising failed"
  );

  Serial.print("BLE name: ");
  Serial.println(BLE_DEVICE_NAME);

  Serial.print("BLE service UUID: ");
  Serial.println(BLE_SERVICE_UUID);

  Serial.print("BLE characteristic UUID: ");
  Serial.println(BLE_CHARACTERISTIC_UUID);

  Serial.println("--------------------------------");
}

void updateBLE() {
  if (!bleStarted || !bleCharacteristic) {
    return;
  }

  unsigned long now = millis();

  if (now - lastBLENotify < BLE_NOTIFY_INTERVAL) {
    return;
  }

  lastBLENotify = now;

  String json = getSensorJSON();

  bleCharacteristic->setValue(json.c_str());

  if (bleClientConnected) {
    bleCharacteristic->notify();
  }
}

// ============================================================
// HTTP page
// ============================================================

void handleRoot() {
  String html = R"HTML(
<!doctype html>
<html lang="uk">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#090d18">
<title>MR60BHA2 · Monitor</title>
<style>
:root {
  color-scheme: dark;
  --bg:#090d18; --panel:rgba(22,30,49,.68); --line:rgba(255,255,255,.12);
  --text:#f3f6ff; --muted:#9ca9c5; --cyan:#8be9fd; --violet:#c4a7ff;
}
* { box-sizing:border-box; }
body {
  margin:0; min-height:100vh; padding:clamp(16px,4vw,34px);
  color:var(--text); font-family:Inter,ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;
  background:radial-gradient(ellipse at 10% 0%,rgba(75,94,190,.25),transparent 42%),
             radial-gradient(ellipse at 95% 25%,rgba(137,69,211,.17),transparent 35%),var(--bg);
}
main { width:100%; max-width:900px; margin:0 auto; }
header { display:flex; align-items:center; justify-content:space-between; gap:16px; margin:8px 0 24px; }
.brand { display:flex; align-items:center; gap:13px; }
.logo { width:46px;height:46px;display:grid;place-items:center;border:1px solid var(--line);border-radius:15px;background:linear-gradient(135deg,rgba(139,233,253,.2),rgba(196,167,255,.16));font-size:23px; }
h1 { font-size:clamp(24px,5vw,34px); margin:0; letter-spacing:-.04em; }
.subtitle { color:var(--muted); margin-top:4px; font-size:13px; }
.pill { border:1px solid var(--line); background:rgba(255,255,255,.05); border-radius:999px;padding:8px 11px;color:var(--muted);font-size:12px;white-space:nowrap; }
.grid { display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px; }
section { min-width:0; margin:14px 0; padding:20px; border:1px solid var(--line); border-radius:22px; background:var(--panel); backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);box-shadow:0 16px 50px rgba(0,0,0,.16); }
.metric { min-height:142px; margin:0; }
.metric .label { color:var(--muted);font-size:13px; }
.metric .value { font-size:clamp(28px,5vw,40px);font-weight:720;letter-spacing:-.04em;margin:18px 0 4px;overflow-wrap:anywhere; }
.unit { color:var(--muted);font-size:12px;font-weight:500;letter-spacing:0; }
.section-title { margin:0 0 14px;font-size:16px;letter-spacing:-.02em; }
.small { color:var(--muted);font-size:13px;line-height:1.55; }
input,select { width:100%;padding:13px 14px;margin:6px 0 10px;border:1px solid var(--line);border-radius:13px;background:rgba(4,8,18,.52);color:var(--text);font-size:15px;outline:none; }
input:focus,select:focus { border-color:rgba(139,233,253,.65);box-shadow:0 0 0 3px rgba(139,233,253,.08); }
button,.glass-button { display:inline-flex;align-items:center;justify-content:center;gap:8px;width:100%;min-height:45px;padding:12px 15px;margin:6px 0;border:1px solid rgba(255,255,255,.19);border-radius:14px;background:rgba(255,255,255,.075);color:var(--text);font-size:14px;font-weight:650;text-align:center;text-decoration:none;cursor:pointer;transition:background .18s ease,border-color .18s ease,transform .18s ease;backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px); }
button:hover,.glass-button:hover { background:rgba(255,255,255,.14);border-color:rgba(255,255,255,.32); }
button:active,.glass-button:active { transform:scale(.99); }
.primary { background:rgba(115,111,255,.20);border-color:rgba(171,160,255,.36); }
.primary:hover { background:rgba(115,111,255,.3); }
.stop { background:rgba(255,90,120,.13);border-color:rgba(255,120,145,.35); }
.app-buttons { display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px; }
.app-button { background:rgba(255,255,255,.065);border-color:rgba(255,255,255,.2);min-height:52px; }
pre { margin:0;white-space:pre-wrap;overflow-wrap:anywhere;color:#d7e2ff;font:12px/1.6 ui-monospace,SFMono-Regular,Consolas,monospace; }
.status-line { display:flex;align-items:center;gap:9px;color:var(--muted);font-size:13px;margin:8px 0; }
.dot { width:8px;height:8px;border-radius:50%;background:#64748b;box-shadow:0 0 12px currentColor;flex:none; }
.dot.on { background:#4ade80;color:#4ade80; }
.dot.off { background:#fbbf24;color:#fbbf24; }
.recording-state { color:var(--violet);font-weight:650; }
footer { text-align:center;color:#77829c;font-size:12px;padding:18px 0 8px; }
@media(max-width:650px) { .grid {grid-template-columns:1fr;}.metric {min-height:auto;} .metric .value {margin:12px 0 3px;} header {align-items:flex-start;} .pill {display:none;} section {padding:17px;border-radius:18px;} }
@media(max-width:420px) { .app-buttons {grid-template-columns:1fr;} }
</style>
</head>
<body>
<main>
<header>
  <div class="brand"><div class="logo">⌁</div><div><h1>MR60BHA2</h1><div class="subtitle">Radar health monitor · XIAO ESP32-C6</div></div></div>
  <div class="pill">LIVE MONITOR</div>
</header>

<div class="grid">
  <section class="metric"><div class="label">Пульс</div><div class="value"><span id="heart">—</span> <span class="unit">уд/хв</span></div><div class="small" id="heart-state">Очікування даних</div></section>
  <section class="metric"><div class="label">Дихання</div><div class="value"><span id="breath">—</span> <span class="unit">вдихів/хв</span></div><div class="small" id="breath-state">Очікування даних</div></section>
  <section class="metric"><div class="label">Відстань</div><div class="value"><span id="distance">—</span> <span class="unit">см</span></div><div class="small" id="target-state">Ціль не визначена</div></section>
</div>

<section>
  <h2 class="section-title">Стан пристрою</h2>
  <div class="status-line"><span id="wifi-dot" class="dot off"></span><span id="wifi-state">Wi-Fi: перевірка…</span></div>
  <div class="status-line"><span id="ble-dot" class="dot off"></span><span id="ble-state">Bluetooth: перевірка…</span></div>
  <div class="status-line"><span id="rec-dot" class="dot off"></span><span id="record-state">Запис зупинено</span></div>
  <pre id="device-details">Завантаження статусу…</pre>
</section>

<section>
  <h2 class="section-title">Запис вимірювань</h2>
  <p class="small">Запис зберігається у внутрішній пам’яті пристрою. Завантаження CSV через сайт вимкнено.</p>
  <button id="record-button" class="primary" onclick="toggleRecording()">● Почати запис</button>
  <button class="glass-button" onclick="clearRecords()">Очистити збережені дані</button>
  <div id="record-result" class="small" aria-live="polite"></div>
</section>

<section>
  <h2 class="section-title">Налаштування Wi-Fi</h2>
  <button class="glass-button" onclick="scan()">⌕ Знайти мережі Wi-Fi</button>
  <select id="nets" onchange="document.getElementById('ssid').value=this.value"><option value="">Оберіть мережу…</option></select>
  <input id="ssid" placeholder="Назва мережі (SSID)" autocomplete="off">
  <input id="password" type="password" placeholder="Пароль Wi-Fi" autocomplete="new-password">
  <button class="primary" onclick="connectWiFi()">Зберегти та підключитися</button>
  <button class="glass-button" onclick="resetWiFi()">Скинути налаштування Wi-Fi</button>
  <div id="result" class="small" aria-live="polite"></div>
</section>

<section>
  <h2 class="section-title">Застосунок Bluetooth</h2>
  <p class="small">Встанови nRF Connect, щоб знайти пристрій, підключитися через BLE та переглядати сповіщення сенсора.</p>
  <div class="app-buttons">
    <a class="glass-button app-button" href="https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp" target="_blank" rel="noopener">↗ Для Android</a>
    <a class="glass-button app-button" href="https://apps.apple.com/us/app/nrf-connect-for-mobile/id1054362403" target="_blank" rel="noopener">↗ Для iPhone</a>
  </div>
  <p class="small">BLE-пристрій: <b>XIAO-C6</b></p>
</section>
<footer>MR60BHA2 · локальний інтерфейс пристрою</footer>
</main>
<script>
async function api(path, options) {
  const r = await fetch(path, options || {});
  let data = {};
  try { data = await r.json(); } catch(e) {}
  if (!r.ok) throw new Error(data.error || ('HTTP ' + r.status));
  return data;
}
function setDot(id,on) { const el=document.getElementById(id); el.className='dot '+(on?'on':'off'); }
async function refresh() {
  try {
    const [d,dev,ble]=await Promise.all([api('/api/sensor'),api('/api/device'),api('/api/ble')]);
    document.getElementById('heart').textContent=d.heart_valid?Number(d.heart).toFixed(0):'—';
    document.getElementById('breath').textContent=d.breath_valid?Number(d.breath).toFixed(0):'—';
    document.getElementById('distance').textContent=d.distance_valid?Number(d.distance).toFixed(1):'—';
    document.getElementById('heart-state').textContent=d.heart_valid?'Сигнал отримано':'Немає достовірного сигналу';
    document.getElementById('breath-state').textContent=d.breath_valid?'Сигнал отримано':'Немає достовірного сигналу';
    document.getElementById('target-state').textContent=d.target?'Ціль виявлена':'Ціль не визначена';
    setDot('wifi-dot',d.wifi_connected); setDot('ble-dot',d.ble_connected); setDot('rec-dot',d.recording);
    document.getElementById('wifi-state').textContent=d.wifi_connected?'Wi-Fi підключено':'Wi-Fi не підключено';
    document.getElementById('ble-state').textContent=d.ble_connected?'Bluetooth BLE підключено':'Bluetooth очікує підключення';
    const rec=document.getElementById('record-state');
    rec.textContent=d.recording?'● Запис активний — світлодіод пурпурний':'Запис зупинено';
    rec.className=d.recording?'recording-state':'';
    const b=document.getElementById('record-button');
    b.textContent=d.recording?'■ Зупинити запис':'● Почати запис';
    b.className=d.recording?'stop':'primary';
    let lines=[];
    lines.push('Пристрій: '+(dev.name||'MR60BHA2'));
    lines.push('Точка налаштування: '+(dev.ap_ssid||'—'));
    lines.push('Адреса точки доступу: http://'+(dev.ap_ip||'192.168.4.1'));
    lines.push('IP у Wi-Fi мережі: '+(dev.wifi_ip||'не отримано'));
    lines.push('Пам’ять запису: '+(dev.storage_ready?'готова':'недоступна'));
    document.getElementById('device-details').textContent=lines.join('\n');
  } catch(e) {
    document.getElementById('device-details').textContent='Пристрій тимчасово недоступний';
  }
}
async function toggleRecording() {
  const b=document.getElementById('record-button'); b.disabled=true;
  try {
    const d=await api('/api/recording/toggle',{method:'POST'});
    document.getElementById('record-result').textContent=d.message||'Стан запису змінено';
    await refresh();
  } catch(e) { document.getElementById('record-result').textContent='Не вдалося змінити запис: '+e.message; }
  finally { b.disabled=false; }
}
async function clearRecords() {
  if (!confirm('Очистити всі збережені дані запису?')) return;
  try { const d=await api('/api/records/clear',{method:'POST'}); document.getElementById('record-result').textContent=d.status==='cleared'?'Збережені дані очищено':'Готово'; }
  catch(e) { document.getElementById('record-result').textContent='Помилка: '+e.message; }
}
async function scan() {
  document.getElementById('result').textContent='Сканування мереж…';
  try {
    const a=await api('/api/wifi/scan'); const sel=document.getElementById('nets');
    sel.innerHTML='<option value="">Оберіть мережу…</option>';
    a.forEach(n=>{const o=document.createElement('option');o.value=n.ssid;o.textContent=n.ssid+' ('+n.rssi+' dBm)';sel.appendChild(o);});
    document.getElementById('result').textContent='Знайдено мереж: '+a.length;
  } catch(e) { document.getElementById('result').textContent='Не вдалося просканувати Wi-Fi: '+e.message; }
}
async function connectWiFi() {
  const ssid=document.getElementById('ssid').value;
  const password=document.getElementById('password').value;
  if(!ssid.trim()){document.getElementById('result').textContent='Введи назву Wi-Fi мережі';return;}
  const body='ssid='+encodeURIComponent(ssid)+'&password='+encodeURIComponent(password);
  try {
    const d=await api('/api/wifi/config',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});
    document.getElementById('result').textContent=d.message||'Дані збережено. Пристрій підключається…';
  } catch(e) { document.getElementById('result').textContent='Підключення могло перезапуститися. Перевір IP пристрою. ('+e.message+')'; }
}
async function resetWiFi() {
  if(!confirm('Скинути збережені налаштування Wi-Fi?'))return;
  try { await api('/api/wifi/reset',{method:'POST'}); document.getElementById('result').textContent='Налаштування скидаються…'; }
  catch(e) { document.getElementById('result').textContent='Пристрій перезапускається або вже недоступний.'; }
}
setInterval(refresh,1500); refresh();
</script>
</body>
</html>
)HTML";

  server.send(200, "text/html; charset=utf-8", html);
}

// ============================================================
// HTTP API handlers
// ============================================================

void handleDevice() {
  String json = "{";

  json += "\"name\":\"" + String(DEVICE_NAME) + "\",";
  json += "\"ble_name\":\"" + String(BLE_DEVICE_NAME) + "\",";
  json += "\"ble_started\":";
  json += bleStarted ? "true" : "false";

  json += ",\"ap_ssid\":\"" + getAPName() + "\",";
  json += "\"ap_ip\":\"" + WiFi.softAPIP().toString() + "\",";
  json += "\"wifi_connected\":";
  json += (WiFi.status() == WL_CONNECTED) ? "true" : "false";

  json += ",\"recording\":";
  json += recordingActive ? "true" : "false";

  json += ",\"storage_ready\":";
  json += filesystemReady ? "true" : "false";

  if (WiFi.status() == WL_CONNECTED) {
    json += ",\"wifi_ip\":\"";
    json += WiFi.localIP().toString();
    json += "\",\"rssi\":";
    json += String(WiFi.RSSI());
  }

  json += "}";

  server.send(200, "application/json", json);
}

void handleWiFiStatus() {
  String ssid =
    WiFi.status() == WL_CONNECTED ? WiFi.SSID() : "";

  String ip =
    WiFi.status() == WL_CONNECTED
      ? WiFi.localIP().toString()
      : "";

  String json = "{\"connected\":";

  json += (WiFi.status() == WL_CONNECTED) ? "true" : "false";

  json += ",\"ssid\":\"" + ssid + "\",";
  json += "\"ip\":\"" + ip + "\",";
  json += "\"rssi\":";

  json += String(
    WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0
  );

  json += "}";

  server.send(200, "application/json", json);
}

void handleWiFiScan() {
  Serial.println("Wi-Fi scan started");

  int count = WiFi.scanNetworks();

  String json = "[";

  for (int i = 0; i < count; i++) {
    if (i) {
      json += ",";
    }

    String ssid = WiFi.SSID(i);

    ssid.replace("\\", "\\\\");
    ssid.replace("\"", "\\\"");

    json += "{\"ssid\":\"" + ssid + "\",";
    json += "\"rssi\":" + String(WiFi.RSSI(i)) + ",";
    json += "\"secured\":";

    json +=
      (WiFi.encryptionType(i) == WIFI_AUTH_OPEN)
        ? "false"
        : "true";

    json += "}";
  }

  json += "]";

  WiFi.scanDelete();

  server.send(200, "application/json", json);
}

void handleWiFiConfig() {
  if (!server.hasArg("ssid") ||
      !server.hasArg("password") ||
      server.arg("ssid").length() == 0) {

    server.send(
      400,
      "application/json",
      "{\"error\":\"ssid and password required\"}"
    );

    return;
  }

  saveWiFiCredentials(
    server.arg("ssid"),
    server.arg("password")
  );

  if (!provisioningMode) {
    apName = getAPName();

    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(apName.c_str(), "MR60setup");

    provisioningMode = true;
  }

  server.send(
    200,
    "application/json",
    "{\"status\":\"saved\",\"message\":\"Credentials saved; reconnecting to Wi-Fi.\"}"
  );

  delay(250);

  connectToSavedWiFi(true);

  if (!wifiConnected && !provisioningMode) {
    startProvisioningAP();
  }
}

void handleWiFiReset() {
  clearWiFiCredentials();

  server.send(
    200,
    "application/json",
    "{\"status\":\"reset\",\"message\":\"Wi-Fi credentials erased; restarting.\"}"
  );

  delay(500);
  ESP.restart();
}

void handleSensor() {
  server.send(200, "application/json", getSensorJSON());
}

void handleRecordingToggle() {
  if (!filesystemReady) {
    server.send(503, "application/json", "{\"error\":\"storage unavailable\"}");
    return;
  }

  recordingActive = !recordingActive;
  preferences.begin("session", false);
  preferences.putBool("active", recordingActive);
  preferences.end();

  if (recordingActive) {
    lastRecordWrite = 0;
    if (!LittleFS.exists(RECORD_FILE)) {
      File f = LittleFS.open(RECORD_FILE, FILE_WRITE);
      if (f) {
        f.println("elapsed_ms,heart_rate_bpm,breath_rate_bpm,distance,target,heart_valid,breath_valid,distance_valid");
        f.close();
      }
    }
  }

  String json = "{\"recording\":";
  json += recordingActive ? "true" : "false";
  json += ",\"message\":\"";
  json += recordingActive ? "Запис розпочато" : "Запис зупинено";
  json += "\"}";
  server.send(200, "application/json", json);

  Serial.println(recordingActive ? "Recording started from web UI" : "Recording stopped from web UI");
}

void handleRecordsClear() {
  if (!filesystemReady) {
    server.send(
      503,
      "application/json",
      "{\"error\":\"storage unavailable\"}"
    );

    return;
  }

  if (LittleFS.exists(RECORD_FILE)) {
    LittleFS.remove(RECORD_FILE);
  }

  File f = LittleFS.open(RECORD_FILE, FILE_WRITE);

  if (f) {
    f.println(
      "elapsed_ms,heart_rate_bpm,breath_rate_bpm,"
      "distance,target,heart_valid,breath_valid,distance_valid"
    );

    f.close();
  }

  server.send(
    200,
    "application/json",
    "{\"status\":\"cleared\"}"
  );
}

void handleBLE() {
  String json = "{\"started\":";

  json += bleStarted ? "true" : "false";

  json += ",\"connected\":";
  json += bleClientConnected ? "true" : "false";

  json += ",\"name\":\"";
  json += BLE_DEVICE_NAME;

  json += "\",\"service_uuid\":\"";
  json += BLE_SERVICE_UUID;

  json += "\",\"characteristic_uuid\":\"";
  json += BLE_CHARACTERISTIC_UUID;

  json += "\"}";

  server.send(200, "application/json", json);
}

void startWebServer() {
  server.on("/", HTTP_GET, handleRoot);

  server.on(
    "/api/device",
    HTTP_GET,
    handleDevice
  );

  server.on(
    "/api/wifi/status",
    HTTP_GET,
    handleWiFiStatus
  );

  server.on(
    "/api/wifi/scan",
    HTTP_GET,
    handleWiFiScan
  );

  server.on(
    "/api/wifi/config",
    HTTP_POST,
    handleWiFiConfig
  );

  server.on(
    "/api/wifi/reset",
    HTTP_POST,
    handleWiFiReset
  );

  server.on(
    "/api/sensor",
    HTTP_GET,
    handleSensor
  );

  server.on(
    "/api/ble",
    HTTP_GET,
    handleBLE
  );

  server.on(
    "/api/records/clear",
    HTTP_POST,
    handleRecordsClear
  );

  server.on(
    "/api/recording/toggle",
    HTTP_POST,
    handleRecordingToggle
  );

  server.begin();

  Serial.println("HTTP server started");
}

// ============================================================
// SETUP
// ============================================================

void setup() {
  Serial.begin(115200);
  delay(1500);

  bootStartedAt = millis();

  // Initialize the external RGB LED.
  rgbLed.begin();
  rgbLed.setBrightness(70);
  rgbLed.clear();
  rgbLed.show();

  setRGB(255, 255, 255);

  Serial.println();
  Serial.println("======================================");
  Serial.println("MR60BHA2 SMART DEVICE - XIAO ESP32-C6");
  Serial.println("======================================");

  Serial.print("External RGB LED GPIO: ");
  Serial.println(RGB_LED_PIN);

  mmWave.begin(&radarSerial);

  Serial.println("MR60BHA2 initialized with Seeed mmWave library");

  apName = getAPName();

  loadWiFiCredentials();

  // Restore recording state, then always show the startup rainbow.
  loadRecordingStateAndHandleReset();
  ledMode = LED_RAINBOW;
  rainbowStartedAt = millis();
  lastRainbowFrame = 0;
  rainbowPending = false;

  startRecordStorage();

  // Start the configuration AP.
  // It remains available when station Wi-Fi connects.
  startProvisioningAP();

  // Start BLE independently of Wi-Fi.
  startBLE();

  bool connected = connectToSavedWiFi(false);

  if (rainbowPending) {
    rainbowStartedAt = millis();
    rainbowPending = false;
  }

  if (!connected) {
    Serial.println(
      "Wi-Fi connection pending; setup page remains at http://192.168.4.1"
    );
  }

  startWebServer();

  Serial.println("--------------------------------------");

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("NORMAL WI-FI MODE");

    Serial.print("Device IP: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("PROVISIONING / RETRY MODE");

    Serial.print("Connect to AP: ");
    Serial.println(getAPName());

    Serial.println("Password: MR60setup");
    Serial.println("Open: http://192.168.4.1");
  }

  Serial.print("BLE name: ");
  Serial.println(BLE_DEVICE_NAME);

  Serial.println("--------------------------------------");
}

// ============================================================
// MAIN LOOP
// ============================================================

void loop() {
  updateRadar();

  server.handleClient();

  updateBLE();

  recordSampleIfNeeded();

  updateStatusLED();

  unsigned long now = millis();

  // Wi-Fi connected.
  if (WiFi.status() == WL_CONNECTED) {
    if (!wifiConnected) {
      wifiConnected = true;

      Serial.print("Wi-Fi restored. IP address: ");
      Serial.println(WiFi.localIP());
    }

    if (now - lastWiFiStatusPrint > 30000) {
      lastWiFiStatusPrint = now;

      Serial.print("Wi-Fi OK, IP: ");
      Serial.print(WiFi.localIP());

      Serial.print(", RSSI: ");
      Serial.println(WiFi.RSSI());
    }

  } else {
    // Wi-Fi disconnected.
    if (wifiConnected) {
      wifiConnected = false;

      Serial.println(
        "Wi-Fi connection lost; will retry automatically."
      );

      if (!provisioningMode) {
        startProvisioningAP();
      }
    }

    // Retry saved Wi-Fi credentials in the background.
    if (savedSSID.length() &&
        now - lastWiFiAttempt >= WIFI_RETRY_INTERVAL) {

      Serial.println("Retrying saved Wi-Fi connection...");

      connectToSavedWiFi(false);
    }
  }
}
