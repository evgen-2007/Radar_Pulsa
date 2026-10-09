
#include <Arduino.h>
#include <HardwareSerial.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include <NimBLEDevice.h>

// ============================================================
// XIAO ESP32-C6 + MR60BHA2
// Wi-Fi, setup AP, web server, BLE JSON, status LED
// nRF Connect link included in Bluetooth section
// ============================================================

#define DEVICE_NAME       "MR60BHA2"
#define BLE_DEVICE_NAME   "XIAO-C6"

#define BLE_SERVICE_UUID        "7a1e0001-8b4f-4d2a-9c21-1234567890ab"
#define BLE_CHARACTERISTIC_UUID "7a1e0002-8b4f-4d2a-9c21-1234567890ab"

static const int LED_PIN = 15;
static const bool LED_ACTIVE_LOW = true;

HardwareSerial radarSerial(0);

WebServer server(80);
Preferences preferences;

String savedSSID;
String savedPassword;
String apName;

bool provisioningMode = false;
bool wifiConnected = false;
bool bleStarted = false;
bool bleClientConnected = false;

unsigned long lastWiFiAttempt = 0;
unsigned long wifiConnectStarted = 0;
unsigned long lastBLENotify = 0;
unsigned long lastLEDUpdate = 0;
unsigned long lastWiFiStatusPrint = 0;

const unsigned long WIFI_CONNECT_TIMEOUT = 15000;
const unsigned long WIFI_RETRY_INTERVAL = 10000;
const unsigned long BLE_NOTIFY_INTERVAL = 1000;

NimBLEServer *bleServer = nullptr;
NimBLEService *bleService = nullptr;
NimBLECharacteristic *bleCharacteristic = nullptr;

float breathRate = 0.0f;
float heartRate = 0.0f;
float distance = 0.0f;

bool targetDetected = false;
bool breathValid = false;
bool heartValid = false;
bool distanceValid = false;

unsigned long lastBreath = 0;
unsigned long lastHeart = 0;
unsigned long lastDistance = 0;

uint8_t header[8];
uint8_t dataBuf[64];

int headerPos = 0;
uint16_t dataLen = 0;
uint16_t frameType = 0;
int dataPos = 0;

enum ParserState {
  WAIT_SOF,
  READ_HEADER,
  READ_DATA,
  READ_CHECKSUM
};

ParserState parserState = WAIT_SOF;

// ============================================================
// LED
// ============================================================

void setLED(bool on) {
  digitalWrite(
    LED_PIN,
    (on ^ LED_ACTIVE_LOW) ? HIGH : LOW
  );
}

void updateStatusLED() {
  unsigned long now = millis();

  if (wifiConnected && WiFi.status() == WL_CONNECTED) {
    setLED(true);
    return;
  }

  unsigned long interval = provisioningMode ? 700 : 180;

  if (now - lastLEDUpdate >= interval) {
    lastLEDUpdate = now;

    static bool ledState = false;
    ledState = !ledState;

    setLED(ledState);
  }
}

// ============================================================
// RADAR UART PARSER
// ============================================================

float bytesToFloat(const uint8_t *p) {
  union {
    uint8_t b[4];
    float f;
  } u;

  u.b[0] = p[0];
  u.b[1] = p[1];
  u.b[2] = p[2];
  u.b[3] = p[3];

  return u.f;
}

void resetParser() {
  headerPos = 0;
  dataLen = 0;
  frameType = 0;
  dataPos = 0;
  parserState = WAIT_SOF;
}

void processFrame() {
  if (frameType == 0x0A14 && dataLen >= 4) {
    breathRate = bytesToFloat(dataBuf);
    breathValid = true;
    lastBreath = millis();
  }
  else if (frameType == 0x0A15 && dataLen >= 4) {
    heartRate = bytesToFloat(dataBuf);
    heartValid = true;
    lastHeart = millis();
  }
  else if (frameType == 0x0A16 && dataLen >= 8) {
    uint32_t flag =
      ((uint32_t)dataBuf[0]) |
      ((uint32_t)dataBuf[1] << 8) |
      ((uint32_t)dataBuf[2] << 16) |
      ((uint32_t)dataBuf[3] << 24);

    distance = bytesToFloat(&dataBuf[4]);
    targetDetected = (flag != 0);
    distanceValid = targetDetected;
    lastDistance = millis();
  }
}

void parseByte(uint8_t b) {
  switch (parserState) {
    case WAIT_SOF:
      if (b == 0x01) {
        header[0] = b;
        headerPos = 1;
        parserState = READ_HEADER;
      }
      break;

    case READ_HEADER:
      if (headerPos < 8) {
        header[headerPos++] = b;
      }

      if (headerPos >= 8) {
        dataLen =
          ((uint16_t)header[3] << 8) |
          header[4];

        frameType =
          ((uint16_t)header[5] << 8) |
          header[6];

        if (dataLen > sizeof(dataBuf)) {
          resetParser();
          break;
        }

        dataPos = 0;

        parserState =
          (dataLen == 0) ? READ_CHECKSUM : READ_DATA;
      }
      break;

    case READ_DATA:
      if (dataPos < (int)sizeof(dataBuf)) {
        dataBuf[dataPos++] = b;
      }

      if (dataPos >= dataLen) {
        parserState = READ_CHECKSUM;
      }
      break;

    case READ_CHECKSUM:
      processFrame();
      resetParser();
      break;
  }
}

// ============================================================
// WI-FI CONFIGURATION
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
  Serial.println(
    savedSSID.length() ? savedSSID : "NONE"
  );
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
  }
  else {
    Serial.println(
      "ERROR: could not start configuration AP"
    );
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

    if (provisioningMode) {
      WiFi.softAPdisconnect(true);
      provisioningMode = false;
      WiFi.mode(WIFI_STA);
    }

    return true;
  }

  wifiConnected = false;

  Serial.println(
    "Wi-Fi not connected yet; retry will continue in background."
  );

  return false;
}

// ============================================================
// SENSOR JSON
// ============================================================

String getSensorJSON() {
  unsigned long now = millis();

  bool target =
    targetDetected &&
    (now - lastDistance < 3000);

  bool breath =
    breathValid &&
    (now - lastBreath < 5000) &&
    breathRate > 0;

  bool heart =
    heartValid &&
    (now - lastHeart < 5000) &&
    heartRate > 0;

  bool dist =
    distanceValid &&
    (now - lastDistance < 3000);

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

  json += ",\"distance_valid\":";
  json += dist ? "true" : "false";

  json += "}";

  return json;
}

// ============================================================
// BLE
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

  bleService = bleServer->createService(
    BLE_SERVICE_UUID
  );

  bleCharacteristic = bleService->createCharacteristic(
    BLE_CHARACTERISTIC_UUID,
    NIMBLE_PROPERTY::READ |
    NIMBLE_PROPERTY::NOTIFY
  );

  bleCharacteristic->setValue(
    "{\"status\":\"starting\"}"
  );

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
// WEB PAGE
// ============================================================

void handleRoot() {
  String html = R"HTML(
<!doctype html>
<html lang="uk">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MR60BHA2</title>

<style>
* {
  box-sizing: border-box;
}

body {
  font-family: Arial, sans-serif;
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

h1 {
  color: #c4b5fd;
}

h2 {
  margin-top: 0;
}

input,
button,
select {
  box-sizing: border-box;
  width: 100%;
  padding: 12px;
  margin: 6px 0;
  font-size: 16px;
  border-radius: 8px;
}

button {
  color: white;
  background: #55349b;
  border: 1px solid #7954c7;
  cursor: pointer;
}

button:hover {
  background: #6846b4;
}

input,
select {
  background: #171717;
  color: white;
  border: 1px solid #555;
}

pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #171717;
  padding: 12px;
  border-radius: 8px;
}

.app-link {
  display: block;
  margin-top: 10px;
  padding: 12px;
  text-align: center;
  color: #c4b5fd;
  background: #211832;
  border: 1px solid #6744a3;
  border-radius: 11px;
  text-decoration: none;
  font-size: 14px;
}

.app-link:hover {
  background: #342453;
}

.note {
  color: #bbb;
  font-size: 13px;
  line-height: 1.5;
}
</style>
</head>

<body>
<h1>MR60BHA2</h1>

<section>
  <h2>Стан пристрою</h2>
  <pre id="status">Завантаження...</pre>
</section>

<section>
  <h2>Налаштування Wi-Fi</h2>

  <button onclick="scan()">
    Знайти мережі Wi-Fi
  </button>

  <select id="nets"
    onchange="document.getElementById('ssid').value=this.value">
    <option value="">Виберіть мережу...</option>
  </select>

  <input id="ssid" placeholder="Назва Wi-Fi (SSID)">

  <input id="password"
    type="password"
    placeholder="Пароль Wi-Fi">

  <button onclick="connectWiFi()">
    Зберегти та підключитися
  </button>

  <pre id="result"></pre>

  <p class="note">
    Якщо пристрій перебуває в режимі налаштування,
    відкрийте адресу http://192.168.4.1
  </p>
</section>

<section>
  <h2>Показники сенсора</h2>
  <pre id="sensor">Завантаження...</pre>
</section>

<section>
  <h2>Bluetooth BLE</h2>

  <p>
    Назва пристрою: <b>XIAO-C6</b>
  </p>

  <p class="note">
    Для підключення до сенсора та перегляду BLE-характеристик
    скористайтеся застосунком nRF Connect.
  </p>

  <a
    href="https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp"
    target="_blank"
    rel="noopener noreferrer"
    class="app-link">
    ↗ Встановити nRF Connect із Google Play
  </a>

  <pre id="ble">Завантаження...</pre>
</section>

<script>
async function get(path) {
  const response = await fetch(path);
  return await response.json();
}

async function refresh() {
  try {
    document.getElementById('status').textContent =
      JSON.stringify(await get('/api/device'), null, 2);

    document.getElementById('sensor').textContent =
      JSON.stringify(await get('/api/sensor'), null, 2);

    document.getElementById('ble').textContent =
      JSON.stringify(await get('/api/ble'), null, 2);
  } catch (error) {
    document.getElementById('status').textContent =
      'Пристрій тимчасово недоступний';
  }
}

async function scan() {
  const result = document.getElementById('result');
  result.textContent = 'Пошук мереж Wi-Fi...';

  try {
    const networks = await get('/api/wifi/scan');
    const select = document.getElementById('nets');

    select.innerHTML =
      '<option value="">Виберіть мережу...</option>';

    networks.forEach(network => {
      const option = document.createElement('option');

      option.value = network.ssid;
      option.textContent =
        network.ssid + ' (' + network.rssi + ' dBm)';

      select.appendChild(option);
    });

    result.textContent =
      'Знайдено мереж: ' + networks.length;
  } catch (error) {
    result.textContent = 'Не вдалося знайти мережі';
  }
}

async function connectWiFi() {
  const ssid = document.getElementById('ssid').value;
  const password = document.getElementById('password').value;
  const result = document.getElementById('result');

  if (!ssid) {
    result.textContent = 'Введіть назву мережі Wi-Fi';
    return;
  }

  const body =
    'ssid=' + encodeURIComponent(ssid) +
    '&password=' + encodeURIComponent(password);

  try {
    const response = await fetch('/api/wifi/config', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: body
    });

    result.textContent =
      JSON.stringify(await response.json(), null, 2);
  } catch (error) {
    result.textContent =
      'Пристрій може перепідключатися до Wi-Fi. ' +
      'Перевірте IP-адресу та повідомлення в Serial Monitor.';
  }
}

setInterval(refresh, 2000);
refresh();
</script>

</body>
</html>
)HTML";

  server.send(
    200,
    "text/html; charset=utf-8",
    html
  );
}

// ============================================================
// API
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
  json += WiFi.status() == WL_CONNECTED ? "true" : "false";

  if (WiFi.status() == WL_CONNECTED) {
    json += ",\"wifi_ip\":\"" +
      WiFi.localIP().toString() + "\"";

    json += ",\"rssi\":" + String(WiFi.RSSI());
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

  String json =
    "{\"connected\":" +
    String(WiFi.status() == WL_CONNECTED ? "true" : "false") +
    ",\"ssid\":\"" + ssid +
    "\",\"ip\":\"" + ip +
    "\",\"rssi\":" +
    String(WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0) +
    "}";

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
      WiFi.encryptionType(i) == WIFI_AUTH_OPEN
        ? "false"
        : "true";
    json += "}";
  }

  json += "]";

  WiFi.scanDelete();

  server.send(200, "application/json", json);
}

void handleWiFiConfig() {
  if (
    !server.hasArg("ssid") ||
    !server.hasArg("password") ||
    server.arg("ssid").length() == 0
  ) {
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
  server.send(
    200,
    "application/json",
    getSensorJSON()
  );
}

void handleBLE() {
  String json =
    "{\"started\":" +
    String(bleStarted ? "true" : "false") +
    ",\"connected\":" +
    String(bleClientConnected ? "true" : "false") +
    ",\"name\":\"" + String(BLE_DEVICE_NAME) +
    "\",\"service_uuid\":\"" + String(BLE_SERVICE_UUID) +
    "\",\"characteristic_uuid\":\"" +
    String(BLE_CHARACTERISTIC_UUID) + "\"}";

  server.send(200, "application/json", json);
}

// ============================================================
// WEB SERVER
// ============================================================

void startWebServer() {
  server.on("/", HTTP_GET, handleRoot);

  server.on("/api/device", HTTP_GET, handleDevice);
  server.on("/api/wifi/status", HTTP_GET, handleWiFiStatus);
  server.on("/api/wifi/scan", HTTP_GET, handleWiFiScan);
  server.on("/api/wifi/config", HTTP_POST, handleWiFiConfig);
  server.on("/api/wifi/reset", HTTP_GET, handleWiFiReset);
  server.on("/api/sensor", HTTP_GET, handleSensor);
  server.on("/api/ble", HTTP_GET, handleBLE);

  server.begin();

  Serial.println("HTTP server started");
}

// ============================================================
// SETUP
// ============================================================

void setup() {
  pinMode(LED_PIN, OUTPUT);
  setLED(false);

  Serial.begin(115200);
  delay(1500);

  Serial.println();
  Serial.println("======================================");
  Serial.println("MR60BHA2 SMART DEVICE - XIAO ESP32-C6");
  Serial.println("======================================");

  radarSerial.begin(115200);

  Serial.println("MR60BHA2 UART started (115200)");

  apName = getAPName();

  loadWiFiCredentials();

  // Start BLE before Wi-Fi.
  startBLE();

  bool connected = connectToSavedWiFi(true);

  if (!connected) {
    startProvisioningAP();
  }

  startWebServer();

  Serial.println("--------------------------------------");

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("NORMAL WI-FI MODE");

    Serial.print("Device IP: ");
    Serial.println(WiFi.localIP());
  }
  else {
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
  while (radarSerial.available()) {
    parseByte((uint8_t)radarSerial.read());
  }

  server.handleClient();

  updateBLE();
  updateStatusLED();

  unsigned long now = millis();

  if (WiFi.status() == WL_CONNECTED) {
    if (!wifiConnected) {
      wifiConnected = true;

      Serial.print("Wi-Fi restored. IP address: ");
      Serial.println(WiFi.localIP());

      if (provisioningMode) {
        WiFi.softAPdisconnect(true);
        provisioningMode = false;
        WiFi.mode(WIFI_STA);
      }
    }

    if (now - lastWiFiStatusPrint > 30000) {
      lastWiFiStatusPrint = now;

      Serial.print("Wi-Fi OK, IP: ");
      Serial.print(WiFi.localIP());

      Serial.print(", RSSI: ");
      Serial.println(WiFi.RSSI());
    }
  }
  else {
    if (wifiConnected) {
      wifiConnected = false;

      Serial.println(
        "Wi-Fi connection lost; will retry automatically."
      );

      if (!provisioningMode) {
        startProvisioningAP();
      }
    }

    if (
      savedSSID.length() &&
      now - lastWiFiAttempt >= WIFI_RETRY_INTERVAL
    ) {
      Serial.println("Retrying saved Wi-Fi connection...");
      connectToSavedWiFi(false);
    }
  }
}
