
#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include <HardwareSerial.h>
#include <NimBLEDevice.h>
#include <Adafruit_NeoPixel.h>
#include "Seeed_Arduino_mmWave.h"

// ============================================================
// XIAO ESP32-C6 + MR60BHA2
// Wi-Fi + Web UI + BLE control + sensor measurements + RGB LED
// ============================================================

// ---------------- LED ----------------
#define LED_PIN D1
#define LED_COUNT 1
#define LED_BRIGHTNESS 60

Adafruit_NeoPixel pixels(
  LED_COUNT, LED_PIN, NEO_GRB + NEO_KHZ800
);

// ---------------- RADAR ----------------
// The official Seeed example uses HardwareSerial(0) on ESP32.
// Check the selected board's USB/serial configuration if needed.
HardwareSerial mmWaveSerial(0);
SEEED_MR60BHA2 mmWave;

// ---------------- WIFI ----------------
WebServer server(80);
Preferences prefs;

const char* AP_NAME = "XIAO-MR60-Setup";
const char* AP_PASS = "MR60setup26";

String wifiSSID;
String wifiPassword;

bool wifiAttemptStarted = false;
bool wifiTimeoutReported = false;
uint32_t wifiStartAt = 0;

// ---------------- BLE ----------------
static const char* BLE_NAME = "XIAO-C6";

static const char* SERVICE_UUID =
  "7a1e0001-8b4f-4d2a-9c21-1234567890ab";

static const char* CHARACTERISTIC_UUID =
  "7a1e0002-8b4f-4d2a-9c21-1234567890ab";

NimBLECharacteristic* bleCharacteristic = nullptr;

volatile bool bleConnected = false;
volatile bool bleCommandPending = false;
String pendingBleCommand;

// ---------------- MEASUREMENT ----------------
enum RecordMode {
  RECORD_STOPPED,
  RECORD_WIFI,
  RECORD_BLE
};

RecordMode recordMode = RECORD_STOPPED;

float heartRate = 0;
float breathRate = 0;
float distanceMeters = 0;

bool heartValid = false;
bool breathValid = false;
bool distanceValid = false;
bool targetDetected = false;

uint32_t lastNotifyAt = 0;
uint32_t lastStatusAt = 0;

// ---------------- LED STATES ----------------
enum LedMode {
  LED_STARTUP,
  LED_READY,
  LED_WIFI_RECORDING,
  LED_BLE_RECORDING,
  LED_ERROR,
  LED_FINISHED
};

LedMode ledMode = LED_STARTUP;

struct RGB {
  uint8_t r;
  uint8_t g;
  uint8_t b;
};

const RGB rainbow[] = {
  {255,   0,   0},  // Red
  {255,  70,   0},  // Orange
  {255, 255,   0},  // Yellow
  {  0, 255,   0},  // Green
  {  0, 255, 255},  // Cyan
  {  0,   0, 255},  // Blue
  {128,   0, 255},  // Purple
  {255,   0, 255},  // Magenta
  {255, 255, 255}   // White
};

const int RAINBOW_COUNT = sizeof(rainbow) / sizeof(rainbow[0]);

uint32_t ledStartedAt = 0;
uint32_t lastLedFrameAt = 0;
uint32_t finishFlashAt = 0;

const uint32_t WHITE_TIME_MS = 600;
const uint32_t FADE_TIME_MS = 350;
const uint32_t FINISH_FLASH_MS = 500;

// ============================================================
// LED
// ============================================================

void showRGB(uint8_t r, uint8_t g, uint8_t b) {
  pixels.setPixelColor(0, pixels.Color(r, g, b));
  pixels.show();
}

RGB blend(const RGB& a, const RGB& b, float t) {
  if (t < 0) t = 0;
  if (t > 1) t = 1;

  RGB out;
  out.r = (uint8_t)(a.r + ((int)b.r - a.r) * t);
  out.g = (uint8_t)(a.g + ((int)b.g - a.g) * t);
  out.b = (uint8_t)(a.b + ((int)b.b - a.b) * t);
  return out;
}

void setLedMode(LedMode mode) {
  ledMode = mode;
  ledStartedAt = millis();

  switch (mode) {
    case LED_STARTUP:
      showRGB(255, 255, 255);
      Serial.println("LED: WHITE startup");
      break;

    case LED_READY:
      showRGB(255, 0, 255);
      Serial.println("LED: MAGENTA ready");
      break;

    case LED_WIFI_RECORDING:
      showRGB(0, 255, 0);
      Serial.println("LED: GREEN Wi-Fi assessment");
      break;

    case LED_BLE_RECORDING:
      showRGB(128, 0, 255);
      Serial.println("LED: PURPLE BLE assessment");
      break;

    case LED_ERROR:
      showRGB(255, 0, 0);
      Serial.println("LED: RED error");
      break;

    case LED_FINISHED:
      finishFlashAt = millis();
      showRGB(0, 255, 255);
      Serial.println("LED: CYAN finished");
      break;
  }
}

void updateLed() {
  uint32_t now = millis();

  if (now - lastLedFrameAt < 20) return;
  lastLedFrameAt = now;

  if (ledMode == LED_STARTUP) {
    uint32_t elapsed = now - ledStartedAt;

    // First show pure white.
    if (elapsed < WHITE_TIME_MS) {
      showRGB(255, 255, 255);
      return;
    }

    elapsed -= WHITE_TIME_MS;

    uint32_t animationLength =
      (RAINBOW_COUNT - 1) * FADE_TIME_MS;

    if (elapsed >= animationLength) {
      setLedMode(LED_READY);
      return;
    }

    int segment = elapsed / FADE_TIME_MS;
    uint32_t segmentTime = elapsed % FADE_TIME_MS;
    float t = (float)segmentTime / FADE_TIME_MS;

    RGB c = blend(rainbow[segment], rainbow[segment + 1], t);
    showRGB(c.r, c.g, c.b);
    return;
  }

  if (ledMode == LED_FINISHED) {
    if (now - finishFlashAt >= FINISH_FLASH_MS) {
      setLedMode(LED_READY);
    }
  }
}

// ============================================================
// MEASUREMENT STATE
// ============================================================

void startAssessment(RecordMode mode) {
  recordMode = mode;

  if (mode == RECORD_WIFI) {
    setLedMode(LED_WIFI_RECORDING);
    Serial.println("Assessment started via Wi-Fi");
  } else if (mode == RECORD_BLE) {
    setLedMode(LED_BLE_RECORDING);
    Serial.println("Assessment started via BLE");
  }
}

void stopAssessment() {
  if (recordMode == RECORD_STOPPED) return;

  recordMode = RECORD_STOPPED;
  setLedMode(LED_FINISHED);
  Serial.println("Assessment stopped");
}

// ============================================================
// JSON
// ============================================================

String sensorJson() {
  String s = "{";

  s += "\"target\":";
  s += targetDetected ? "true" : "false";

  s += ",\"heart\":";
  s += String(heartRate, 1);

  s += ",\"heart_valid\":";
  s += heartValid ? "true" : "false";

  s += ",\"breath\":";
  s += String(breathRate, 1);

  s += ",\"breath_valid\":";
  s += breathValid ? "true" : "false";

  s += ",\"distance\":";
  s += String(distanceMeters, 2);

  s += ",\"distance_valid\":";
  s += distanceValid ? "true" : "false";

  s += ",\"recording\":";
  s += recordMode != RECORD_STOPPED ? "true" : "false";

  s += ",\"record_mode\":\"";
  if (recordMode == RECORD_WIFI) s += "wifi";
  else if (recordMode == RECORD_BLE) s += "ble";
  else s += "stop";
  s += "\"";

  s += ",\"wifi_connected\":";
  s += WiFi.status() == WL_CONNECTED ? "true" : "false";

  s += ",\"ble_connected\":";
  s += bleConnected ? "true" : "false";

  s += "}";
  return s;
}

String deviceJson() {
  String s = "{";

  s += "\"device\":\"XIAO-C6\",";
  s += "\"radar\":\"MR60BHA2\",";
  s += "\"wifi_connected\":";
  s += WiFi.status() == WL_CONNECTED ? "true" : "false";

  s += ",\"wifi_ssid\":\"";
  s += WiFi.SSID();
  s += "\"";

  s += ",\"wifi_ip\":\"";
  s += WiFi.localIP().toString();
  s += "\"";

  s += ",\"ap_ip\":\"";
  s += WiFi.softAPIP().toString();
  s += "\"";

  s += ",\"ble_connected\":";
  s += bleConnected ? "true" : "false";

  s += "}";
  return s;
}

// ============================================================
// SENSOR UPDATE - OFFICIAL SEEED LIBRARY
// ============================================================

void updateRadar() {
  if (!mmWave.update(10)) return;

  float value;

  if (mmWave.getHeartRate(value)) {
    heartRate = value;
    heartValid = (value > 0 && value < 250);
  }

  if (mmWave.getBreathRate(value)) {
    breathRate = value;
    breathValid = (value > 0 && value < 100);
  }

  if (mmWave.getDistance(value)) {
    distanceMeters = value;
    distanceValid = (value > 0 && value < 20);
  }

  targetDetected = mmWave.isHumanDetected();
}

// ============================================================
// BLE CALLBACKS
// NimBLE-Arduino 2.x
// ============================================================

class MyServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(
    NimBLEServer* server,
    NimBLEConnInfo& connInfo
  ) override {
    bleConnected = true;
    Serial.println("BLE connected");
  }

  void onDisconnect(
    NimBLEServer* server,
    NimBLEConnInfo& connInfo,
    int reason
  ) override {
    bleConnected = false;
    Serial.println("BLE disconnected");
    NimBLEDevice::startAdvertising();
  }
};

class MyCharacteristicCallbacks :
  public NimBLECharacteristicCallbacks {

  void onWrite(
    NimBLECharacteristic* characteristic,
    NimBLEConnInfo& connInfo
  ) override {
    std::string raw = characteristic->getValue();
    String command = String(raw.c_str());
    command.trim();
    command.toUpperCase();

    // Defer state changes to loop().
    pendingBleCommand = command;
    bleCommandPending = true;
  }
};

void setupBLE() {
  NimBLEDevice::init(BLE_NAME);

  NimBLEServer* serverBLE = NimBLEDevice::createServer();
  serverBLE->setCallbacks(new MyServerCallbacks());

  NimBLEService* service = serverBLE->createService(SERVICE_UUID);

  bleCharacteristic = service->createCharacteristic(
    CHARACTERISTIC_UUID,
    NIMBLE_PROPERTY::READ |
    NIMBLE_PROPERTY::WRITE |
    NIMBLE_PROPERTY::WRITE_NR |
    NIMBLE_PROPERTY::NOTIFY
  );

  bleCharacteristic->setCallbacks(new MyCharacteristicCallbacks());
  bleCharacteristic->setValue("{\"status\":\"ready\"}");

  service->start();

  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setName(BLE_NAME);
  advertising->start();

  Serial.println("BLE advertising started");
  Serial.print("BLE name: ");
  Serial.println(BLE_NAME);
}

void processBleCommand() {
  if (!bleCommandPending) return;

  String command = pendingBleCommand;
  bleCommandPending = false;

  if (command == "START") {
    startAssessment(RECORD_BLE);
  } else if (command == "STOP") {
    stopAssessment();
  } else if (command == "STATUS") {
    // Status will be sent by the next notification.
  } else {
    Serial.print("Unknown BLE command: ");
    Serial.println(command);
  }
}

void updateBleNotifications() {
  if (!bleConnected || bleCharacteristic == nullptr) return;

  uint32_t now = millis();
  if (now - lastNotifyAt < 500) return;

  lastNotifyAt = now;

  String s = sensorJson();
  bleCharacteristic->setValue(s.c_str());
  bleCharacteristic->notify();
}

// ============================================================
// WIFI CREDENTIALS / CONNECTION
// ============================================================

void loadCredentials() {
  prefs.begin("wifi", true);
  wifiSSID = prefs.getString("ssid", "");
  wifiPassword = prefs.getString("password", "");
  prefs.end();
}

void saveCredentials(const String& ssid, const String& password) {
  prefs.begin("wifi", false);
  prefs.putString("ssid", ssid);
  prefs.putString("password", password);
  prefs.end();
}

void setupWiFi() {
  WiFi.mode(WIFI_AP_STA);
  WiFi.setAutoReconnect(true);

  // Keep setup access available even when router connection works.
  bool apStarted = WiFi.softAP(AP_NAME, AP_PASS);

  Serial.println(apStarted ? "Setup AP started" : "Setup AP failed");
  Serial.print("Setup SSID: ");
  Serial.println(AP_NAME);
  Serial.print("Setup password: ");
  Serial.println(AP_PASS);
  Serial.print("Setup URL: http://");
  Serial.println(WiFi.softAPIP());

  loadCredentials();

  if (wifiSSID.length() == 0) {
    Serial.println("No saved router credentials");
    return;
  }

  Serial.print("Connecting to router: ");
  Serial.println(wifiSSID);

  WiFi.begin(wifiSSID.c_str(), wifiPassword.c_str());

  wifiAttemptStarted = true;
  wifiStartAt = millis();
}

void checkWiFi() {
  if (!wifiAttemptStarted) return;

  if (WiFi.status() == WL_CONNECTED) {
    if (!wifiTimeoutReported) {
      wifiTimeoutReported = true;
      Serial.println("Router Wi-Fi connected");
      Serial.print("Router IP: ");
      Serial.println(WiFi.localIP());
    }
    return;
  }

  if (!wifiTimeoutReported && millis() - wifiStartAt > 15000) {
    wifiTimeoutReported = true;
    Serial.println("Router connection timed out.");
    Serial.println("Setup AP remains available.");
  }
}

// ============================================================
// WEB PAGE
// ============================================================

String makeWebPage() {
  String p = R"HTML(
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MR60BHA2 Control</title>
<style>
body{font-family:Arial,sans-serif;margin:18px;max-width:760px;background:#f5f6f8;color:#222}
.card{background:white;padding:16px;margin:12px 0;border-radius:12px;border:1px solid #ddd}
button,input{font-size:16px;padding:10px;margin:5px 0;max-width:100%}
button{cursor:pointer;border-radius:8px;border:1px solid #aaa;background:#eee}
.primary{background:#dcecff}
.stop{background:#ffe2e2}
pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f6f6f6;padding:10px;border-radius:8px}
small{color:#555}
</style>
</head>
<body>
<h2>XIAO ESP32-C6 / MR60BHA2</h2>

<div class="card">
<h3>Bluetooth control</h3>
<p>Connect to the device over Bluetooth to receive measurements and control the assessment.</p>
<button class="primary" onclick="connectBLE()">Connect Bluetooth</button>
<button class="primary" onclick="startBLE()" id="bleStart" disabled>Start assessment via BLE</button>
<button class="stop" onclick="stopBLE()" id="bleStop" disabled>Stop assessment via BLE</button>
<p id="bleStatus">Bluetooth not connected.</p>
<small id="bleHint"></small>
</div>

<div class="card">
<h3>Wi-Fi assessment control</h3>
<button class="primary" onclick="apiRecord('wifi')">Start via Wi-Fi</button>
<button class="stop" onclick="apiRecord('stop')">Stop assessment</button>
<p><small>These controls use the device's HTTP API.</small></p>
</div>

<div class="card">
<h3>Wi-Fi setup</h3>
<form method="POST" action="/wifi/save">
<label>Router SSID</label><br>
<input name="ssid" required autocomplete="off"><br>
<label>Router password</label><br>
<input name="password" type="password" autocomplete="new-password"><br>
<button type="submit">Save Wi-Fi credentials and restart</button>
</form>
</div>

<div class="card">
<h3>Live sensor data</h3>
<pre id="sensor">Loading...</pre>
</div>

<div class="card">
<h3>Device status</h3>
<pre id="device">Loading...</pre>
</div>

<script>
let bleDevice = null;
let bleServer = null;
let bleCharacteristic = null;

const serviceUUID = '7a1e0001-8b4f-4d2a-9c21-1234567890ab';
const characteristicUUID = '7a1e0002-8b4f-4d2a-9c21-1234567890ab';

function setBleStatus(message) {
  document.getElementById('bleStatus').textContent = message;
}

function updateBleButtons(connected) {
  document.getElementById('bleStart').disabled = !connected;
  document.getElementById('bleStop').disabled = !connected;
}

async function connectBLE() {
  if (!navigator.bluetooth) {
    setBleStatus('Web Bluetooth is unavailable in this browser or page context.');
    document.getElementById('bleHint').textContent =
      'Try a supported Chromium browser in a secure context, or use an Android BLE client.';
    return;
  }

  try {
    setBleStatus('Choose XIAO-C6 in the Bluetooth picker...');

    bleDevice = await navigator.bluetooth.requestDevice({
      filters: [{name: 'XIAO-C6'}],
      optionalServices: [serviceUUID]
    });

    bleDevice.addEventListener('gattserverdisconnected', () => {
      bleCharacteristic = null;
      updateBleButtons(false);
      setBleStatus('Bluetooth disconnected.');
    });

    bleServer = await bleDevice.gatt.connect();
    const service = await bleServer.getPrimaryService(serviceUUID);
    bleCharacteristic = await service.getCharacteristic(characteristicUUID);

    await bleCharacteristic.startNotifications();
    bleCharacteristic.addEventListener(
      'characteristicvaluechanged',
      event => {
        const bytes = event.target.value;
        const text = new TextDecoder().decode(bytes);
        try {
          document.getElementById('sensor').textContent =
            JSON.stringify(JSON.parse(text), null, 2);
        } catch (e) {
          document.getElementById('sensor').textContent = text;
        }
      }
    );

    updateBleButtons(true);
    setBleStatus('Connected to ' + bleDevice.name + ' over Bluetooth.');
    document.getElementById('bleHint').textContent =
      'Live measurements will appear below when notifications arrive.';
  } catch (e) {
    setBleStatus('Bluetooth connection failed: ' + e.message);
  }
}

async function sendBLECommand(command) {
  if (!bleCharacteristic) {
    setBleStatus('Connect Bluetooth first.');
    return;
  }

  try {
    await bleCharacteristic.writeValue(new TextEncoder().encode(command));
    setBleStatus('Bluetooth command sent: ' + command);
  } catch (e) {
    setBleStatus('Could not send command: ' + e.message);
  }
}

function startBLE() {
  sendBLECommand('START');
}

function stopBLE() {
  sendBLECommand('STOP');
}

async function apiRecord(mode) {
  try {
    const response = await fetch('/api/record?mode=' + encodeURIComponent(mode),
                                 {method:'POST'});
    const result = await response.json();
    document.getElementById('sensor').textContent =
      JSON.stringify(result, null, 2);
  } catch(e) {
    alert('API error: ' + e.message);
  }
}

async function refreshStatus() {
  try {
    const a = await fetch('/api/sensor');
    document.getElementById('sensor').textContent =
      JSON.stringify(await a.json(), null, 2);

    const b = await fetch('/api/device');
    document.getElementById('device').textContent =
      JSON.stringify(await b.json(), null, 2);
  } catch(e) {}
}

setInterval(refreshStatus, 1500);
refreshStatus();
</script>
</body>
</html>
)HTML";

  return p;
}

// ============================================================
// HTTP ROUTES
// ============================================================

void setupWebServer() {
  server.on("/", HTTP_GET, []() {
    server.send(200, "text/html; charset=utf-8", makeWebPage());
  });

  server.on("/wifi/save", HTTP_POST, []() {
    String ssid = server.arg("ssid");
    String password = server.arg("password");
    ssid.trim();

    if (ssid.length() == 0) {
      server.send(400, "text/plain", "SSID is required");
      return;
    }

    saveCredentials(ssid, password);

    server.send(
      200, "text/html; charset=utf-8",
      "<html><body><h3>Credentials saved. Restarting...</h3></body></html>"
    );

    delay(500);
    ESP.restart();
  });

  server.on("/api/sensor", HTTP_GET, []() {
    server.send(200, "application/json", sensorJson());
  });

  server.on("/api/device", HTTP_GET, []() {
    server.send(200, "application/json", deviceJson());
  });

  server.on("/api/ble", HTTP_GET, []() {
    String s = "{\"connected\":";
    s += bleConnected ? "true" : "false";
    s += ",\"name\":\"XIAO-C6\"}";
    server.send(200, "application/json", s);
  });

  server.on("/api/wifi/status", HTTP_GET, []() {
    server.send(200, "application/json", deviceJson());
  });

  auto recordHandler = []() {
    String mode = server.arg("mode");
    mode.toLowerCase();

    if (mode == "wifi") {
      startAssessment(RECORD_WIFI);
    } else if (mode == "ble") {
      startAssessment(RECORD_BLE);
    } else if (mode == "stop") {
      stopAssessment();
    } else {
      server.send(
        400, "application/json",
        "{\"error\":\"mode must be wifi, ble or stop\"}"
      );
      return;
    }

    server.send(200, "application/json", sensorJson());
  };

  server.on("/api/record", HTTP_GET, recordHandler);
  server.on("/api/record", HTTP_POST, recordHandler);

  server.on("/api/wifi/reset", HTTP_POST, []() {
    prefs.begin("wifi", false);
    prefs.remove("ssid");
    prefs.remove("password");
    prefs.end();

    server.send(200, "text/plain", "Wi-Fi credentials cleared. Restarting.");
    delay(300);
    ESP.restart();
  });

  server.onNotFound([]() {
    server.send(404, "text/plain", "Not found");
  });

  server.begin();
  Serial.println("HTTP server started");
}

// ============================================================
// SETUP
// ============================================================

void setup() {
  Serial.begin(115200);
  delay(150);

  Serial.println();
  Serial.println("====================================");
  Serial.println("XIAO ESP32-C6 + MR60BHA2");
  Serial.println("Wi-Fi + BLE + RGB LED");
  Serial.println("====================================");

  pixels.begin();
  pixels.setBrightness(LED_BRIGHTNESS);
  pixels.clear();
  pixels.show();

  // White immediately, then non-blocking fade animation.
  setLedMode(LED_STARTUP);

  // Use the official Seeed sensor library.
  mmWave.begin(&mmWaveSerial);
  Serial.println("MR60BHA2 library initialized");

  setupBLE();
  setupWiFi();
  setupWebServer();

  Serial.println("Startup complete");
  Serial.println("Open setup page at the printed AP IP.");
}

// ============================================================
// LOOP
// ============================================================

void loop() {
  updateRadar();

  server.handleClient();
  processBleCommand();
  updateBleNotifications();
  checkWiFi();
  updateLed();

  if (millis() - lastStatusAt >= 5000) {
    lastStatusAt = millis();
    Serial.println(sensorJson());
  }

  delay(1);
}