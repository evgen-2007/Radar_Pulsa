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

  // BLE client connected: purple.
  if (bleClientConnected) {
    setRGB(150, 0, 255);
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
      // Assumption: library distance is metres; convert to centimetres.
      distance = rawDistance * 100.0f;
      distanceValid = true;
      targetDetected = true;
      lastDistance = now;
      receivedAny = true;

      Serial.print("Radar raw distance: ");
      Serial.print(rawDistance, 3);
      Serial.print(" | distance: ");
      Serial.print(distance, 1);
      Serial.println(" cm");
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
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MR60BHA2</title>
<style>
body {
  font-family: Arial;
  background: #111;
  color: #fff;
  max-width: 720px;
  margin: auto;
  padding: 18px;
}
section {
  background: #222;
  padding: 16px;
  border-radius: 12px;
  margin: 14px 0;
}
input, button, select {
  box-sizing: border-box;
  width: 100%;
  padding: 12px;
  margin: 6px 0;
  font-size: 16px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
a { color: #8ab4ff; }
.app-button {
  display: block;
  box-sizing: border-box;
  width: 100%;
  padding: 12px;
  margin: 6px 0;
  border-radius: 8px;
  background: #1769aa;
  color: #fff;
  text-align: center;
  text-decoration: none;
}
</style>
</head>
<body>

<h1>MR60BHA2</h1>

<section>
<h2>Device status</h2>
<pre id="status">Loading...</pre>
</section>

<section>
<h2>Wi-Fi setup</h2>
<button onclick="scan()">Scan Wi-Fi networks</button>
<select id="nets"
 onchange="document.getElementById('ssid').value=this.value">
<option value="">Select network...</option>
</select>
<input id="ssid" placeholder="Wi-Fi SSID">
<input id="password" type="password" placeholder="Wi-Fi password">
<button onclick="connectWiFi()">Save and connect</button>
<pre id="result"></pre>
</section>

<section>
<h2>Sensor data</h2>
<pre id="sensor">Loading...</pre>
<p><a href="/records.csv">Download saved CSV records</a></p>
<button onclick="clearRecords()">Clear saved records</button>
</section>

<section>
<h2>BLE information</h2>
<pre id="ble">Loading...</pre>
<p><a class="app-button" href="https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp" target="_blank" rel="noopener">Download nRF Connect for Android</a></p>
<p><a class="app-button" href="https://apps.apple.com/us/app/nrf-connect-for-mobile/id1054362403" target="_blank" rel="noopener">Download nRF Connect for iPhone</a></p>
</section>

<script>
async function get(path) {
  let r = await fetch(path);
  return await r.json();
}

async function refresh() {
  try {
    document.getElementById('status').textContent =
      JSON.stringify(await get('/api/device'), null, 2);

    document.getElementById('sensor').textContent =
      JSON.stringify(await get('/api/sensor'), null, 2);

    document.getElementById('ble').textContent =
      JSON.stringify(await get('/api/ble'), null, 2);
  } catch(e) {
    document.getElementById('status').textContent =
      'Device temporarily unavailable';
  }
}

async function clearRecords() {
  if (!confirm('Delete all saved records?')) return;

  try {
    let r = await fetch('/api/records/clear', {method:'POST'});
    document.getElementById('result').textContent =
      JSON.stringify(await r.json(), null, 2);
  } catch(e) {
    document.getElementById('result').textContent = 'Clear failed';
  }
}

async function scan() {
  document.getElementById('result').textContent = 'Scanning...';

  try {
    let a = await get('/api/wifi/scan');
    let s = document.getElementById('nets');

    s.innerHTML = '<option value="">Select network...</option>';

    a.forEach(n => {
      let o = document.createElement('option');
      o.value = n.ssid;
      o.textContent = n.ssid + ' (' + n.rssi + ' dBm)';
      s.appendChild(o);
    });

    document.getElementById('result').textContent =
      a.length + ' networks found';
  } catch(e) {
    document.getElementById('result').textContent = 'Scan failed';
  }
}

async function connectWiFi() {
  let ssid = document.getElementById('ssid').value;
  let password = document.getElementById('password').value;

  if (!ssid) {
    document.getElementById('result').textContent =
      'Enter Wi-Fi SSID';
    return;
  }

  let b = 'ssid=' + encodeURIComponent(ssid) +
          '&password=' + encodeURIComponent(password);

  try {
    let r = await fetch('/api/wifi/config', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: b
    });

    document.getElementById('result').textContent =
      JSON.stringify(await r.json(), null, 2);
  } catch(e) {
    document.getElementById('result').textContent =
      'The device may be reconnecting. Check its serial log and IP address.';
  }
}

setInterval(refresh, 2000);
refresh();
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

void handleRecordsDownload() {
  if (!filesystemReady ||
      !LittleFS.exists(RECORD_FILE)) {

    server.send(
      503,
      "text/plain",
      "Flash storage is not ready"
    );

    return;
  }

  File f = LittleFS.open(RECORD_FILE, FILE_READ);

  if (!f) {
    server.send(
      500,
      "text/plain",
      "Cannot open records file"
    );

    return;
  }

  server.sendHeader(
    "Content-Disposition",
    "attachment; filename=MR60BHA2_records.csv"
  );

  server.streamFile(f, "text/csv");
  f.close();
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
    HTTP_GET,
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
    "/records.csv",
    HTTP_GET,
    handleRecordsDownload
  );

  server.on(
    "/api/records/clear",
    HTTP_POST,
    handleRecordsClear
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
